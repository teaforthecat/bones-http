(ns bones.http.auth-test
  (:require [bones.http.auth :as auth]
            [ring.middleware.session :refer [wrap-session session-request session-response]]
            [ring.middleware.session.store :as store]
            [ring.util.codec :as codec]
            [clojure.test :refer [deftest testing is]]
            [buddy.core.nonce :as nonce]))

(def conf {:http/auth {:secret (nonce/random-bytes 32)
                       :cookie-name "pizza"}})

(def valid-secret (apply str (map char (range 32))))

(defn session [shield data]
  (let [{:keys [cookie-opts cookie-secret]} shield]
    (store/write-session (:store cookie-opts) cookie-secret data)))

(defn read-session [shield sess]
  (let [{:keys [cookie-opts]} shield]
    (store/read-session (:store cookie-opts) sess)))

(def session-info {:identity {:xyz 123}})

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
  (testing "takes 16 bytes from secret for cookie-secret"
    (let [secret (auth/gen-secret)]
      (is (= true (valid-secret-key? (.getBytes (auth/limited-secret secret 16))))))))

(deftest request-session
  (let [shield (.start (auth/map->Shield conf))]
    (testing "given an empty request and response, sets identity to nil"
      (let [req ((auth/identity-interceptor shield) {} )]
        (is (= {:identity nil} req))))
    (testing "session is readable and writable"
      (is (= (read-session shield (session shield session-info)) session-info)))))


(deftest test-shield
  (testing "workable defaults"
    (let [shield (.start (auth/map->Shield conf))]
      (testing "extacts data from token to identity"
        ;; all token data ends up in :identity
        (let [valid-request {:headers {"authorization" (str "Token " (.token shield {:xyz 123}))}}
              req ((auth/identity-interceptor shield) valid-request)]
          (is (= 123 (get-in req [:identity :xyz])))))
      (testing "extracts data from cookie to identity"
        ;; session data must have data within :identity
        (let [value (session shield session-info)
              valid-request {:headers {"cookie" (codec/form-encode {"bones-session" value})}}
              req ((auth/identity-interceptor shield)
                   (session-request valid-request (:cookie-opts shield)))]
          (is (= 123 (get-in req [:identity :xyz]))))))))
