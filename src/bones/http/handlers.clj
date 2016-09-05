(ns bones.http.handlers
  (:require [com.stuartsierra.component :as component]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [schema.experimental.abstract-map :as abstract-map]
            [schema.core :as s]
            [bones.http.auth :as auth]

            ;;ped

            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :refer [interceptor]]
            [io.pedestal.http.ring-middlewares :as middlewares]
            [io.pedestal.http.content-negotiation :as cn]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.sse :as sse]
            [io.pedestal.http.jetty.websockets :as ws]
            [io.pedestal.interceptor.helpers :as helpers]
            [io.pedestal.log :as log]
            [ring.util.response :as ring-resp]

            ;; aleph
            [compojure.core :as compojure :refer [GET POST]]
            [ring.middleware.params :as params]
            [compojure.route :as croute]
            [compojure.response :refer [Renderable]]
            [aleph.http :as http]
            [manifold.stream :as ms]
            [aleph.http :refer [websocket-connection]]
            [clojure.core.async :as a]
            [compojure.api.sweet :as api]
            ))

(s/defschema Command
  (abstract-map/abstract-map-schema
   :command
   {:args {s/Keyword s/Any}}))

;; todo register multiple query handlers
;; remove?
;; this gets redefined in `register-query-handler'
(s/defschema Query
  {:q s/Any
   (s/optional-key :type) s/Str
   s/Keyword s/Any})

;; a default is not needed due to the `check-command-exists' interceptor
(defmulti command :command)

(defn add-command
  "ensure a unique command is created based on a name spaced keyword"
  [command-name schema]
  (let [varname (-> command-name
                    str
                    (string/replace "/" "-")
                    (string/replace "." "-")
                    (subs 1)
                    (str "-schema")
                    (symbol))]
    (abstract-map/extend-schema! Command
                                 {:args schema}
                                 varname
                                 [command-name])))

(defn resolve-command [command-name]
  (let [nmspc (namespace command-name)
        f     (name command-name)]
    (if nmspc
      (ns-resolve (symbol nmspc) (symbol f))
      (resolve (symbol f)))))

(defn register-command
  "the command can have the same name of the function (implicit) - it must also
  have the same namespace as the call of this `register-command' function, or a third
  argument can be given that resolves to a function in another namespace, that
  way the command-name can be different from the function name if desired.

    * resolves a keyword to a function
    * adds a method to `command'
    * adds a schema to `Command'"
  ([command-name schema]
   (register-command command-name schema command-name))
  ([command-name schema explicit-handler]
   (let [command-handler (resolve-command explicit-handler)]
     (if (nil? command-handler)
       (throw (ex-info "could not resolve command to a function" {:command explicit-handler})))
     (add-command command-name schema)
     (defmethod command command-name [command req]
       (command-handler (:args command) req)))))

(defn register-commands [commands]
  (map (partial apply register-command) commands))

(defn register-query-handler [explicit-handler schema]
  (let [user-query-handler (resolve-command explicit-handler)]
    (if (nil? user-query-handler)
      (throw (ex-info "could not resolve query to a function" {:query explicit-handler})))
    ;; todo register multiple query handlers
    (s/defschema Query schema)
    (defn query [query-params req]
      (user-query-handler query-params req))))

;; remove?
(defn echo "built-in sanity check" [args req] args)
;; remove?
(register-command :echo {s/Any s/Any})

(defn registered-commands
  "a bare bones, low-level introspection utility"
  []
  (->> Command
       abstract-map/sub-schemas
       ;; login should probably be filtered out?
       (map (fn [sub]
              (:extended-schema (second sub))))))

(defn body [ctx]
  (-> ctx
      :request
      :body-params))

(defn get-req-command [ctx]
  (:command (body ctx)))

(defn command-handler [req]
  (command (:body-params req) req))

(defn command-list-handler [_]
  {:available-commands (registered-commands)})

(defn token-interceptor [shield]
  (interceptor {:name :bones/token-interceptor
                :leave (fn [ctx]
                         ;; assuming authentic user data in response
                         ;; returned from registered :login command
                         ;; the session ends up in a Set-Cookie header
                         ;; the token ends up in the response body
                         (let [user-data (:response ctx)]
                           (assoc ctx :response {:session {:identity user-data}
                                                 :token (auth/token shield user-data)})))}))

(defn login-handler [req]
    (if (= :login (get-in req [:body-params :command]))
      (let [user-data (command (:body-params req) req)]
        (if user-data
          user-data
          (throw (ex-info "Invalid Credentials" {:status 400}))))
      (throw (ex-info "Invalid Command" {:status 400}))))

;; todo register multiple query handlers somehow
;; remove?
;; this gets overwritten by `register-query-handler'
(defn query [params req]
  (let [q (:q params)]
    {:results (edn/read-string q)}))

(defn query-handler [req]
  (query (:query-params req) req))

(defn identity-interceptor [shield]
  ;; sets request :identity to data of decrypted "Token" in the "Authorization" header
  ;; sets request :identity to data of :identity in session cookie
  (helpers/on-request :bones.auth/identity
                      (auth/identity-interceptor shield)))



(defn session [shield]
  ;; interceptor
  ;; sets request :session to decrypted data in the session cookie
  ;; sets cookie to encrypted value of data in request :session
  (middlewares/session (:cookie-opts shield)))

(def check-command-exists
  (interceptor {:name :bones/check-command-exists
                :enter (fn [ctx]
                         (let [commands (-> Command abstract-map/sub-schemas keys)
                               cmd (get-req-command ctx)]
                           (if (some #{cmd} commands)
                             ctx ;;do nothing
                             (throw (ex-info "command not found"
                                             {:status 400
                                              :message (str "command not found: " cmd)
                                              :available-commands commands})))))}))

(def check-args-spec
  (interceptor {:name :bones/check-args-spec
                :enter (fn [ctx]
                         (if-let [error (s/check Command (body ctx))]
                           (throw (ex-info "args not valid" (assoc error :status 400)))
                           ctx))}))

(def check-query-params-spec
  (interceptor {:name :bones/check-query-params-spec
                :enter (fn [ctx]
                         (if-let [error (s/check Query (get-in ctx [:request :query-params] {}))]
                           (throw (ex-info "query params not valid" (assoc error :status 400)))
                           ctx))}))

(defn respond-with [response content-type]
  (let [session (:session response)]
    (-> (dissoc response :session) ;;body
        ring-resp/response
        (assoc :session session) ;; new session info for Set-Cookie header
        (ring-resp/content-type content-type))))

(defmulti render
  "render content-types, extensible, relies on io.pedestal.http.content-negotiation.
  dispatch-val will be a valid content-type string
  recommended to call (respond-with rendered dispatch-val)"
  #(get-in % [:request :accept :field]))

(defmethod render :default ;; both? "application/edn" configurable?
  [ctx]
  (update-in ctx [:response] #(respond-with % "application/edn")))

;; (defmethod render "text/event-stream"
;;   [ctx]
;;   (if (ms/source? (:response ctx))
;;     (let [stream-ready-fn #((ms/connect (:response ctx)
;;                                         (:response-channel ctx)))]
;;       (sse/start-stream stream-ready-fn context heartbeat-delay bufferfn-or-n opts))
;;     (throw (ex-info "source not given for text/event-stream" {:status 500}))))

(def renderer
  (interceptor {:name :bones/renderer
                :leave (fn [ctx] (render ctx))}))

(def body-params
  "shim for the body-params interceptor, stuff the result into a single
  key on the request `body-params' for ease of use"
  (interceptor {:name :bones/body-params
                :enter (fn [ctx]
                         (update ctx
                                 :request (fn [req]
                                            (assoc req
                                                   :body-params (or (:edn-params req)
                                                                    (:json-params req)
                                                                    (:transit-params req)
                                                                    (:form-params req))))))}))

(def error-responder
  ;; get status of the nested(thrown) exception's ex-data for specialized http responses
  (interceptor {:name :bones/error-responder
                :error (fn [ctx ex]
                         (let [exception (-> ex ex-data :exception) ;; nested exception
                               data (ex-data exception)
                               status (or (:status data) 500)
                               resp {:message (.getMessage exception)}
                               response (if (empty? (dissoc data :status))
                                          resp
                                          (assoc resp :data (dissoc data :status)))]
                           (-> (assoc ctx :response response) ;; sets body
                               (render) ;; sets content-type
                               (update :response assoc :status status))))}))

(defn get-resource-interceptors []
  ^:interceptors
  ['bones.http.handlers/error-responder ;; must come first
   (cn/negotiate-content ["application/edn"])
   'bones.http.handlers/renderer])

(defn command-resource
  "# post request handler
    * uses `Command' schema for validation
    * requires exclusively `:command' and `:args' keys in post body
    * hands off `:args' of post body to `:command'
    * commands are registered with `register-command'"
  [conf shield]
  [:bones/command
   ;; need to use namespace so this can be called from a macro (defroutes)
   ^:interceptors ['bones.http.handlers/error-responder              ; request ; must come first
                   (cn/negotiate-content ["application/edn"])        ; request
                   (bones.http.handlers/session shield)              ; auth
                   (bones.http.handlers/identity-interceptor shield) ; auth
                   'bones.http.auth/check-authenticated              ; auth
                   (body-params/body-params)                         ; post
                   'bones.http.handlers/body-params                  ; post
                   'bones.http.handlers/check-command-exists         ; command
                   'bones.http.handlers/check-args-spec              ; command
                   'bones.http.handlers/renderer                     ; response
                   ]
   'bones.http.handlers/command-handler])

(defn command-list-resource [conf shield]
  ;; need to use namespace so this can be called from a macro (defroutes)
  [:bones/command-list (get-resource-interceptors) 'bones.http.handlers/command-list-handler])

(defn login-resource [conf shield]
  ;; need to use namespace so this can be called from a macro (defroutes)
  [:bones/login
   ^:interceptors ['bones.http.handlers/error-responder           ; request ; must come first
                   (cn/negotiate-content ["application/edn"])     ; request
                   (bones.http.handlers/session shield)           ; auth
                   (body-params/body-params)                      ; post
                   'bones.http.handlers/body-params               ; post
                   'bones.http.handlers/check-command-exists      ; login   ; :login should be registered
                   'bones.http.handlers/check-args-spec           ; login   ; user defined login parameters
                   'bones.http.handlers/renderer                  ; response
                   (bones.http.handlers/token-interceptor shield) ; response ; before response
                   ]
   'bones.http.handlers/login-handler])

(defn query-resource [conf shield]
  ;; need to use namespace so this can be called from a macro (defroutes)
  [:bones/query
   ^:interceptors ['bones.http.handlers/error-responder              ; request ; must come first
                   (cn/negotiate-content ["application/edn"])        ; request
                   (bones.http.handlers/session shield)              ; auth
                   (bones.http.handlers/identity-interceptor shield) ; auth
                   'bones.http.auth/check-authenticated              ; auth
                   'bones.http.handlers/check-query-params-spec      ; query
                   'bones.http.handlers/renderer                     ; response
                   ]
   'bones.http.handlers/query-handler])

(defn event-stream-resource [conf shield event-stream-handler]
  ;; need to use namespace so this can be called from a macro (defroutes)
  [:bones/event-stream
   ^:interceptors ['bones.http.handlers/error-responder              ; request ; must come first
                   (cn/negotiate-content ["text/event-stream"])      ; request
                   (bones.http.handlers/session shield)              ; auth
                   (bones.http.handlers/identity-interceptor shield) ; auth
                   'bones.http.auth/check-authenticated              ; auth
                   ]
   (sse/start-event-stream event-stream-handler)])

(def cors-headers
  {"Access-Control-Allow-Origin" "http://localhost:3449"
   "Access-Control-Allow-Headers" "Content-Type,AUTHORIZATION"
   "Access-Control-Allow-Methods" "GET,POST,OPTIONS"
   "Access-Control-Allow-Credentials" "true"})

(defn cors-ok []
  (interceptor {:name :bones/cors-ok
                :enter (fn [ctx]
                         (assoc ctx :response
                                {:status 200
                                 :body ""
                                 :headers cors-headers}))}))

(defn ws-paths [mount-path conf]
  (let [ws-handler (get-in conf [:http/handlers :ws-handler])
        ws-onclose (get-in conf [:http/handlers :ws-onclose])
        ws-path (str mount-path "/ws")]
    {ws-path {:on-connect (ws/start-ws-connection ws-handler)
              ;; not supported
              :on-text (fn [msg]
                         (log/info :msg (str "A client sent - " msg)))
              ;; not supported
              :on-binary (fn [payload offset length]
                           (log/info :msg "Binary Messag!" :bytes payload))
              :on-error (fn [t]
                          (log/error :msg "WS Error happened" :exception t))
              :on-close (fn [num-code reason-text]
                          (log/info :msg "WS Closed:" :reason reason-text)
                          (ws-onclose num-code reason-text))}}))

(defrecord CQRS [conf shield]
  component/Lifecycle
  (start [cmp]
    (let [config (get-in cmp [:conf :http/handlers])
          auth-sheild (:shield cmp)
          mount-path (or (:mount-path config) "/api")]
      (-> cmp
          (assoc :ws-paths (ws-paths mount-path (:conf cmp)))
          (assoc :routes
                 (route/expand-routes
                  [[[mount-path
                     ["/command"
                      {:post (command-resource config auth-sheild)
                       :get (command-list-resource config auth-sheild)}]
                     ["/query"
                      {:get (query-resource config auth-sheild)}]
                     (if-let [event-stream-handler (:event-stream-handler config)]
                       ["/events"
                        {:get (event-stream-resource config
                                                     auth-sheild
                                                     event-stream-handler)}])
                     ["/login"
                      {:post (login-resource config auth-sheild)}]
                     ;; causes not-found:
                     ;; ["/*any" {:options [:bones/cors (cors-ok)]}]
                     ]]]))))))

(extend-protocol Renderable
  manifold.deferred.Deferred
  (render [d _] d))

(comment

  (def commands [[:who {:first-name s/Str}]
                 [:what {:weight-kg s/Int}]
                 [:where {:room-no s/Int}]])

  (defn event-stream-handler [{:keys [params]}]
    (let [cnt (Integer/parseInt (get params "count" "0"))
          body (a/chan)]
      (a/go-loop [i 0]
        (if (< i cnt)
          (let [_ (a/<! (a/timeout 100))]
            (a/>! body (str i "\n"))
            (recur (inc i)))
          (a/close! body)))
      {:status 200
       :headers {"Content-Type" "text/event-stream"}
       :body (ms/->source body)}))


  (defn ws-handler [{:keys [params] :as req}]
    (let [cnt (Integer/parseInt (get params "count" "0"))
          body @(http/websocket-connection req)]
      (a/go-loop [i 0]
        (if (< i cnt)
          (let [_ (a/<! (a/timeout 100))]
            (a/>! body (str i "\n"))
            (recur (inc i)))
          (a/close! body)))
      ;;no return value
      ))

  (defn query-handler [req]
    {:status 200
     :headers {"content-type" "text"}
     :body "hello"})

  (def handlers
    {
     :login login-handler
     :commands commands
     :query query-handler
     :event-stream event-stream-handler
     :websocket ws-handler
     })

  (defn list-commands [cmds]
    (fn [req]
      {:status 200
       :headers {"Content-Type" "text"}
       :body (pr-str cmds)}))

  (defn handler [handlerz]
    (let [{:keys [:login
                  :commands
                  :query
                  :event-stream
                  :websocket]} handlerz]
      (api/api
       (api/context "/api" []
         :tags ["api"]
         (GET "/commands"  [] (list-commands commands))
         (GET "/query"     [] query)
         (POST "/login"    [] login)
         (GET "/events"    [] event-stream)
         (GET "/ws"        [] websocket)
         (croute/not-found "No such page.")))))

  (def s (http/start-server (handler handlers) {:port 3000}))

  (.close s)

  )
