(ns bones.http.auth
  (:require [buddy.sign.jwt :as jwt]
            [buddy.core.nonce :as nonce]
            [buddy.auth :refer [authenticated? throw-unauthorized]]
            [buddy.auth.backends.token :refer [jwe-backend]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [buddy.hashers :as hashers]
            [buddy.auth.protocols :as proto]
            [yada.security :as yada]
            [yada.cookies :as cookies]
            [clj-time.core :as time]
            [byte-streams :as bs]
            [ring.middleware.session.cookie :refer [cookie-store]]
            [ring.middleware.session :refer [wrap-session session-request session-response]]
            [ring.middleware.session.store :as store]
            [com.stuartsierra.component :as component]))

(defn encrypt-password [password]
  (hashers/encrypt password {:alg :bcrypt+blake2b-512}))

(defn check-password [password-candidate encrypted-password]
  (hashers/check password-candidate encrypted-password))

(def check-authenticated
  {:name :bones.auth/check-authenticated
   :enter (fn [ctx]
            ;; authenticated just checks for the presence of :identity
            (if (authenticated? (:request ctx))
              ctx
              (throw (ex-info "Not Authenticated" {:status 401}))))})

;; thanks! apiga http://stackoverflow.com/a/37356673/714357
(defn gen-secret
  "printable secret, so it can be put into a text file"
  ([] (gen-secret 32))
  ([n]
   (let [chars-between #(map char (range (int %1) (inc (int %2))))
         chars (concat (chars-between \0 \9)
                       (chars-between \a \z)
                       (chars-between \A \Z)
                       [\_])
         secret (take n (repeatedly #(rand-nth chars)))]
     (reduce str secret))))

(defn limited-secret [secret n]
  (->> secret
       (take n)
       (map char)
       (apply str)))

(defn validate-secret [secret algorithm]
  ;; taken from buddy.sign.jwe.cek in order to raise the alarms early
  ;; other algorithms don't have this constraint
  (let [n (get {:a128kw 16
                :a192kw 24
                :a256kw 32} algorithm)]
    (if n
      (assert (= n (count secret))
              (str "buddy.sign.jwe.cek says the secret key must "
                   (format  "be exactly %s bytes(characters); " n)
                   (format "%s is %d \n" (pr-str secret) (count secret))
                   "use this newly generated one: \n" (gen-secret) "\n"))
      (assert (> 16 (count secret))
              (str "secret to short; at least 16 bytes(characters) "
                   "needed to satisfy ring.middleware.session.cookie; "
                   (format "%s is %d" (pr-str secret) (count secret)))))))

(defmethod yada/verify :bones/cookie
  [ctx {:keys [:bones/shield] :as scheme}]
  (let [{:keys [token-backend cookie-name]} shield
        token (-> ctx :request cookies/parse-cookies (get cookie-name))
        request (:request ctx)]
    ;; get the same token that is in the Authorization header from the cookie
    (if (not-empty token)
      (proto/-authenticate token-backend {} token))))

(defmethod yada/verify :bones/token
  [ctx {:keys [:bones/shield] :as scheme}]
  (let [{:keys [token-backend]} shield
        request (:request ctx)]
    (if-let [token (proto/-parse token-backend request)]
      (proto/-authenticate token-backend {} token))))

(defprotocol Token
  (token [this data]))

(defrecord Shield [conf]
  Token
  (token [cmp data]
    (let [exp? (:token-exp-ever cmp)
          hours (:token-exp-hours cmp)
          secret (:secret cmp)
          algorithm (:algorithm cmp)
          exp (if exp?
                {:exp (time/plus (time/now) (time/hours hours))}
                {})
          claims (merge data exp)]
      (jwt/encrypt claims secret algorithm)))
  component/Lifecycle
  (stop [cmp] cmp)
  (start [cmp]
    (let [config (get-in cmp [:conf :bones.http/auth])
          {:keys [secret
                  algorithm
                  cookie-name
                  cookie-secret
                  cookie-https-only
                  cookie-max-age
                  token-exp-hours
                  token-exp-ever?
                  cors-allow-origin]
           :or {secret (gen-secret 32)
                algorithm {:alg :a256kw :enc :a128gcm}
                cookie-name "bones-session"
                cookie-secret (limited-secret secret 16)
                cookie-https-only false
                cookie-max-age (* 60 60 24 365) ;; one year
                token-exp-hours (* 24 365) ;; one year
                token-exp-ever? false
                cors-allow-origin "http://localhost:3449" ;; for dev
                }} config]
      (validate-secret secret (:alg algorithm))
      (-> cmp
          (assoc :secret secret)
          (assoc :token-backend (jwe-backend {:secret secret :options algorithm}))
          (assoc :token-exp-ever? token-exp-ever?)
          (assoc :token-exp-hours token-exp-hours)
          (assoc :algorithm algorithm)
          (assoc :cookie-backend (session-backend))
          (assoc :cookie-name cookie-name)
          (assoc :cors-allow-origin cors-allow-origin)
          ;; ring.middleware.session.cookie says the secret key must be exactly 16 bytes(characters)
          (assoc :cookie-opts {:store (cookie-store {:key cookie-secret})
                               :cookie-name cookie-name
                               :cookie-attrs {:http-only false
                                              :secure cookie-https-only
                                              :max-age cookie-max-age}})))))

(defmethod clojure.core/print-method Shield
  [system ^java.io.Writer writer]
  (.write writer "#<bones.http.auth/Shield>"))
