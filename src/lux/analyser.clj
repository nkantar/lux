(ns lux.analyser
  (:require (clojure [template :refer [do-template]])
            [clojure.core.match :as M :refer [matchv]]
            clojure.core.match.array
            (lux [base :as & :refer [exec return fail]]
                 [parser :as &parser]
                 [type :as &type]
                 [macro :as &macro]
                 [host :as &host])
            (lux.analyser [base :as &&]
                          [lux :as &&lux]
                          [host :as &&host])))

;; [Utils]
(defn ^:private parse-handler [[catch+ finally+] token]
  (matchv ::M/objects [token]
    [["Form" ["Cons" [["Symbol" [_ "jvm-catch"]]
                      ["Cons" [["Symbol" [_ ?ex-class]]
                               ["Cons" [["Symbol" [_ ?ex-arg]]
                                        ["Cons" [?catch-body
                                                 ["Nil" _]]]]]]]]]]]
    [(concat catch+ (list [?ex-class ?ex-arg ?catch-body])) finally+]

    [["Form" ["Cons" [["Symbol" [_ "jvm-finally"]]
                      ["Cons" [?finally-body
                               ["Nil" _]]]]]]]
    [catch+ ?finally-body]))

(defn ^:private analyse-basic-ast [analyse eval! token]
  ;; (prn 'analyse-basic-ast (aget token 0))
  ;; (prn 'analyse-basic-ast token (&/show-ast token))
  (matchv ::M/objects [token]
    ;; Standard special forms
    [["lux;Bool" ?value]]
    (return (&/|list (&/V "Expression" (&/T (&/V "bool" ?value) (&/V "lux;TData" (&/T "java.lang.Boolean" (&/V "lux;Nil" nil)))))))

    [["lux;Int" ?value]]
    (return (&/|list (&/V "Expression" (&/T (&/V "int" ?value)  (&/V "lux;TData" (&/T "java.lang.Long" (&/V "lux;Nil" nil)))))))

    [["lux;Real" ?value]]
    (return (&/|list (&/V "Expression" (&/T (&/V "real" ?value) (&/V "lux;TData" (&/T "java.lang.Double" (&/V "lux;Nil" nil)))))))

    [["lux;Char" ?value]]
    (return (&/|list (&/V "Expression" (&/T (&/V "char" ?value) (&/V "lux;TData" (&/T "java.lang.Character" (&/V "lux;Nil" nil)))))))

    [["lux;Text" ?value]]
    (return (&/|list (&/V "Expression" (&/T (&/V "text" ?value) (&/V "lux;TData" (&/T "java.lang.String" (&/V "lux;Nil" nil)))))))

    [["lux;Tuple" ?elems]]
    (&&lux/analyse-tuple analyse ?elems)

    [["lux;Record" ?elems]]
    (&&lux/analyse-record analyse ?elems)

    [["lux;Tag" [?module ?name]]]
    (let [tuple-type (&/V "lux;Tuple" (&/V "lux;Nil" nil))
          ?tag (str ?module ";" ?name)]
      (return (&/|list (&/V "Expression" (&/T (&/V "variant" (&/T ?tag (&/V "Expression" (&/T (&/V "tuple" (&/|list)) tuple-type))))
                                              (&/V "lux;TVariant" (&/V "lux;Cons" (&/T (&/T ?tag tuple-type) (&/V "lux;Nil" nil)))))))))

    [["lux;Symbol" [_ "jvm-null"]]]
    (return (&/|list (&/V "Expression" (&/T (&/V "jvm-null" nil) (&/V "lux;TData" (&/T "null" (&/V "lux;Nil" nil)))))))
    
    [["lux;Symbol" ?ident]]
    (&&lux/analyse-ident analyse ?ident)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "case'"]]
                              ["lux;Cons" [?variant ?branches]]]]]]
    (&&lux/analyse-case analyse ?variant ?branches)
    
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "lambda'"]]
                              ["lux;Cons" [["lux;Symbol" [_ ?self]]
                                           ["lux;Cons" [["lux;Symbol" [_ ?arg]]
                                                        ["lux;Cons" [?body
                                                                     ["lux;Nil" _]]]]]]]]]]]
    (&&lux/analyse-lambda analyse ?self ?arg ?body)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "get@'"]] ["lux;Cons" [["lux;Tag" ?slot] ["lux;Cons" [?record ["lux;Nil" _]]]]]]]]]
    (&&lux/analyse-get analyse ?slot ?record)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "set@'"]] ["lux;Cons" [["lux;Tag" ?slot] ["lux;Cons" [?value ["lux;Cons" [?record ["lux;Nil" _]]]]]]]]]]]
    (&&lux/analyse-set analyse ?slot ?value ?record)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "def'"]] ["lux;Cons" [["lux;Symbol" [_ ?name]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]]]
    (&&lux/analyse-def analyse ?name ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "declare-macro"]] ["lux;Cons" [["lux;Symbol" ?ident] ["lux;Nil" _]]]]]]]
    (&&lux/analyse-declare-macro ?ident)
    
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "import'"]] ["lux;Cons" [["lux;Text" ?path] ["lux;Nil" _]]]]]]]
    (&&lux/analyse-import analyse ?path)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ ":"]] ["lux;Cons" [?value ["lux;Cons" [?type ["lux;Nil" _]]]]]]]]]
    (&&lux/analyse-check analyse eval! ?type ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "coerce"]] ["lux;Cons" [?type ["lux;Cons" [?value ["lux;Nil" _]]]]]]]]]
    (&&lux/analyse-coerce analyse eval! ?type ?value)

    ;; Host special forms
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "exec"]] ?exprs]]]]
    (&&host/analyse-exec analyse ?exprs)

    ;; Integer arithmetic
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-iadd"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-iadd analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-isub"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-isub analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-imul"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-imul analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-idiv"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-idiv analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-irem"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-irem analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-ieq"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-ieq analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-ilt"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-ilt analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-igt"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-igt analyse ?x ?y)

    ;; Long arithmetic
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-ladd"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-ladd analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-lsub"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-lsub analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-lmul"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-lmul analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-ldiv"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-ldiv analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-lrem"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-lrem analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-leq"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-leq analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-llt"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-llt analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-lgt"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-lgt analyse ?x ?y)

    ;; Float arithmetic
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-fadd"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-fadd analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-fsub"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-fsub analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-fmul"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-fmul analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-fdiv"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-fdiv analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-frem"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-frem analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-feq"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-feq analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-flt"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-flt analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-fgt"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-fgt analyse ?x ?y)

    ;; Double arithmetic
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-dadd"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-dadd analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-dsub"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-dsub analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-dmul"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-dmul analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-ddiv"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-ddiv analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-drem"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-drem analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-deq"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-deq analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-dlt"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-dlt analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-dgt"]] ["lux;Cons" [?y ["lux;Cons" [?x ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-dgt analyse ?x ?y)

    ;; Objects
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-null?"]] ["lux;Cons" [?object ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-null? analyse ?object)
    
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-new"]]
                              ["lux;Cons" [["lux;Symbol" [_ ?class]]
                                           ["lux;Cons" [["lux;Tuple" ?classes]
                                                        ["lux;Cons" [["lux;Tuple" ?args]
                                                                     ["lux;Nil" _]]]]]]]]]]]
    (&&host/analyse-jvm-new analyse ?class ?classes ?args)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-getstatic"]]
                              ["lux;Cons" [["lux;Symbol" [_ ?class]]
                                           ["lux;Cons" [["lux;Text" ?field]
                                                        ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-getstatic analyse ?class ?field)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-getfield"]]
                              ["lux;Cons" [["lux;Symbol" [_ ?class]]
                                           ["lux;Cons" [["lux;Text" ?field]
                                                        ["lux;Cons" [?object
                                                                     ["lux;Nil" _]]]]]]]]]]]
    (&&host/analyse-jvm-getfield analyse ?class ?field ?object)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-putstatic"]]
                              ["lux;Cons" [["lux;Symbol" [_ ?class]]
                                           ["lux;Cons" [["lux;Text" ?field]
                                                        ["lux;Cons" [?value
                                                                     ["lux;Nil" _]]]]]]]]]]]
    (&&host/analyse-jvm-putstatic analyse ?class ?field ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-putfield"]]
                              ["lux;Cons" [["lux;Symbol" [_ ?class]]
                                           ["lux;Cons" [["lux;Text" ?field]
                                                        ["lux;Cons" [?object
                                                                     ["lux;Cons" [?value
                                                                                  ["lux;Nil" _]]]]]]]]]]]]]
    (&&host/analyse-jvm-putfield analyse ?class ?field ?object ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-invokestatic"]]
                              ["lux;Cons" [["lux;Symbol" [_ ?class]]
                                           ["lux;Cons" [["lux;Text" ?method]
                                                        ["lux;Cons" [["lux;Tuple" ?classes]
                                                                     ["lux;Cons" [["lux;Tuple" ?args]
                                                                                  ["lux;Nil" _]]]]]]]]]]]]]
    (&&host/analyse-jvm-invokestatic analyse ?class ?method ?classes ?args)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-invokevirtual"]]
                              ["lux;Cons" [["lux;Symbol" [_ ?class]]
                                           ["lux;Cons" [["lux;Text" ?method]
                                                        ["lux;Cons" [["lux;Tuple" ?classes]
                                                                     ["lux;Cons" [?object
                                                                                  ["lux;Cons" [["lux;Tuple" ?args]
                                                                                               ["lux;Nil" _]]]]]]]]]]]]]]]
    (&&host/analyse-jvm-invokevirtual analyse ?class ?method ?classes ?object ?args)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-invokeinterface"]]
                              ["lux;Cons" [["lux;Symbol" [_ ?class]]
                                           ["lux;Cons" [["lux;Text" ?method]
                                                        ["lux;Cons" [["lux;Tuple" ?classes]
                                                                     ["lux;Cons" [?object
                                                                                  ["lux;Cons" [["lux;Tuple" ?args]
                                                                                               ["lux;Nil" _]]]]]]]]]]]]]]]
    (&&host/analyse-jvm-invokeinterface analyse ?class ?method ?classes ?object ?args)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-invokespecial"]]
                              ["lux;Cons" [["lux;Symbol" [_ ?class]]
                                           ["lux;Cons" [["lux;Text" ?method]
                                                        ["lux;Cons" [["lux;Tuple" ?classes]
                                                                     ["lux;Cons" [?object
                                                                                  ["lux;Cons" [["lux;Tuple" ?args]
                                                                                               ["lux;Nil" _]]]]]]]]]]]]]]]
    (&&host/analyse-jvm-invokespecial analyse ?class ?method ?classes ?object ?args)
    
    ;; Exceptions
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-try"]]
                              ["lux;Cons" [?body
                                           ?handlers]]]]]]
    (&&host/analyse-jvm-try analyse ?body (&/fold parse-handler [(list) nil] ?handlers))

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-throw"]]
                              ["lux;Cons" [?ex
                                           ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-throw analyse ?ex)

    ;; Syncronization/monitos
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-monitorenter"]]
                              ["lux;Cons" [?monitor
                                           ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-monitorenter analyse ?monitor)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-monitorexit"]]
                              ["lux;Cons" [?monitor
                                           ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-monitorexit analyse ?monitor)

    ;; Primitive conversions
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-d2f"]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-d2f analyse ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-d2i"]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-d2i analyse ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-d2l"]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-d2l analyse ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-f2d"]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-f2d analyse ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-f2i"]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-f2i analyse ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-f2l"]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-f2l analyse ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-i2b"]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-i2b analyse ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-i2c"]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-i2c analyse ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-i2d"]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-i2d analyse ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-i2f"]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-i2f analyse ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-i2l"]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-i2l analyse ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-i2s"]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-i2s analyse ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-l2d"]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-l2d analyse ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-l2f"]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-l2f analyse ?value)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-l2i"]] ["lux;Cons" [?value ["lux;Nil" _]]]]]]]
    (&&host/analyse-jvm-l2i analyse ?value)

    ;; Bitwise operators
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-iand"]] ["lux;Cons" [?x ["lux;Cons" [?y ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-iand analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-ior"]] ["lux;Cons" [?x ["lux;Cons" [?y ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-ior analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-land"]] ["lux;Cons" [?x ["lux;Cons" [?y ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-land analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-lor"]] ["lux;Cons" [?x ["lux;Cons" [?y ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-lor analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-lxor"]] ["lux;Cons" [?x ["lux;Cons" [?y ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-lxor analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-lshl"]] ["lux;Cons" [?x ["lux;Cons" [?y ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-lshl analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-lshr"]] ["lux;Cons" [?x ["lux;Cons" [?y ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-lshr analyse ?x ?y)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-lushr"]] ["lux;Cons" [?x ["lux;Cons" [?y ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-lushr analyse ?x ?y)
    
    ;; Arrays
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-new-array"]] ["lux;Cons" [["lux;Symbol" [_ ?class]] ["lux;Cons" [["lux;Int" ?length] ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-new-array analyse ?class ?length)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-aastore"]] ["lux;Cons" [?array ["lux;Cons" [["lux;Int" ?idx] ["lux;Cons" [?elem ["lux;Nil" _]]]]]]]]]]]
    (&&host/analyse-jvm-aastore analyse ?array ?idx ?elem)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-aaload"]] ["lux;Cons" [?array ["lux;Cons" [["lux;Int" ?idx] ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-aaload analyse ?array ?idx)

    ;; Classes & interfaces
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-class"]] ["lux;Cons" [["lux;Symbol" [_ ?name]] ["lux;Cons" [["lux;Symbol" [_ ?super-class]] ["lux;Cons" [["lux;Tuple" ?fields] ["lux;Nil" _]]]]]]]]]]]
    (&&host/analyse-jvm-class analyse ?name ?super-class ?fields)

    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-interface"]] ["lux;Cons" [["lux;Symbol" [_ ?name]] ?members]]]]]]
    (&&host/analyse-jvm-interface analyse ?name ?members)

    ;; Programs
    [["lux;Form" ["lux;Cons" [["lux;Symbol" [_ "jvm-program"]] ["lux;Cons" [["lux;Symbol" [_ ?args]] ["lux;Cons" [?body ["lux;Nil" _]]]]]]]]]
    (&&host/analyse-jvm-program analyse ?args ?body)
    
    [_]
    (fail (str "[Analyser Error] Unmatched token: " (&/show-ast token)))))

(defn ^:private analyse-ast [eval!]
  (fn [token]
    ;; (prn 'analyse-ast token)
    (matchv ::M/objects [token]
      [["lux;Form" ["lux;Cons" [["lux;Tag" [?module ?name]] ?values]]]]
      (exec [;; :let [_ (prn 'PRE-ASSERT)]
             :let [?tag (str ?module ";" ?name)]
             :let [_ (assert (= 1 (&/|length ?values)) (str "[Analyser Error] Can only tag 1 value: " (pr-str token)))]
             ;; :let [_ (prn 'POST-ASSERT)]
             =value (&&/analyse-1 (analyse-ast eval!) (&/|head ?values))
             =value-type (&&/expr-type =value)]
        (return (&/|list (&/V "Expression" (&/T (&/V "variant" (&/T ?tag =value)) (&/V "lux;TVariant" (&/V "lux;Cons" (&/T (&/T ?tag =value-type) (&/V "lux;Nil" nil)))))))))
      
      [["lux;Form" ["lux;Cons" [?fn ?args]]]]
      (fn [state]
        ;; (prn '(&/show-ast ?fn) (&/show-ast ?fn))
        (matchv ::M/objects [((&&/analyse-1 (analyse-ast eval!) ?fn) state)]
          [["lux;Right" [state* =fn]]]
          ((&&lux/analyse-apply (analyse-ast eval!) =fn ?args) state*)

          [_]
          (do ;; (prn 'analyse-ast/token (aget token 0) (&/show-state state))
              ((analyse-basic-ast (analyse-ast eval!) eval! token) state))))
      
      [_]
      (analyse-basic-ast (analyse-ast eval!) eval! token))))

;; [Resources]
(defn analyse [eval!]
  (exec [asts &parser/parse
         ;; :let [_ (prn 'analyse/asts asts)]
         ]
    (&/flat-map% (analyse-ast eval!) asts)))
