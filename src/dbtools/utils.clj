(ns dbtools.utils
  (:require
   [clojure.tools.logging :as log]
   [clj-stacktrace.repl :refer [pst-str]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.reader.edn :as edn]
   [clojure.java.shell :as sh]
   [clojure.java.jdbc :as sql]
   [clojure.test :refer :all]
   [environ.core :as env]
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

;;; config

(def default_config_filename ".dbtools.edn")

(defn set-config
  "Set the configs map using command line options and the configuration file."
  [options]
  (let [cfgfile (io/file (get options :config default_config_filename))]
    (if (.exists cfgfile)
      (merge (edn/read-string (slurp cfgfile)) options)
      options)))


(deftest set-config-test
  (is (= {:postgres_dir "/usr/lib/postgresql/9.3/bin/",
          :db_host "localhost",
          :db_name "dbtools",
          :db_temp_dir "/tmp/",
          :db_file "/tmp/questra.backup"}
         (set-config {})) "default configuration file")
  (is (= {:postgres_dir "/usr/lib/postgresql/9.3/bin/",
          :config "test/test_cfg.edn"
          :db_host "localhost",
          :db_name "dbtools",
          :db_temp_dir "/tmp/",
          :db_file "/tmp/questra.backup"}
         (set-config {:config "test/test_cfg.edn"})) "test configuration file")
  )


;;; shell commands

(defn run-db
  "Run a shell database command using the system variables PGUSER and PGPASSWORD.
  params:
  - config a map with database settings
  - cmd the shell command to be executed
  - additonal_opts command additonal options [strings vector]
  returns: the standard pair [config errors]"
  [config cmd additonal_opts]
  (let [{:keys [db_user db_password postgres_dir db_host db_port]} config
        params (into [(str postgres_dir cmd)
                      "--no-password"
                      (str "--host=" db_host)
                      (str "--port=" db_port)]
                     additonal_opts)]
    (sh/with-sh-env (assoc (zipmap (keys (System/getenv)) (vals (System/getenv)))
                           "PGUSER" db_user
                           "PGPASSWORD" db_password)
      (let [ret (apply sh/sh params)]
        (if (zero? (ret :exit))
          [config nil]
          [nil (ret :err)])))))

(defn create-empty-db
  "Creates an empty database.
  params:
  - config a map with database settings"
  [config]
  (run-db config "createdb" [(:db_name config)]))

(defn delete-db
  "Deletes a database.
  params:
  - config a map with database settings"
  [config]
  (run-db config "dropdb" [(:db_name config)]))


(deftest db-command-test
  (let [config (set-config {:config (env/env :config-file)})]
    (let [[ret err] (create-empty-db config)]
      (is (nil? err) "error")
      (is (= config ret) "ret"))
    (let [[ret err] (delete-db config)]
      (is (nil? err) "error")
      (is (= config ret) "ret"))
    ))


(defn backup-db
  "Backups a database.
  params:
  - config a map with database settings"
  [config]
  (let [{:keys [db_archive db_schema db_name]} config]
    (run-db config "pg_dump" [(if db_archive "--format=c" "--format=p")
                              (if db_schema "--schema-only" "--ignore-version")
                              db_name])))

(defn restore-custom-db
  "Restores a database from an archive in custom format.
  params:
  - config a map with database settings"
  [config]
  (let [{:keys [db_file db_schema db_name]} config]
    (run-db config "pg_restore" [(str "--dbname=" db_name)
                                 db_file])))



;; Functions related to the sql files execution
(defn- check-sql-line
  [[acc cmds] line]
  (let [l (str/trim line)]
    (cond
      (str/blank? l) [acc cmds] ; empty line
      (str/starts-with? l "--") [acc cmds] ; comment
      (str/ends-with? l ";") ["" (conj cmds (str acc l))]
      :else [(str acc l "\n") cmds])))

(defn get-sql-commands
  "Get the sql commands from a sql file.
  param: fname - file name
  returns: a strings vector, each element a sql command."
  [fname]
  (with-open [r (io/reader fname)]
    (second (reduce check-sql-line ["" []] (line-seq r)))))

(deftest get-sql-commands-test
  (is (=  ["DROP TABLE IF EXISTS dbversion;"
           "CREATE TABLE dbversion\n(\ndbversion_version integer NOT NULL,\ndbversion_date timestamp with time zone NOT NULL,\ndbversion_comments character varying(256),\ndbversion_last boolean NOT NULL,\nCONSTRAINT dbversion_pkey PRIMARY KEY (dbversion_version)\n)\nWITH (\nOIDS=FALSE\n);"]
          (get-sql-commands "test/001_dbversion.sql" ))))

(defn make-db-spec
  [config]
  (let [{:keys [db_name db_host db_port db_user db_password]} config]
    {:subprotocol "postgresql"
     :subname (str "//" db_host ":" db_port "/" db_name)
     :user db_user
     :password db_password}))


(defn exec-sql-commands
  "Execute the sql commands read from a file.
  params: system - system map contains the key db_conn - database connection
          fname - filename of the sql commands's file
  returns: [system errors]"
  [system fname]
  (with-checked-errors
    (sql/db-do-commands (:db_conn system) (get-sql-commands fname))
    [system nil]))

(def get_tables_query
  ["SELECT tablename
    FROM pg_catalog.pg_tables
    WHERE schemaname='public'
    ORDER BY tablename;"])

(defn get-tables-names
  [system]
  (with-checked-errors
    (sql/query (:db_conn system)
               get_tables_query
               {:result-set-fn (fn [rs]
                  [(assoc system :resultset (doall rs)) nil])})))


(deftest exec-sql-commands-test
  (let [config (set-config {:config (env/env :config-file)})
        [ret err] (err->> config
                          create-empty-db
                          (fn [cfg]
                            (sql/with-db-transaction [dbconn (make-db-spec cfg)]
                              (err->> (assoc cfg :db_conn dbconn)
                                      #(exec-sql-commands % "test/001_dbversion.sql")
                                      get-tables-names)))
                          )]
    (delete-db config)
    (is (= config (dissoc ret :db_conn :resultset)) "return value")
    (is (= [{:tablename "dbversion"}] (:resultset ret)) "resultset")
    (is (nil? err) "error value")))
