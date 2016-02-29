;;;; LCMAP Data core namespace
;;;;
;;;; This namespace defines the command line behaviors provided by this
;;;; library. It provides two functions invoked via Leiningen.
;;;;
;;;; 1. CQL Exection: used to load the schema
;;;; 2. Save metadata as tile specification.
;;;; 2. Save raster data as tiles.
(ns lcmap.data.cli
  (:require [clojurewerkz.cassaforte.client :as cc]
            [clojurewerkz.cassaforte.cql    :as cql]
            [clojure.pprint                 :as pprint]
            [clojure.tools.cli              :as cli]
            [clojure.tools.logging          :as log]
            [clojure.java.io                :as io]
            [com.stuartsierra.component     :as component]
            [dire.core                      :refer [with-handler!]]
            [twig.core                      :as twig]
            [lcmap.data.system              :as sys]
            [lcmap.data.ingest              :as ingest]
            [lcmap.data.util                :as util])
  (:gen-class))

(def option-specs
  [["-h" "--hosts HOST1,HOST2,HOST3" "List of hosts"
    :parse-fn #(clojure.string/split % #"[, ]")
    :default (clojure.string/split
      (or (System/getenv "LCMAP_HOSTS") "") #"[, ]")]
   ["-u" "--username USERNAME" "Cassandra user ID"
    :default (System/getenv "LCMAP_USER")]
   ["-p" "--password PASSWORD" "Cassandra password"
    :default (System/getenv "LCMAP_PASS")]
   ["-k" "--spec-keyspace SPEC_KEYSPACE" ""
    :default (System/getenv "LCMAP_SPEC_KEYSPACE")]
   ["-t" "--spec-table SPEC_TABLE" ""
    :default (System/getenv "LCMAP_SPEC_TABLE")]
   ["-c" "--cql PATH_TO_CQL" ""
    :default "resources/schema.cql"]
   ["-b" "--batch-size LCMAP_INGEST_BATCH_SIZE"
    "The size to partition jobs into for operations that parallelize
    tasks, such as tiling."
    :default 50
    :parse-fn #(Integer/parseInt %)]
   ["-m" "--checksum-ingest" "Perform checksum on ingested tiles?"]
   [nil "--checksum-outfile FILENAME" "Save the checksums to a particular file."
    :default (str (System/getProperty "java.io.tmpdir") "/ingest-hashes.txt")]
   ])

(defn execute-cql
  "Execute all statements in file specified by path"
  [system path]
  (let [conn (-> system :database :session)
        cql-file (slurp path)
        statements (map clojure.string/trim (clojure.string/split cql-file #";"))]
    (doseq [stmt (remove empty? statements)]
      (cc/execute conn stmt))))

(defn exec-cql
  "Executes CQL (useful for creating schema and seeding data)"
  [system opts]
  (log/info "Running command: 'exec'")
  (let [path (-> opts :options :cql)]
    (execute-cql system path)))

(defn make-tiles
  "Generate tiles from an ESPA archive"
  [system opts]
  (log/info "Running command: 'tile'")
  (let [paths (-> opts :arguments rest)]
    (doseq [path paths]
      (util/with-temp [dir path]
        (ingest/ingest dir system)))))

(defn make-specs
  "Generate specs from an ESPA archive"
  [system opts]
  (log/info "Running command: 'spec'")
  (let [paths (-> opts :arguments rest)]
    (doseq [path paths]
      (util/with-temp [dir path]
        (ingest/adopt dir system)))))

(defn info
  [system config-map]
  (log/info "Running command: 'info'")
  (pprint/pprint config-map))

(defn -main
  "Entry point for command line execution"
  [& args]
  (twig/set-level! ['lcmap.data] :info)
  (let [cli-args (cli/parse-opts args option-specs)
        db-opts  {:db {:hosts (get-in cli-args [:options :hosts])
                       :credentials (select-keys (cli-args :options) [:username :password])}}
        env-opts (util/get-config)
        combined (util/deep-merge env-opts db-opts {:opts (:options cli-args)})
        system   (component/start (sys/build combined))
        cmd      (-> cli-args :arguments first)]
    (cond (= cmd "exec") (exec-cql system cli-args)
          (= cmd "tile") (make-tiles system cli-args)
          (= cmd "spec") (make-specs system cli-args)
          (= cmd "info") (info system combined)
          :else (log/error "Invalid command:" cmd))
    (component/stop system)
    (System/exit 0)))

(with-handler! #'-main
  java.lang.Exception
  (fn [e & args]
    (log/error e)
    (System/exit 1)))