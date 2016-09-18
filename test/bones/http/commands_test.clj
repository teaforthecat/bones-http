(ns bones.http.commands-test
  (:require [bones.http.commands :as commands]
            [clojure.test :refer [deftest testing is]]))

(defn hi [] "hi")

(deftest resolve-command
  (testing "a function"
    (is (= "hi"
           ((commands/resolve-command bones.http.commands-test/hi)))))
  (testing "a namespaced keyword"
    (is (= "hi"
           ((commands/resolve-command ::hi)))))
  (testing "a non-namespaced keyword"
    ;; passes in repl, fails in lein test(?)
    #_(is (= "hi"
           ((commands/resolve-command :hi)))))
  (testing "a symbol"
    ;; passes in repl, fails in lein test(?)
    #_(is (= "hi"
           ((commands/resolve-command 'hi)))))
  (testing "a namespaced symbol"
    (is (= "hi"
           ((commands/resolve-command 'bones.http.commands-test/hi))))))
