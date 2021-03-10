(ns rsstonews.server
  (:require
    [rsstonews.web :as web]
    [rsstonews.mail :as mail]
    ["keyv" :as Keyv]
    ["path" :as path]
    ["node-fetch" :as fetch]
    [cljs.core.async :refer (go <!) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]))

(set! *warn-on-infer* false)

(defonce app (web/create))
(defonce keyv (Keyv. (web/env "DATABASE" "sqlite://./rsstonews.sqlite")))

(defn authenticate [req res pass]
  (if (not (aget req "session" "authenticated"))
    (-> res (.status 403) (.json #js {:error "Not authenticated."}))
    (pass)))

(defn get-data [req res]
  (go (.json res (<p! (.get keyv "user-data")))))

(defn set-data [req res]
  (go (.json res (<p! (.set keyv "user-data" (aget req "body"))))))

(defn set-password [req res]
  (go
    (let [existing-password (<p! (.get keyv "password"))]
      (if (nil? existing-password)
        (.json res (<p! (.set keyv "password" (aget req "body"))))
        (-> res (.status 403) (.json #js {:error "Password already set"}))))))

(defn login [req res]
  (if (= (aget req.body "password") (web/env "PASSWORD" "password"))
    (do
      (aset req.session "authenticated" true)
      (.json res true))
    (-> res (.status 403) (.json #js {:error "Incorrect password"}))))

(defn logout [req res]
  (aset req.session "authenticated" false)
  (.json res true))

(defn cors-proxy [req res]
  (let [url (aget req.query "url")]
    (-> (fetch url)
        (.catch (fn [err]
                  (-> res (.status 404) (.json #js {:error "Proxy fetch failed."}))
                  nil))
        (.then (fn [r] (when r
                         (-> (.text r)
                             (.then (fn [text] #js [text r]))))))
        (.then (fn [[text f]]
                 (when f
                   (for [[k v] (.entries f.headers)]
                     (.header res k v))
                   (.status res f.status)
                   (.send res text)))))))

(defn append-unsubscribe-text [text unsubscribe-url]
  (str text "\n\n"
       "--" "\n"
       "Unsubscribe: " unsubscribe-url))

(defn append-unsubscribe-html [html unsubscribe-url]
  (str html "\n<br/>--\n<br/>\n\n"
       "<p><a href='" unsubscribe-url "'>Unsubscribe</a></p>"))

(defn send-emails [req res]
  (let [text (aget req.body "text")
        html (aget req.body "html")
        subject (aget req.body "subject")
        recipients (aget req.body "recipients")
        ; TODO: if this is unset bail at startup
        from (web/env "FROM_EMAIL" "chris@mccormick.cx")]
    (js/console.log "send-emails")
    (go
      (let [mailer (<p! (mail/create))
            send-promises (for [r recipients]
                            ; to from subject html text unsubscribe-url
                            (let [to (aget r "email")
                                  unsubscribe-url (aget r "unsubscribe")
                                  text (append-unsubscribe-text text unsubscribe-url)
                                  html (append-unsubscribe-html html unsubscribe-url)]
                              (js/console.log "Sending to:" to subject)
                              (-> (mail/send-mail mailer to from subject html text unsubscribe-url)
                                  (.catch (fn [err] err))
                                  (.then (fn [sent]
                                           (js/console.log "sent:" (aget sent "accepted"))
                                           (if (aget sent "error")
                                             (let [err (-> sent js/JSON.stringify js/JSON.parse)]
                                               (aset err "message" (aget sent "error" "message"))
                                               (aset err "email" to)
                                               (js/console.log "error:" err)
                                               err)
                                             sent))))))
            send-results (<p! (.all js/Promise (clj->js send-promises)))]
        (.json res send-results)))))

(defn setup-routes [app]
  (.post app "/login" login)
  (.get app "/logout" logout)
  (.post app "/set-password" set-password)
  (.use app authenticate)
  (.get app "/proxy" cors-proxy)
  (.get app "/data" get-data)
  (.post app "/save" set-data)
  (.post app "/send-emails" send-emails))

(defn reload! []
  (web/reset-routes app)
  (setup-routes app)
  (println "Fresh routes loaded: " (aget app "_router" "stack" "length")))

(defn main! []
  (go
    (let [[host port] (<p! (web/serve app))]
      (reload!)
      (println "Servers started."))))
