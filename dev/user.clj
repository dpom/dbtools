(ns user
  (:require
   [clojure.tools.namespace.repl :refer [refresh]]
   ))

(defn start
  "Start the application"
  []
  )

(defn stop
  "Stop the application"
  []
  )

(defn reset []
  (stop)
  (refresh :after 'user/start))
