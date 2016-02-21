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
  (let [handler  (yada (login-resource :bones.http.db/authenticate-user))]
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
