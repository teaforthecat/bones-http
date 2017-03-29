(ns bones.http.handlers
  (:require [bones.http.commands :as commands]
            [ring.middleware.params :as params]
            [clojure.walk :refer [keywordize-keys]]
            [aleph.http :as ahttp]
            [manifold.stream :as ms]
            [manifold.deferred :as d]
            ;; [clojure.core.async :as a]
            [yada.yada :as yada]
            [bidi.ring :refer [make-handler]]
            [clj-time.core :as t]
            [clj-time.coerce :refer (to-date)]
            [schema.core :as schema]
            [clojure.spec :as s]
            [clojure.edn :as edn]
            [byte-streams :as bs]
            [taoensso.timbre :as timbre :refer [log log-and-rethrow-errors]]
            [com.stuartsierra.component :as component]))

(defn allow-cors
  "access-control attributes for resources"
  [shield]
  {:allow-origin (:cors-allow-origin shield)
   :allow-credentials true
   :allow-methods #{:get :post}
   :allow-headers ["Content-Type" "Authorization"]})

(defn require-login
  "access-control attributes for resources"
  [shield]
  {:authentication-schemes [{:scheme :bones/token
                             :bones/shield shield}
                            {:scheme :bones/cookie
                             :bones/shield shield}]
   ;; authorization is what returns a 401
   :authorization {:scheme :bones/authorize}})

(defn logger
  "logs exceptions, ignoring special status(422) exceptions"
  [ctx]
  (if-let [error (:error ctx)]
    (if-let [status (:status (ex-data error))]
      nil
      (log :error (:error ctx)))))

(defn handler
  "common handler definition for all resources"
  [resource]
  (-> resource
      (merge {:logger logger})
      (yada/resource)
      (yada/handler)))

(defn parse-schema-error
  "an attempt to make data specification errors friendlier"
  [ctx]
  (->> ctx :error ex-data :errors first (apply hash-map)))

(defn schema-response
  "note: clojure.spec errors are also rendered, this is used for the
  coercion that yada offers from prismatic schema"
  [param-type ctx]
  ; param-type is where the schema validation error ends up: query,body,form
  (let [schema-error (param-type (parse-schema-error ctx))
        param-error (-> ctx :error ex-data :error)]
    (cond
      schema-error
      (get schema-error :error "schema error not found")
      param-error
      (pr-str param-error)
      :else
      "there was an error parsing the request")))

(defn bad-request-response
  "response functions for known request problems"
  [param-type]
  {400 {
        :produces "application/edn"
        :response (partial schema-response param-type)}
   401 {:produces "application/edn"
        :response "not authorized"}
   422 {:produces  "application/edn"
        :response (fn [ctx] (-> ctx :error ex-data :message))}
   ;; exceptions will be logged
   500 {:produces "text/plain"
        :response "Server Error"}})

(defmethod yada.authorization/validate
  :bones/authorize
  ;; bare minimum to get a 401 response
  [ctx credentials authorization]
  (if credentials
    ctx
    (d/error-deferred
     (ex-info "authorization required"
              {:status 401}))))

(defn command-response [ctx]
  (let [{:keys [parameters authentication request]} ctx
        _body (:body parameters)
        ;; yada won't parse body if the Content-Length header is not provided
        ;; we could require it, but we can also fall back to parsing it ourselves
        body (or _body (edn/read-string (bs/to-string (:body (:request ctx)))))]
    (if-let [errors (commands/check body)]
      (assoc (:response ctx) :status 400 :body errors)
      (commands/command body authentication request))))

(defn command-handler
  "does two things, register commands and return yada handler"
  [commands shield]
  (commands/register-commands commands)
  (handler {:id :bones/command
            :access-control (merge
                             (require-login shield)
                             (allow-cors shield))
            :methods {:post
                      {:response command-response
                       ;; just to get the coersion, duplicated in commands
                       :parameters {:body {:command schema/Keyword
                                           :args {schema/Any schema/Any}}}
                       :consumes "application/edn"
                       :produces "application/edn"}}
            :responses (bad-request-response :body)}) )

(defn command-list-handler [commands]
  (handler {:id :bones/command-list
            :properties {:last-modified (to-date (t/now))}
            :methods {:get
                      ;; only show the command name and args schema
                      ;; todo: expand on this or replace with swagger
                      ;; maybe use a `recursive-describe' function
                      {:response (map #(take 2 %) commands)
                       :produces "application/edn"}}}))

(defn query-response [query-fn query-spec]
  (fn [{:keys [parameters authentication request] :as ctx}]
    (log-and-rethrow-errors
     (let [query ((fnil keywordize-keys {}) (:query parameters))]
       (if (s/valid? query-spec query)
         (query-fn (s/conform query-spec query) authentication request)
         (assoc (:response ctx) :status 400 :body (s/explain-data query-spec query)))))))

(defn query-handler [query-spec query-fn shield]
  (handler {:id :bones/query
            :access-control (merge
                             (require-login shield)
                             (allow-cors shield))
            :methods {:get
                      {:response (query-response query-fn query-spec)
                       :consumes "application/edn"
                       :produces "application/edn"}}
            :responses (bad-request-response :query)}))

(defn encrypt-response [shield ctx result]
  (let [cookie-name (:cookie-name shield)
        data result
        ;; to share some data, but not all (e.g.: roles or groups for permissions)
        share-keys (:share (meta data))
        share-data (if share-keys (select-keys data share-keys))
        ;; todo: enforce login-fn to return a map
        token (.token shield (if (map? data) data {:data data}))]
    (-> ctx
        (assoc-in [:response :cookies] {cookie-name token})
        (assoc-in [:response :body] {"token" token
                                     "share" share-data}))))

(defn unset-cookie [ctx shield]
  (let [cookie-name (:cookie-name shield)]
    (assoc-in ctx [:response :cookies] {cookie-name {:value "nil"
                                                     :expires (to-date (t/now))}})))

(defn handle-error [ctx]
  "Server Error")

(defn login-response [login-fn login-spec shield]
  (fn [{:keys [parameters request] :as ctx}]
    (log-and-rethrow-errors
     (let [body (:body parameters)]
       (if-let [result (login-fn body request)]
         (:response (encrypt-response shield ctx result))
         (assoc (:response ctx) :status 401 :body "invalid credentials"))))))

(defn login-handler [login-spec login-fn shield]
  (handler {:id :bones/login
            :access-control (allow-cors shield)
            :responses {500 {:produces "text/plain"
                             :response handle-error}}
            :methods {:post
                      {:response (login-response login-fn login-spec shield)
                       :parameters {:body {schema/Any schema/Any}}
                       :consumes "application/edn"
                       :produces "application/edn"}}}))

(defn logout-response [logout-fn shield]
  (fn [ctx]
    (log-and-rethrow-errors
     (let [body (logout-fn (:request ctx))]
       (-> ctx
           (assoc-in [:response :body] body)
           (unset-cookie shield)
           :response)))))

(defn logout-handler [logout-fn shield]
  (handler
   {:id :bones/logout
    :access-control (allow-cors shield)
    :responses {500 {:produces "text/plain"
                     ;; TODO: don't do this in production
                     :response handle-error}}
    ;; strange :* results in a 406
    :methods (reduce #(assoc %1 %2
                                 {:response (logout-response logout-fn shield)
                                  :consumes "application/edn"
                                  :produces "application/edn"})
                         {}
                         [:get :post :put :delete])}))

(defn format-event [{:keys [event data id] :as datum :or {data datum}}]
  ;; allows map of sse keys or just anything as data to be str'd
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

(defn ws-connect [req source]
  (let [ws @(ahttp/websocket-connection req)]
    ;; this is a shim to be able to use the same stream for both SSE and
    ;; websockets, maybe support two different streams
    ;; string is necessary, not sure why
    (ms/connect (ms/map (fn [e] (pr-str (or (:data e) e)))
                        source)
                ws)))

(defn handle-ws [event-fn]
  (fn [ctx]
    (let [auth-info (:authentication ctx)
          req (:request ctx)
          source (event-fn req auth-info)]
      (ws-connect req source))))

(defn ws-handler [event-fn shield]
  (handler {:id :bones/ws
            :access-control (merge (require-login shield)
                                   (allow-cors shield))
            :methods {:get
                      {:produces "application/edn" ;? I think something needs to go here?
                       :response (handle-ws event-fn)}}}))

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
  ;; todo what is spec for callable
  (let [{:keys [login
                logout
                commands
                query
                event-stream
                mount-path]
         :or {mount-path "/api"
              logout (fn [req] "")}} req-handlers]
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
      (if event-stream
        ["/ws"
         (ws-handler event-stream shield)
         :bones/ws])
      [true
       (not-found-handler)
       :bones/not-found]]]))

(defrecord App [conf shield]
  component/Lifecycle
  (start [cmp]
    (let [handlers (get-in cmp [:conf :bones.http/handlers])
          auth-shield (:shield cmp)]
      (assoc cmp :routes (routes handlers auth-shield))))
  (stop [cmp]
    (assoc cmp :routes nil)))

(defn app [handlers shield]
  (make-handler (routes handlers shield)))
