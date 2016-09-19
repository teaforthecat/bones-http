(ns bones.http.handlers
  (:require [bones.http.commands :as commands]
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

(defn allow-cors [shield]
  {:allow-origin (:cors-allow-origin shield)
   :allow-credentials true
   :allow-methods #{:get :post}
   :allow-headers ["Content-Type" "Authorization"]})

(defn authenticate [auth-fn cookie-name]
  (fn [ctx]
    (let [token (get-in ctx [:cookies cookie-name])
          ;; hack to use the token as the cookie
          request (update-in (:request ctx)
                             [:headers "authorization"]
                             #(or % (str "Token " token)))]
      (:identity (auth-fn request)))))

(defn require-login [shield]
  (let [auth-fn (identity-interceptor shield)
        cookie-name (:cookie-name shield)]
    {:authentication-schemes [{:verify (authenticate auth-fn cookie-name)}
                              ]
     :authorization {:scheme :bones/authorize}}))

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
     (ex-info "authorization required"
              {:status 401}))))

(defn handle-command [ctx]
  (let [{:keys [parameters authentication request]} ctx
        body (:body parameters)]
    (if-let [errors (s/check commands/Command body)]
      (assoc (:response ctx) :status 400 :body errors)
      (commands/command body authentication request))))

(defn command-handler [commands shield]
  ;; hack to ensure registration - abstract-map is experimental
  ;; if the print is removed, for some reason(!), the commands don't get registered
  (pr-str (commands/register-commands commands))
  (handler {:id :bones/command
            :access-control (merge
                             (require-login shield)
                             (allow-cors shield))
            :methods {:post
                      {:response handle-command
                       :parameters {:body {:command s/Keyword :args s/Any}}
                       :consumes "application/edn"
                       :produces "application/edn"}}
            :responses (bad-request-response :body)}) )

(defn command-list-handler [commands]
  (handler {:id :bones/command-list
            :properties {:last-modified (to-date (t/now))}
            :methods {:get
                      ;; only show the command name and args schema
                      ;; todo: expand on this or replace with swagger
                      {:response (map #(take 2 %) commands)
                       :produces "application/edn"}}}))

(defn query-handler [query-schema query-fn shield]
  (handler {:id :bones/query
            :parameters {:query query-schema}
            :access-control (merge
                             (require-login shield)
                             (allow-cors shield))
            :methods {:get
                      {:response (fn [{:keys [parameters authentication request]}]
                                   (query-fn parameters authentication request))
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

(defn unset-cookie [shield ctx]
  (let [cookie-name (:cookie-name shield)]
    (assoc-in ctx [:response :cookies] {cookie-name ""})))

(defn handle-error [ctx]
  ;; bare minimum to show it
  (pr-str (:error ctx)))

(defn login-handler [login-schema login-fn shield]
  (-> {:id :bones/login
       :access-control (allow-cors shield)
       :responses {500 {:produces "text/plain"
                        ;; todo: don't do this in production
                        :response handle-error}}
       :methods {:post
                 {:response (fn [{:keys [parameters request]}]
                              (login-fn parameters request))
                  :consumes "application/edn"
                  :produces "application/edn"}}}
      (yada/resource)
      (yada.resource/insert-interceptor
       yada.interceptors/create-response
       (partial encrypt-response shield))
      (yada/handler)))

(defn logout-handler [logout-fn shield]
  (-> {:id :bones/login
       :access-control (allow-cors shield)
       :responses {500 {:produces "text/plain"
                        ;; todo: don't do this in production
                        :response handle-error}}
       :methods {:get ; only get or post will be allowed
                 {:response (fn [ctx] (logout-fn (:request ctx)))
                  :consumes "application/edn"
                  :produces "application/edn"}}}
      (yada/resource)
      (yada.resource/insert-interceptor
       yada.interceptors/create-response
       (partial unset-cookie shield))
      (yada/handler)))

(defn format-event [{:keys [event data id] :as datum :or {data datum}}]
  ;; allows map of sse keys or just anything as data to be str'd
  ;; todo: do non-integer id's break the client?
  ;; note: a map with :event or :id keys, without :data, may not be what you want
  ;; note: in this order, it doesn't matter if the data is already a string that
  ;;   ends with a newline because _at least_ one blank line separates events
  (cond-> ""
    event (str "event: " event "\n")
    id    (str "id: " id "\n")
    true  (str "data: " data "\n\n")))

(defn handle-event-stream [event-fn]
  (fn [ctx]
    (let [auth-info (:authentication ctx)
          req (:request ctx)
          source (event-fn req auth-info)]
      (ms/transform (map format-event)
                    source))))

(defn event-stream-handler [event-fn shield]
  (handler {:id :bones/event-stream
            :access-control (merge (require-login shield)
                                   (allow-cors shield))
            :methods {:get
                      {:produces "text/event-stream"
                       :response (handle-event-stream event-fn)}}}))

(defn not-found-handler []
  (handler {:id :bones/not-found
            :methods {:* nil}
            :properties {:exists? false}
            :responses {404 {
                             :produces #{"application/edn"
                                         "application/json"
                                         "text/plain"}
                             :response "not found"}}}))

(defn routes [req-handlers shield]
  ;; if query is nil/not given, 404 will be the response to ./query
  ;; todo what is schema for callable
  (let [{:keys [:login
                :logout
                :commands
                :query
                :event-stream
                :mount-path]
         :or {:mount-path "/api"
              :logout (fn [req] "")}} req-handlers]
    [mount-path
     [
      (if commands
        ["/command"
         (command-handler commands shield)
         :bones/command])
      (if commands
        ["/commands"
         (command-list-handler commands)
         :bones/commands])
      (if query
        ["/query"
         (apply query-handler (conj query shield))
         :bones/query])
      (if login
        ["/login"
         (apply login-handler (conj login shield))
         :bones/login])
      (if login
        ["/logout"
         (logout-handler logout shield)
         :bones/logout])
      (if event-stream
        ["/events"
         (event-stream-handler event-stream shield)
         :bones/event-stream])
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
