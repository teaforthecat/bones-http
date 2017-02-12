(ns bones.http.auth-test
  (:require [bones.http.auth :as auth]
            [ring.middleware.session :refer [wrap-session session-request session-response]]
            [ring.middleware.session.store :as store]
            [ring.util.codec :as codec]
            [clojure.test :refer [deftest testing is]]
            [buddy.core.nonce :as nonce]
            [yada.security :as yada]))

(def valid-secret (apply str (map char (range 32))))

(def conf {:bones.http/auth {:secret valid-secret
                       :cookie-name "pizza"}})

(def shield (.start (auth/map->Shield {:conf conf})))

;; made from `valid-secret' above
;; (.token shield {:abc "123"})
(def valid-token "eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1QiLCJlbmMiOiJBMTI4R0NNIn0.x6MngqAvPPrBnlr7jERY92QR30XGaXz0.2g-lzuaNPRaUqay7.Ll_Rsl6pOp6SKqEmgQ.EG0A18FZsqkbnAq3KCu8-g")

(deftest token
  (testing "valid token in header"
    (let [token (.token shield {:abc "123"})
          ctx {:request {:headers {"Authorization" (str "Token " valid-token)}}} ]
      (is (= {:abc "123"}
             (yada/verify ctx {:bones/shield shield
                               :scheme :bones/token})))))
  (testing "valid token in Cookie"
    (let [token (.token shield {:abc "123"})
          ctx {:request {:headers {"cookie" (str "pizza=" token)}}}]
      (is (= {:abc "123"}
             (yada/verify ctx {:bones/shield shield
                               :scheme :bones/cookie})))))
  (testing "invalid token in header"
    (let [ctx {:request {:headers {"Authorization" (str "Token " "something-else")}}} ]
      (is (= nil
             (yada/verify ctx {:bones/shield shield
                               :scheme :bones/token})))))
  (testing "invalid token in cookie"
    (let [ctx {:request {} :cookies {"pizza" "something-else"}}]
      (is (= nil
             (yada/verify ctx {:bones/shield shield
                               :scheme :bones/cookie}))))))

;; taken from ring.middleware.session.cookie
(defn- valid-secret-key? [key]
  (and (= (type (byte-array 0)) (type key))
       (= (count key) 16)))

(deftest validate-secret
  (testing "throws if count doesn't match algorithm"
    (let [secret "abcd"
          alg :a256kw]
      (is (thrown? AssertionError
                   (auth/validate-secret secret alg)))))
  (testing "a valid count passes"
    (let [secret (apply str (map char (range 32)))
          alg :a256kw]
      (is (nil? (auth/validate-secret secret alg))))
    (let [secret "a 16 byte string"
          alg :a128kw]
      (is (nil? (auth/validate-secret secret alg)))))
  (testing "gen-secret creates a valid secret"
    (let [secret (auth/gen-secret)]
      (is (= true (valid-secret-key? (.getBytes (auth/limited-secret secret 16))))))))

