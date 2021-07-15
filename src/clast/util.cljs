(ns clast.util
  (:require
    ["util" :as util]
    ["caller-id" :as caller-id]
    ["chokidar" :as file-watcher]
    ["rotating-file-stream" :as rfs]))

(defn bind-console-log-to-file []
  (let [logs (str js/__dirname "/logs")
        error-log (.createStream rfs "error.log" #js {:interval "7d" :path logs})
        stdout (aget js/process "stdout")
        log-fn (fn [& args]
                 (let [date (.toISOString (js/Date.))
                       [d t] (.split date "T")
                       [t _] (.split t ".")
                       out (str d " " t " " (apply util/format (clj->js args)) "\n")]
                   (.write error-log out)
                   (.write stdout out)))]
    (aset js/console "log" log-fn)
    (aset js/console "error" log-fn)))

(defn bail [msg]
  (js/console.error msg)
  (js/console.error "Server exit.")
  (js/process.exit 1))

(defn env [k & [default]]
  (or (aget js/process.env k) default))

(defn error-to-json [err]
  (let [e (js/JSON.parse (js/JSON.stringify err))]
    (aset e "message" (str err))
    #js {:error e}))

(defn btoa [s]
  (-> s js/Buffer. (.toString "base64")))

(defn reloader [reload-function]
  (let [caller (.getData caller-id)
        caller-path (aget caller "filePath")]
    (->
      (.watch file-watcher caller-path)
      (.on "change"
           (fn [path]
             (js/console.error (str "Reload triggered by " path))
             (js/setTimeout
               reload-function
               500))))))

(defn build-absolute-uri [req path]
  (let [hostname (aget req "hostname")
        host (aget req.headers "host")]
    (str req.protocol "://"
         (if (not= hostname "localhost") hostname host)
         (if (not= (aget path 0) "/") "/")
         path)))
