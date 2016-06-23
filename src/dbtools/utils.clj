(ns dbtools.utils
  (:require
   [clojure.tools.logging :as log]
   [clj-stacktrace.repl :refer [pst-str]]
   ))



;;; Error management framework

(defn apply-or-error
  "Apply a function depending of the result of the previous one
    params: f - next function to apply
            [val err] - the result of the previous function"
  [f [val err]]
  (if (nil? err)
    (f val)
    [val err]))

(defmacro err->>
  [val & fns]
  (let [fns (for [f fns] `(apply-or-error ~f))]
    `(->> [~val nil]
          ~@fns)))

(defmacro with-checked-errors
  [& body]
  `(try
     ~@body
     (catch Exception e#
       (log/debug (pst-str e#))
       [nil (str e#)])))

