(defproject dbtools "0.1.0-SNAPSHOT"
  :description "Database Management Tools"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.cli "0.3.5"]
                 [org.clojure/tools.reader "0.10.0"]
                 [org.clojure/java.jdbc "0.6.1"]
                 [clj-time "0.12.0"]
                 [postgresql "9.3-1102.jdbc41"]
                 [environ "1.0.3"]
                 [org.clojure/tools.logging "0.3.1"]
                 [log4j "1.2.17" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]]
  :pedantic? :abort
  :main ^:skip-aot dbtools.core
  :target-path "target/%s"
  :plugins [[lein-environ "1.0.1"]
            [lein-localrepo "0.5.3" :exclusions [org.clojure/clojure]]
            [codox "0.8.10" :exclusions [org.clojure/clojure leinjacker]]
            [lein-cljfmt "0.1.10"]]
  :profiles {:uberjar {:aot :all}
             :check {:global-vars {*warn-on-reflection* true}}
             :dev {:source-paths ["dev"]
                   :test-paths ["src"]
                   :resource-dirs ["log"]
                   :jvm-opts ["-Dlog4j.configuration=file:log/log4j.properties"]}
             :dan {:env {:config-file "dev/Dan.edn"}}
             :default [:base :system :user :provided :dev :dan]
             })
