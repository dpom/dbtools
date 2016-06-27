(ns dbtools.core
  (:require
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [dbtools.utils :as utl]
   [dbtools.dbtools :as dbu]
   [clojure.tools.logging :as log]
   [clojure.test :refer :all]
   )
  (:gen-class))


(defn print-msg
  "Print informal messages on the console and in log.
    params: options - options map, used key :quiet
            msg - the message to display (string)
     "
  [options msg]
  (log/info msg)
  (if-not (:quiet options)
    (println msg)))


(def cli-options
  [["-a" "--db_archive" "Create database backup file as a custom PostgreSQL archive; optional, used only by the 'backup' command"]
   ["-c" "--config FILE" "Configuration file" :default ".dbtools.cfg"]
   ["-d" "--db_name NAME" "Database name"]
   ["-f" "--db_file FILE" "Database absolute file name - for the 'backup'/'restore' commands"]
   ["-h" "--help"]
   ["-p" "--db_password PASS" "Database user password"]
   ["-r" "--resource PATH" "Resources root  path"]
   ["-s" "--db_schema" "Create database backup file only for schema, not data; optional, used only by the 'backup' command"]
   ["-t" "--table NAME" "The table(s) name(s) (comma separated if there are many tables) to export into csv file; mandatory, used only by the 'export' command."]
   ["-u" "--db_user USER" "Database user"]
   ["-k" "--clear_table" "Clear table before import"]
   ["-v" "--db_version NAME" "Database version string using this format 'DDD.dd', where 'DDD' is the major version number and 'dd' is the minor version number; optional, used only by the 'migrate' command"]
   ])

(defn usage
  "Display the help text.
    params: options_summary - cli generated options help"
  [options_summary]
  (str/join
   \newline
   ["This is the dbtools program."
    ""
    "Usage: dbtools [options] action"
    ""
    "Options:"
    options_summary
    ""
    "Actions:"
    "create    Create a database"
    "migrate   Migrate database to a specific version"
    "version   Print database version"
    "update    Update database with migration files from a specific directory"
    "delete    Delete a database"
    "backup    Backup a database to a provided file"
    "restore   Restore a database from a provided file"
    "export    Export table(s) records into csv file(s)"
    "import    Import table(s) records from csv file(s)"
    ""
    "Please refer to the user's guide for more information."]))

(defn error-msg
  "Display the errors text.
    params: errors - cli generated errors string collection"
  [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn exit
  "Exit application after displaing a message.
    params: status - exit code
            msg - message to display"
  [status msg]
  (println msg)
  (System/exit status))


(defn action
  "Handler for action.
    params: options - options map
            actionfn - the function which run the action
            okmsg - the message to display if the action finishes normally"
  [options actionfn okmsg]
  (let [[ret err] (actionfn options)]
    (if err
      (println (str  "exception: " err))
      (let [{:keys [version install_date comments]} (first (:version ret))]
        (print-msg options (format "db version: %d (%s) installed %s" version comments install_date))
        (print-msg options okmsg)))))

(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    ;; Handle help and error conditions
    (cond
      (:help options) (exit 0 (usage summary))
      (not= (count arguments) 1) (exit 1 (usage summary))
      errors (exit 1 (error-msg errors)))
    ;; Execute program with options
    (let [act (partial action (dbu/set-config options))]
      (case (first arguments)
        "delete" (act dbu/delete-db "Database deleted.")
        "create" (act dbu/create-db "Database created.")
        "version" (act dbu/get-db-version "")
        ;; "migrate" (act migrate "Database migrated.")
        (exit 1 (usage summary))))))
