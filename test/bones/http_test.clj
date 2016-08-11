(ns bones.http-test
  (:require [clojure.test :refer :all]
            [bones.http :refer :all]
            [bones.http.db :refer [create-user find-user]]
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

(defn mock-authenticate-user [username password]
  (if (= username "admin")
    {:id 1}))

(deftest test-login
  (let [handler (yada (login-resource ::mock-authenticate-user))]
    (testing "post with form params returns edn"
      (let [request (mock/request :post x {:username "admin" :password "secret"})
            response @(handler request)]
        (if-not (m/is (m/= 200) (:status response))
          (m/is (m/starts-with "session=") (first (get-in response [:headers "set-cookie"])))
          (m/is (m/has-key :token) (edn/read-string (bs/to-string (:body response)))))
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

(defn authenticated [req token]
  (-> req
      (edn-request)
      (update :headers assoc token-name token)))

(deftest test-command
  (let [handler (yada (command-resource))]
    (testing "accepts application/edn content-type"
      (let [request (mock/request :post x (print-str {:command_type :a :message {:somethig 123}}))
            response @(handler (authenticated request (create-token {:user {:id 1}})))]
        (m/is (m/= 200) (:status response))
        ))
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
