(ns dbtools.dbtools
  (:require
   [clojure.tools.logging :as log]
   [clj-stacktrace.repl :refer [pst-str]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.reader.edn :as edn]
   [clojure.java.shell :as sh]
   [clojure.java.jdbc :as sql]
   [dbtools.utils :as utl]
   [clojure.test :refer :all]
   [environ.core :as env]
   ))


;;; Globals

(def default_config_filename ".dbtools.edn")
(def sql_file_extension ".sql")
(def csv_file_extension ".csv")
(def csv_filename_pattern #"[0-9_]*(.+).csv")
(def list_file_name "files.lst")

;;; Config

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


;;; Shell commands

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

(defn sql-file?
  [filename]
  (str/ends-with? filename sql_file_extension))

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
           "CREATE TABLE dbversion\n(\nversion integer NOT NULL,\ninstall_date timestamp with time zone NOT NULL,\ncomments character varying(256),\nCONSTRAINT dbversion_pkey PRIMARY KEY (version)\n)\nWITH (\nOIDS=FALSE\n);"]
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
  (utl/with-checked-errors
    (sql/db-do-commands (:db_conn system) (get-sql-commands fname))
    [system nil]))

(def get_tables_query
  ["SELECT tablename
    FROM pg_catalog.pg_tables
    WHERE schemaname='public'
    ORDER BY tablename;"])

(defn get-query
  [system query skey]
  (utl/with-checked-errors
    (sql/query (:db_conn system)
               query
               {:result-set-fn (fn [rs]
                                 [(assoc system skey (doall rs)) nil])})))

(defmacro with-test-db
  [& body]
  `(let [config# (set-config {:config (env/env :config-file)})
         ret# (utl/err->> config#
                          create-empty-db
                          (fn [cfg#]
                            (sql/with-db-transaction [dbconn# (make-db-spec cfg#)]
                              (utl/err->> (assoc cfg# :db_conn dbconn#)
                                          ~@body
                                          ))))]
     (delete-db config#)
     ret#))

(deftest exec-sql-commands-test
  (let [[ret err] (with-test-db
                    #(exec-sql-commands % "test/001_dbversion.sql")
                    #(get-query % get_tables_query :resultset))]
    (is (= [{:tablename "dbversion"}] (:resultset ret)) "resultset")
    (is (nil? err) "error value")))

;; Functions related to the static data files

(defn csv-file?
  [filename]
  (str/ends-with? filename csv_file_extension))

(defn get-tablename
  "Extract the tablename from the filename."
  [filename]
  (second (re-find csv_filename_pattern (.getName (io/file filename)))))

(deftest get-tablename-test
  (is (= "dbversion" (get-tablename "test/001_dbversion.csv"))
      "normal filename")
  (is (= "dbversion" (get-tablename "test/dbversion.csv"))
      "filename without prefix numbers")
  (is (nil? (get-tablename "test/dbversion.vvv"))
      "filename with wrong extension")
  )

(defn import-csv-file
  "Import static data into database from a csv file created with the 'copy to'
  postgresql command.
  params: - system, system map (used keys :db_conn, :clear_table)
          - fname, csv file name
  returns: [system errors]"
  [system filename]
  (let [{:keys [db_conn clear_table]} system
        fname (.getCanonicalPath (io/as-file filename))
        table (get-tablename fname)
        cmd (str "copy " table
                 " from '" fname
                 "' WITH DELIMITER ';' CSV HEADER ")]
    (log/infof "Import static data from file %s into table %s" fname table)
    (utl/with-checked-errors
      (if clear_table (sql/db-do-commands db_conn
                                          (str "truncate table " table)))
      (sql/db-do-commands db_conn cmd)
      [system nil])))


(def get_users_query
  ["SELECT *
    FROM users
    ORDER BY id;"])

(deftest import-csv-file-test
  (let [[ret err] (with-test-db
                    #(exec-sql-commands % "test/001_dbversion.sql")
                    #(exec-sql-commands % "test/002_users.sql")
                    #(import-csv-file % "test/002_users.csv")
                    #(get-query % get_tables_query :tables)
                    #(get-query % get_users_query :users))]
    (is (= [{:tablename "dbversion"} {:tablename "users"}] (:tables ret)) "tables")
    (is (= [{:id "test1",
             :password "abcd",
             :role "test",
             :surname "Test",
             :firstname "First"}
            {:id "test2",
             :password "efgh",
             :role "test",
             :surname "Test",
             :firstname "Second"}]
           (:users ret)) "users")
    (is (nil? err) "error value")))

;;; Actions

(defn- process-file
  [[system err] fname]
  (log/debugf "process-file: fname = %s, system = %s" fname system)
  (cond
    err (reduced [system err]) ; abort on error
    (sql-file? fname) (exec-sql-commands system fname)
    (csv-file? fname) (import-csv-file system fname)
    :else [system err] ; ignore non csv or sql files
    ))

(defn fill-db
  "Fill the database with useful things.
  params: - system, the system map (used keys :resource :db_conn)
  returns: [sytem err]  "
  [system]
  (let [{:keys [resource db_conn]} system
        listfile (io/file  resource list_file_name)]
    (if-not (.exists listfile)
      [system :missing_list_file]
      (with-open [r (io/reader listfile)]
        (reduce process-file [system nil] (map #(str resource %) (line-seq r)))))))

(deftest fill-db-test
  (let [[ret err] (with-test-db
                    #(fill-db %)
                    #(get-query % get_tables_query :tables)
                    #(get-query % get_users_query :users))]
    (is (= [{:tablename "dbversion"} {:tablename "users"}] (:tables ret)) "tables")
    (is (= [{:id "test1",
             :password "abcd",
             :role "test",
             :surname "Test",
             :firstname "First"}
            {:id "test2",
             :password "efgh",
             :role "test",
             :surname "Test",
             :firstname "Second"}]
           (:users ret)) "users")
    (is (nil? err) "error value")))

(defn create-db
  "Create a new database and to it intitial data.
  params: system, the system map
  returns: [system errors]"
  [system]
  (utl/err->> system
              create-empty-db
              (fn [cfg#]
                (sql/with-db-transaction [dbconn# (make-db-spec cfg#)]
                  (fill-db (assoc cfg# :db_conn dbconn#))))))
