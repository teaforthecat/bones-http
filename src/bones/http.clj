(ns bones.http
  (:require [bones.http.db :as db]
            [yada.yada :refer [resource]]
            [schema.core :as s :refer [defschema]]
            [buddy.core.nonce :as nonce]
            [clj-time.core :as time]
            [buddy.sign.jwe :as jwe]
            [buddy.sign.jws :as jws]))

;; constants
(defonce secret (nonce/random-bytes 32))
(defonce algorithm {:alg :a256kw :enc :a128gcm})

(defn resolve-fn [sym]
  (let [space (symbol (namespace sym))
        fn (symbol (name sym))]
    (ns-resolve space fn)))

;; to be resolved by library user (bones.db.datomic/authenticate-user)
;; (def authenticate-user (fn [username password] nil))

;; (defn resolve-authenticate-user-fn [sym]
;;   (alter-var-root #'authenticate-user (fn [f] (resolve-fn sym))))


(defn make-login-handler
  "Accepts a function to use to both check password and retreive user data.
   If that function returns falsey a 401 will be returned.
   This handler is designed to handle both browser sessions and api-token provisioning."
  [authenticate-user]
  (fn login-handler [ctx]
    (let [{:keys [username password]} (get-in ctx [:parameters :form])]
      (if-let [user-data (#'authenticate-user username password)]
        (let [claims {:user user-data
                      ;; todo: consider other options for expiration
                      :exp (time/plus (time/now) (time/hours 1))}
              token (jwe/encrypt claims secret algorithm)
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

(defn command-handler [] "hello")

(defschema Command
  {:command_type (s/enum :a :b :c)})

(defn command-resource []
  (resource {:methods {:post {:parameters {:body Command}
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

(defn cqrs [{:keys :bones.http/mount-point :bones.http/auth-fn}]
  [(or mount-point "/api")
   (if auth-fn ["login" (login-resource auth-fn)])
   ["command" (command-resource)]
   ["query" (query-resource)]
   ["events" (events-resource)]])
