(ns bones.http
  (:require [bones.http.db :as db]
            [bones.http.kafka :as kafka]
            [bones.http.system :as system]
            [bones.conf :as conf]
            [bidi.ring :refer (make-handler)]
            [yada.yada :refer [resource yada]]
            [yada.swagger :refer [swaggered]]
            [yada.security :refer [verify]]
            [schema.core :as s :refer [defschema]]
            [clj-time.core :as time]
            [buddy.core.nonce :as nonce]
            [buddy.auth.backends.token :refer [jwe-backend]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.sign.jwe :as jwe]
            [buddy.sign.jws :as jws])
  (:import [clojure.lang ExceptionInfo]))

;; constants
(defonce token-name "X-Auth-Token")
(defonce secret (nonce/random-bytes 32))
(defonce algorithm {:alg :a256kw :enc :a128gcm})


(defn resolve-fn [sym]
  (let [space (symbol (namespace sym))
        fn (symbol (name sym))]
    (ns-resolve space fn)))


(defn create-token [data]
  (jwe/encrypt data secret algorithm))

(defn make-login-handler
  "Accepts a function to use to both check password and retreive user data.
   If that function returns falsey a 401 will be returned.
   This handler is designed to handle both browser sessions and api-token provisioning."
  [authenticate-user-fn]
  (fn login-handler [ctx]
    (let [{:keys [username password]} (get-in ctx [:parameters :form])]
      (if-let [user-data (authenticate-user-fn username password)]
        (let [claims {:user user-data
                      ;; todo: consider other options for expiration
                      :exp (time/plus (time/now) (time/hours 1))}
              token (create-token claims)
              session (jws/sign user-data secret)]
          (-> (:response ctx)
              (assoc :cookies {"session" {:value session}})
              (assoc :body {:token token})))
        (-> (:response ctx)
            (assoc :status 401)
            (assoc :body "Invalid username or password"))))))

(defn login-resource [auth-user-fn]
  (let [resolved-fn (resolve-fn auth-user-fn)]
    (resource
     {:methods {:post {:consumes ["application/x-www-form-urlencoded" "application/edn"]
                       :produces ["application/edn" "application/json"]
                       :parameters {:form
                                    {:username s/Str :password s/Str}}
                       :response (make-login-handler resolved-fn)}}})))

(defn verify-token [request]
  (let [token (get-in request [:headers token-name])]
    (if token
      (try
        (if-let [user-data (jwe/decrypt token secret algorithm)]
          user-data)
        (catch ExceptionInfo e
          (println (ex-data e))
          (if-not (= (ex-data e)
                     {:type :validation :cause :signature})
            (throw e)
            ))))))

(defn command-handler [ctx]
  (let [user-data (verify-token (:request ctx))
        command (get-in ctx [:parameters :body])]
    (if user-data
      ;; (kafka/produce command)
      "hello"
      (-> (:response ctx)
          (assoc :status 403)
          (assoc :body "Valid Auth-Token required")))))

(defschema Command
  {:command_type (s/enum :a :b :c)
   :message s/Any })

(defn command-resource []
  (resource {:methods {:post {:consumes ["application/edn"]
                              :produces ["application/edn" "application/json"]
                              :parameters {:body Command}
                              :response command-handler}}}))

;; todo make/find datalog schema
(defschema Query {:q [s/Any]})

(defn query-handler []
  {:results ["hello"]})

(defn query-resource []
  (resource {:methods {:get {:parameters {:query Query}
                             :response query-handler}}}))

(defn events-resource []
  (resource {:methods {:get {:response "hello"}}}))

(defn cqrs [{:keys [:bones.http/mount-point :bones.http/auth-fn]}]
  [(or mount-point "/api/")
   (swaggered
    ;; (if auth-fn ["login" (yada (login-resource auth-fn))])
    ["command" (yada (command-resource))]
    ;; ["query" (yada (query-resource))]
    ;; ["events" (yada (events-resource))]
    {:info {:title "Hello World!"
            :version "1.0"
            :description "A greetings service"}
     :basePath "/api/"})])

(defn start-server [conf]
  (let [handler (-> {:bones.http/auth-fn :bones.http.db/authenticate-user}
                    (cqrs)
                    (make-handler))
        sys (system/system {:config-files ["test.edn"]
                            :http/handler handler
                            :http/port 3000})]
    (system/start-system sys :conf :http)
    sys))

(comment

  (def system (start-server {}))

  (system/stop-system system :conf :http)

  (let [handler (-> {:bones.http/auth-fn :bones.http.db/authenticate-user}
                    (cqrs)
                    (make-handler)
                    )]
    (system/system {:config-files ["test.edn"]
                               :http/handler handler})
    )

  )
