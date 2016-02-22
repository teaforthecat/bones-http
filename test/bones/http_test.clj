(ns bones.http-test
  (:require [clojure.test :refer :all]
            [bones.http :refer :all]
            [bones.http.db :refer [create-user]]
            [schema.core :as s :refer [defschema]]
            [clojure.edn :as edn]
            [byte-streams :as bs]
            [yada.security :refer [authenticate verify]]
            [yada.yada :as yada :refer [yada]]
            [yada.resource :refer [resource]]
            [ring.mock.request :as mock]
            [peridot.core :as p]
            [matcha :as m]))


(def x "/not-testing-routing-now")

(def users
  [  {:username "admin"
      :password "secret"
      :roles [:admin]}
   {:username "jerry"
    :password "jerry"}])

(defn create-users [f]
  (map create-user users)
  (f))

(use-fixtures :once create-users)

(deftest login
  (let [handler (yada (login-resource :bones.http.db/authenticate-user))]
    (testing "post with form params returns edn"
      (let [request (mock/request :post x {:username "admin" :password "secret"})
            response @(handler request)]
        (m/is (m/= 200) (:status response))
        (m/is (m/starts-with "session=") (first (get-in response [:headers "set-cookie"])))
        (m/is (m/has-key :token) (edn/read-string (bs/to-string (:body response))))
        ))
    (testing "post with form params and json accept header returns json"
      (let [request (mock/request :post x {:username "admin" :password "secret"})
            response @(handler (update request :headers assoc "accept" "application/json"))]
        (m/is (m/= 200) (:status response))
        (m/is (m/starts-with "session=") (first (get-in response [:headers "set-cookie"])))
        (m/is (m/starts-with "{\"token\":") (bs/to-string (:body response)))
        ))
    (testing "invalid credentials returns 401"
      (let [response @(handler (mock/request :post x {:username "tom" :password "invalid"}))]
        (m/is (m/= 401) (:status response))))))

(defn edn-request [req]
  (update req :headers assoc "content-type" "application/edn"))

(defn authenticated-request [req]
  (-> req
      (edn-request)
      (update :headers assoc token-name "eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1MiLCJlbmMiOiJBMTI4R0NNIn0.6NWNdVYv5L2sM0PBYCl3X85ZemRfBk9X.gwQHFsX8XTgkJO7L.dmS7IPBnlTeOSG7-wfM-NZi19ilM2MJnwU64af5NBi7RN10DhUjOpkTBMGhQS7uCim-srw.LXdQpPY5WwFKTHOM3RkqDw")))

(deftest command
  (let [handler (yada (command-resource))]
    (testing "accepts application/edn content-type"
      (let [request (mock/request :post x (print-str {:command_type :a :message {:somethig 123}}))
            response @(handler (authenticated-request request))]
        (m/is (m/= 200) (:status response))
        )))

    (testing "does not accept form-encoded content-type"
      (let [request (mock/request :post x {:command_type :a :message {:somethig 123}})
            response @(handler request)]
        (m/is (m/= 415) (:status response))
        ))
    (testing "requires authentication"
      (let [request (mock/request :post x (print-str {:command_type :a :message {:somethig 123}}))
            response @(handler (update request :headers assoc "content-type" "application/edn"))]
        (m/is (m/= 403) (:status response))))
    ))
