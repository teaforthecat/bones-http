(ns bones.http.core-test
  (:require [bones.http.core :as http]
            [clojure.test :refer [deftest testing is]]))

(def sys (atom {}))
(def conf {:http/auth
           {:secret "a 16 byte stringa 32 byte string"
            :cookie-name "pizza"}})

(deftest start-system
  (testing "shield gets created and used by routes"
    (http/build-system sys conf)
    (http/start sys)
    (is (= "a 16 byte stringa 32 byte string" (get-in @sys [:routes :shield :secret])))
    (is (= "pizza" (get-in @sys [:routes :shield :cookie-opts :cookie-name])))
    (http/stop sys)))
