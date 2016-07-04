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
(def migrations_file_name "migrations.lst")
(def migrations_line_pattern #"([0-9_]+)\s+(.+)")


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
          (get-sql-commands "test/01_dbversion.sql" ))))

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
  "Adds to system map a query result.
  params: system, the sytem map (used key :db_conn)
          query, a sql query (string)
          skey, the key for the result
  returns: [updated_system err] where updated_system = system + {skey result}."
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
                    #(exec-sql-commands % "test/01_dbversion.sql")
                    #(get-query % get_tables_query :resultset))]
    (is (= [{:tablename "dbversion"}] (:resultset ret)) "resultset")
    (is (nil? err) "error value")))

;;; Functions related to the static data files

(defn csv-file?
  [filename]
  (str/ends-with? filename csv_file_extension))

(defn get-tablename
  "Extract the tablename from the filename."
  [filename]
  (second (re-find csv_filename_pattern (.getName (io/file filename)))))

(deftest get-tablename-test
  (is (= "dbversion" (get-tablename "test/01_dbversion.csv"))
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
                    #(exec-sql-commands % "test/01_dbversion.sql")
                    #(exec-sql-commands % "test/02_users.sql")
                    #(import-csv-file % "test/02_users.csv")
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




;;; Functions related to populate the database

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
        (reduce process-file [system nil] (map #(io/file resource %) (line-seq r)))))))

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

;;; Functions related to versioning and migrations

(def get_version_query
  ["SELECT *
    FROM dbversion
    ORDER BY version DESC LIMIT 1;"])

(defn get-version
  "Get database version.
  params: system, system map (used keys :db_conn )
  returns: [updated_system err], the updated_system is sytem with a :version key append to it"
  [system]
  (get-query system get_version_query :version))

(deftest get-version-test
  (let [[ret err] (with-test-db
                    #(fill-db %)
                    get-version)]
    (is (= [{:version 1,
             :install_date #inst "2016-06-27T09:12:30.000000000-00:00",
             :comments "initial version"}]
           (:version ret)) "version")
    (is (nil? err) "error value")))

(defn set-migrations-map
  "Set migrations map.
  params: system, system map (used keys :resource)
  returns: [updated_system err] where updated_system = system + :migrations"
  [system]
  (let [resource (:resource system)
        migfile (io/file resource migrations_file_name)]
    (if-not (.exists migfile)
      [system :missing_migrations_file]
      (with-open [r (io/reader migfile)]
        [(assoc system :migrations (reduce (fn [migs item]
                                             (let [[_ v f] (re-find migrations_line_pattern item)]
                                               (assoc migs (Integer/valueOf v) (.getCanonicalPath (io/file resource f)))))
                                           {}
                                           (line-seq r)))
         nil]))))

(deftest set-migrations-map-test
  (let [config (set-config {:config (env/env :config-file)})
        [ret err] (set-migrations-map config)]
      (is (nil? err) "error")
      (is (= [20 30 40] (keys (:migrations ret))) "migrations")))


(defn get-active-migrations
  "Returns the migrations versions to execute as sorted set."
  [system]
  (let [{:keys [version migrations db_version]} system
        ver (get (first version) :version 1)]
    (into (sorted-set) (filter (fn [x]
                                 (and (> x ver)
                                      (or (nil? db_version) (<= x db_version))))
                               (keys migrations)))))

(deftest get-active-migrations-test
  (is (= #{2 3} (get-active-migrations {:version [{:version 1}] :db_version 3 :migrations {2 "mig2" 3 "mig3" 4 "mig4" 5 "mig5"}} )) "standard")
  (is (= #{} (get-active-migrations {:version [{:version 6}] :db_version 3 :migrations {2 "mig2" 3 "mig3" 4 "mig4" 5 "mig5"}} )) "no migrations")
  (is (= #{2 3 4 5} (get-active-migrations {:version [{:version 1}] :migrations {2 "mig2" 3 "mig3" 4 "mig4" 5 "mig5"}} )) "missing db_version")
      )



(defn migrate
  "Migrate the database.
  params: system, system map (used keys :version :migrations)
  returns: [system err]"
  [system]
  (utl/with-checked-errors
    (let [{:keys [migrations]} system
          migrseq (get-active-migrations system)]
      (log/debugf "migrate: migrseq = %s" migrseq)
      (reduce (fn [[sys err] ver]
                (if err
                  (reduced [sys err])
                  (let [fname (get migrations ver)]
                    (log/infof "Migrate the database to %d" ver)
                    (fill-db (assoc sys :resource fname)))))
              [system nil]
              migrseq))))

(deftest migrate-test
  (let [[ret err] (with-test-db
                    fill-db
                    set-migrations-map
                    get-version
                    migrate 
                    ;; #(do (log/debug "migrate ok") [% nil])
                    get-version
                    ;; #(do (log/debug "get-version ok") [% nil])
                    #(get-query % get_tables_query :tables)
                    )]
    (is (= [{:tablename "dbversion"}
             {:tablename "test2"}
             {:tablename "test3"}
            {:tablename "users"}]
           (:tables ret))
        "tables")
    (is (= 40 (:version (first (:version ret)))) "version") 
    (is (nil? err) "error value")))



;;; Actions

;; (defmacro with-db
;;   [system & body]
;;   `(let [ret# (utl/err->> ~system
;;                           (fn [cfg#]
;;                             (sql/with-db-transaction [dbconn# (make-db-spec cfg#)]
;;                                (utl/err->> (assoc cfg# :db_conn dbconn#)
;;                                           ~@body
;;                                           ))))]
;;      ret#))

(defmacro with-db
  [system & body]
  `(let [sys# ~system
         db# (make-db-spec sys#)] 
     (sql/with-db-transaction [dbconn# db#]
       (let [cfg# (assoc sys# :db_conn dbconn#)
             ret# (utl/err->> cfg# 
                   ~@body)]
        (when (second ret#) (sql/db-set-rollback-only! db#))
     ret#))))


(defn create-db
  "Create a new database and to it intitial data.
  params: system, the system map
  returns: [system errors]"
  [system]
  (utl/err->> system
              create-empty-db
              #(with-db %
                 fill-db
                 get-version)))

(defn get-db-version
  "Get database version.
  params: system, the system map
  returns: [system errors]"
  [system]
  (utl/with-checked-errors
  (with-db system
    get-version)))


(defn update-db
  "Update the database.
  params: system, the system map
  returns: [system errors]"
  [system]
  (utl/with-checked-errors
    (with-db system
      fill-db 
      get-version)))


(defn migrate-db
  "Migrate the database.
  params: system, the system map
  returns: [system errors]"
  [system]
  (utl/with-checked-errors
    (with-db system
      set-migrations-map
      get-version
      migrate 
      get-version)))
