(ns bones.http.yada
  (:require [bones.http.handlers :refer [Command register-commands command]]
            [bones.http.auth :refer [identity-interceptor]]
            [ring.middleware.params :as params]
            [compojure.route :as croute]
            [compojure.response :refer [Renderable]]
            [aleph.http :as http]
            [manifold.stream :as ms]
            [manifold.deferred :as d]
            [aleph.http :refer [websocket-connection]]
            [clojure.core.async :as a]
            [compojure.api.sweet :as api]
            [yada.yada :as yada]
            [bidi.ring :refer [make-handler]]
            [clj-time.core :as t]
            [clj-time.coerce :refer (to-date)]
            [schema.core :as s]
            [schema.experimental.abstract-map :as abstract-map]
            [clojure.edn :as edn]
            [byte-streams :as bs]
            [com.stuartsierra.component :as component]))

(def content-types
  #{"application/edn"
    "application/json"
    "application/xml"
    "application/html"
    "text/plain"})

(defn allow-cors [shield]
  {:allow-origin (get shield :allow-origin "http://localhost:3449")
   :allow-credentials true
   :allow-methods #{:get :post}
   :allow-headers ["Content-Type" "Authorization"]})

(defn authenticate [auth-fn]
  (fn [ctx]
    (let [token (get-in ctx [:cookies "bones-session"])
          request (update-in (:request ctx)
                             [:headers "authorization"]
                             #(or % (str "Token " token)))]
      (:identity (auth-fn request)))))

(defn require-login [shield]
  (let [auth-fn (identity-interceptor shield)]
    {:authentication-schemes [{:verify (authenticate auth-fn)}
                              ]
     :authorization {:scheme :bones/authorize}}))

(defn list-commands [cmds]
  (fn [req]
    (pr-str cmds)))

(defn handler [resource]
  (yada/handler
   (yada/resource resource)))

(defn parse-schema-error [ctx]
  (->> ctx :error ex-data :errors first (apply hash-map)))

(defn schema-response [param-type ctx]
  ; param-type is where the schema validation error ends up: query,body,form
  (if-let [schema-error (param-type (parse-schema-error ctx))]
    (get schema-error :error "schema error not found")
    "there was an error parsing the request"))

(defn bad-request-response [param-type]
  {400 {
        :produces "application/edn"
        :response (partial schema-response param-type)}
   401 {:produces "application/edn"
        :response "not authorized"}})

(defmethod yada.authorization/validate
  :bones/authorize
  ;; bare minimum to get a 401 response
  [ctx credentials authorization]
  (if credentials
    ctx
    (d/error-deferred
     (ex-info "No authorization provided"
              {:status 401}))))

(defn command-params-handler [ctx]
  (let [command-body (-> (get-in ctx [:request :body])
                         (bs/to-string)
                         (edn/read-string))]
    (if-let [errors (s/check Command command-body)]
      (assoc (:response ctx) :status 400 :body errors)
      ;;maybe add the request too?
      (command command-body (:authorization ctx)))))

(defn command-handler [commands shield]
  ;; hack to ensure registration - abstract-map is experimental
  ;; if the print is removed, for some reason(!), the commands don't get registered
  (pr-str (register-commands commands))
  (handler {:id :bones/command
            :access-control (merge
                             (require-login shield)
                             (allow-cors shield))
            :methods {:post
                      {:response command-params-handler
                       :consumes "application/edn"
                       :produces "application/edn"}}
            :responses (bad-request-response :body)}) )

(defn command-list-handler [commands]
  (handler {:id :bones/command-list
            :properties {:last-modified (to-date (t/now))}
            :methods {:get
                      {:response commands
                       :produces "application/edn"}}}))

(defn query-handler [q-fn q-schema shield]
  (handler {:id :bones/query
            :parameters {:query q-schema}
            :access-control (merge
                             (require-login shield)
                             (allow-cors shield))
            :methods {:get
                      {:response q-fn
                       :consumes "application/edn"
                       :produces "application/edn"}}
            :responses (bad-request-response :query)}))

(defn encrypt-response [shield ctx]
  (if (= :options (:method ctx));;cors pre-flight
    ctx
    (let [cookie-name (:cookie-name shield)
          data (get-in ctx [:response :body])
          ;; todo: enforce login-fn to return a map
          token (.token shield (if (map? data) data {:data data}))]
      (-> ctx
          (assoc-in [:response :cookies] {cookie-name token})
          (assoc-in [:response :body] {"token" token})))))

(defn handle-error [ctx]
  (pr-str (:error ctx)))

(defn login-handler [login-fn shield]
  (-> {:id :bones/login
       :access-control (allow-cors shield)
       :responses {500 {:produces "text/plain"
                        :response handle-error}}
       :methods {:post
                 {:response login-fn
                  :consumes "application/edn"
                  :produces "application/edn"}}}
      (yada/resource)
      (yada.resource/insert-interceptor
       yada.interceptors/create-response
       (partial encrypt-response shield))
      (yada/handler)))

(defn format-event [{:keys [event data id] :as datum :or {data datum}}]
  ;; allows map of sse keys or just anything as data to be str'd
  ;; todo: do non integer id's break the client?
  ;; note: a map with :event or :id keys, without :data, may not be what you want
  ;; note: in this order, it doesn't matter if the data is already a string that
  ;;   ends with a newline because _at least_ one blank line separates events
  (cond-> ""
    event (str "event: " event "\n")
    id    (str "id: " id "\n")
    true  (str "data: " data "\n\n")))

(defn event-stream-handler [event-fn shield]
  (handler {:id :bones/event-stream
            :access-control (merge (require-login shield)
                                   (allow-cors shield))
            :methods {:get
                      {:produces "text/event-stream"
                       ;; maybe don't create the stream here
                       :response (fn [ctx]
                                   (let [auth-info (:authentication ctx)
                                         req (:request ctx)
                                         source (event-fn req auth-info)]
                                     (ms/transform (map format-event)
                                                   source)))}}}))

(defn not-found-handler []
  (handler {:id :bones/not-found
            :methods {:* nil}
            :properties {:exists? false}
            :responses {404 {
                             :produces content-types
                             :response "not found"}}}))

(defn routes [req-handlers shield]
  (let [{:keys [:login
                :commands
                :query
                :query-schema
                :event-stream
                :websocket
                :mount-path]
         :or {:query-schema []
              :mount-path "/api"
              :commands []}} req-handlers]
    [mount-path
     [
      ["/command"
       (command-handler commands shield)
       :bones/command]
      ["/commands" ;; this should not be necessary if swagger
       (command-list-handler commands)
       :bones/commands]
      ["/query"
       (query-handler query query-schema shield)
       :bones/query]
      ["/login"
       (login-handler login shield)
       :bones/login]
      ["/events"
       (event-stream-handler event-stream shield)
       :bones/event-stream]
      [true
       (not-found-handler)
       :bones/not-found]]]))

(defrecord App [conf shield]
  component/Lifecycle
  (start [cmp]
    (let [handlers (get-in cmp [:conf :http/handlers])
          auth-shield (:shield cmp)]
      (assoc cmp :routes (routes handlers auth-shield)))))

(defn app [handlers shield]
  (make-handler (routes handlers shield)))
