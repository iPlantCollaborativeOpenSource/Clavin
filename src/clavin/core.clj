(ns clavin.core
  (:gen-class)
  (:use [clojure.java.io :only [file]])
  (:require [clojure.tools.cli :as cli]
            [clavin.environments :as env]
            [clavin.generator :as gen]
            [clavin.loader :as loader]
            [clavin.zk :as zk]
            [clojure-commons.file-utils :as ft]
            [clojure.string :as string]
            [clojure-commons.props :as ccprops]))

(declare main-help)

(defn- to-integer
  [v]
  (Integer. v))

(defn parse-args
  [args]
  (cli/cli 
   args 
   ["-h" "--help" "Show help." :default false :flag true]
   ["-f" "--envs-file" "The file containing the environment definitions."
    :default nil]
   ["-t" "--template-dir" "The directory containing the templates."
    :default nil]
   ["--host" "The Zookeeper host to connection to." :default nil]
   ["--port" "The Zookeeper client port to connection to." :default 2181
    :parse-fn to-integer]
   ["--acl"  "The file containing Zookeeper hostname ACLs." :default nil]
   ["-a" "--app" "The application the settings are for." :default "de"]
   ["-e" "--env" "The environment that the options should be entered into."
    :default nil]
   ["-d" "--deployment"
    "The deployment inside the environment that is being configured."
    :default nil]))

(def ^:private required-args
  [:envs-file :template-dir :host :acl :deployment])

(defn parse-files-args
  [args]
  (cli/cli
   args
   ["-h" "--help" "Show help." :default false :flag true]
   ["-f" "--envs-file" "The file containing the environment definitions."
    :default nil]
   ["-t" "--template-dir" "The directory containing the templates."
    :default nil]
   ["-a" "--app" "The application the settings are for." :default "de"]
   ["-e" "--env" "The environment that the options are for." :default nil]
   ["-d" "--deployment" "The deployment that the properties files are for."
    :default nil]
   ["--dest" "The destination directory for the files." :default nil]))

(def ^:private required-files-args
  [:envs-file :template-dir :deployment :dest])

(defn parse-hosts-args
  [args]
  (cli/cli 
    args
    ["-h" "--help" "Show help." :default false :flag true]
    ["--acl"  "The file containing Zookeeper hostname ACLs." :default nil]
    ["--host" "The Zookeeper host to connection to." :default nil]
    ["--port" "The Zookeeper client port to connection to." :default 2181
     :parse-fn to-integer]))

(defn parse-envs-args
  [args]
  (cli/cli
   args
   ["-h" "--help" "Show help." :default false :flag true]
   ["-l" "--list" "List environments." :default false :flag true]
   ["-v" "--validate" "Validate the environments file" :default false
    :flag true]
   ["-f" "--envs-file" "The file containing the environment definitions"
    :default nil]))

(def ^:private required-envs-args
  [[:list :validate] :envs-file])

(defn keyword->opt-name
  [k]
  (str "--" (name k)))

(defn get-directory
  [opts help-str opt-k]
  (let [v (opts opt-k)]
    (when-not (or (nil? v) (ft/dir? v))
      (println (keyword->opt-name opt-k) "must refer to a directory.")
      (println help-str)
      (System/exit 1))
    v))

(defn get-regular-file
  [opts help-str opt-k]
  (let [v (opts opt-k)]
    (when-not (or (nil? v) (ft/file? v))
      (println (keyword->opt-name opt-k) "must refer to a regular file.")
      (println help-str)
      (System/exit 1))
    v))

(defn validate-single-opt
  [opts help-str opt-k]
  (when-not (opts opt-k)
    (println (keyword->opt-name opt-k) "is required.")
    (println help-str)
    (System/exit 1)))

(defn validate-multiple-opts
  [opts help-str opt-ks]
  (let [defined-opts (filter opts opt-ks)
        opt-names    (string/join ", " (map keyword->opt-name opt-ks))]
    (when-not (< 0 (count defined-opts) 2)
      (println "please specify exactly one of" opt-names)
      (println help-str)
      (System/exit 1))))

(defn validate-opts
  [opts help-str required-opts]
  (when (:help opts)
    (println help-str)
    (System/exit 0))
  (dorun (map #(cond
                (keyword? %)    (validate-single-opt opts help-str %)
                (= (count %) 1) (validate-single-opt opts help-str (first %))
                :else           (validate-multiple-opts opts help-str %))
              required-opts)))

(defn handle-hosts
  [args-vec]
  (let [[opts args help-str] (parse-hosts-args args-vec)]
    (when (:help opts)
      (println help-str)
      (System/exit 0))
    
    (cond
      (not (:acl opts))
      (do (println "--acl is required.")
        (println help-str)
        (System/exit 1))
      
      (not (:host opts))
      (do (println "--host is required.")
        (println help-str)
        (System/exit 1))
      
      (not (ft/exists? (:acl opts)))
      (do (println "--acl must reference an existing file.")
        (println help-str)
        (System/exit 1)))
    
    (println (str "Connecting to Zookeeper instance at " (:host opts) ":" (:port opts)))
    (zk/init (:host opts) (:port opts))
    
    (let [acl-props (ccprops/read-properties (:acl opts))]
      (when-not (loader/can-run? acl-props)
        (println "This machine isn't listed as an admin machine in " (:acl opts))
        (System/exit 1))
      
      (println "Starting to load hosts.")
      (loader/load-hosts acl-props)
      (println "Done loading hosts.")
      (System/exit 0))))

(defn handle-files
  [args-vec]
  (let [[opts args help-str] (parse-files-args args-vec)]
    (validate-opts opts help-str required-files-args)
    (let [envs-file    (get-regular-file opts help-str :envs-file)
          template-dir (get-directory opts help-str :template-dir)
          envs         (env/load-envs envs-file)
          templates    (if (empty? args) (gen/list-templates template-dir) args)
          dep          (:deployment opts)
          env-name     (or (:env opts) (env/env-for-dep envs dep))
          app          (:app opts)
          env          (get-in envs (map keyword [env-name dep]))
          env-path     (str app "." env-name "." dep)
          dest         (:dest opts)]

      (when (nil? env)
        (println "no environment defined for" env-path)
        (System/exit 1))

      (when-not (ft/dir? dest)
        (.mkdirs (file dest)))

      (gen/generate-all-files env template-dir templates dest))))

(defn handle-properties
  [args-vec]
  (let [[opts args help-str] (parse-args args-vec)]
    (validate-opts opts help-str required-args)
    (let [envs-file    (get-regular-file opts help-str :envs-file)
          template-dir (get-directory opts help-str :template-dir)
          envs         (env/load-envs envs-file)
          templates    (if (empty? args) (gen/list-templates template-dir) args)
          acl-file     (get-regular-file opts help-str :acl)
          acl-props    (ccprops/read-properties acl-file)
          dep          (:deployment opts)
          env-name     (or (:env opts) (env/env-for-dep envs dep))
          app          (:app opts)
          env          (get-in envs (map keyword [env-name dep]))
          host         (:host opts)
          port         (:port opts)
          env-path     (str app "." env-name "." dep)]

      (when (nil? env)
        (println "no environment defined for" env-path)
        (System/exit 1))

      (when-not (loader/can-run? acl-props)
        (println "This machine isn't listed as an admin machine in " acl-file)
        (System/exit 1))

      (println "Connecting to Zookeeper instance at" (str host ":" port))
      (zk/init host port)

      (println "Starting to load data into the" env-path "environment...")
      (let [acls (loader/load-acls app env-name dep acl-props)]
        (loader/load-settings app env-name dep template-dir templates acls env))
      (println "Done loading data into the" env-path "environment."))))

(defn handle-environments
  [args-vec]
  (let [[opts args help-str] (parse-envs-args args-vec)]
    (validate-opts opts help-str required-envs-args)
    (let [envs-file (get-regular-file opts help-str :envs-file)]
      (cond (:list opts)     (env/list-envs envs-file)
            (:validate opts) (env/validate-envs envs-file)))))

(def ^:private subcommand-fns
  {"help"  (fn [args] (main-help args) (System/exit 0))
   "files" handle-files
   "props" handle-properties
   "hosts" handle-hosts
   "envs"  handle-environments})

(defn main-help
  [args]
  (let [known-cmds (string/join "|" (sort (keys subcommand-fns)))]
    (println "clavin" known-cmds "[options]")
    (println "Each command has its own --help.")))

(defn -main
  [& args-vec]
  (let [cmd      (first args-vec)
        args-vec (vec (drop 1 args-vec))]
    (if (contains? subcommand-fns cmd)
      ((subcommand-fns cmd) args-vec)
      (do
        (println "Something weird happened.")
        (main-help args-vec)
        (System/exit 1)))))
