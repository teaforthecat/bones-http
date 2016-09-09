(ns bones.http.yada
  (require [ring.middleware.params :as params]
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
           [clj-time.coerce :refer (to-date)]))

(def content-types
  #{"application/edn"
    "application/json"
    "application/xml"
    "application/html"
    "text/plain"})

(defn list-commands [cmds]
  (fn [req]
    ;; {:status 200
    ;;  :headers {"Content-Type" "text"}
    ;;  :body}
    (pr-str cmds)))

(defn handler [resource]
  (yada/handler
   (yada/resource resource)))


(defn command-list-handler [commands]
  (handler {:id :bones/command-list
            :properties {:last-modified (to-date (t/now))}
            :methods {:get
                      {:response commands
                       :produces "application/edn"
                       }}}))

(defn parse-schema-error [ctx]
  (->> ctx :error ex-data :errors first (apply hash-map)))

(defn schema-response [param-type ctx]
  ; param-type is where the schema validation error ends up
  (if-let [schema-error (param-type (parse-schema-error ctx))]
    (get schema-error :error "schema error not found")
    "there was an error parsing the request"))

(defn bad-request-response [param-type]
  {400 {
        :produces "application/edn"
        :response (partial schema-response param-type)}})

(defn query-handler [q-fn q-schema]
  (handler {:id :bones/query
            :parameters {:query q-schema}
            :methods {:get
                      {:response q-fn
                       :consumes "application/edn"
                       :produces "application/edn"}}
            :responses (bad-request-response :query)}))

(defn login-handler [login-fn]
  (handler {:id :bones/login
            :methods {:post
                      {:response login-fn
                       :consumes "application/edn"
                       :produces "application/edn"}}}))

(defn not-found-handler []
  (handler {:id :bones/not-found
            :methods {:* nil}
            :properties {:exists? false}
            :responses {404 {
                             :produces content-types
                             :response "not found"}}}))

(defn app [req-handlers]
  (let [{:keys [:login
                :commands
                :query
                :query-schema
                :event-stream
                :websocket]
         :or {query-schema []}} req-handlers]
    (make-handler ["/api"
                   [
                    ["/commands"
                     (command-list-handler commands)
                     :bones/commands]
                    ["/query"
                     (query-handler query query-schema)
                     :bones/query]
                    ["/login"
                     (login-handler login)
                     :bones/login]
                    [true
                     (not-found-handler)
                     :bones/not-found]]])))
