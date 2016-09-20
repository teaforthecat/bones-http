(ns bones.http.handlers-test
  (:require [bones.http.handlers :as handlers]
            [bones.http.auth :as auth]
            [clojure.test :refer [deftest testing is are]]
            [clojure.edn :as edn]
            [bones.http.service :as service]
            [peridot.core :as p]
            [clojure.core.async :as a]
            [manifold.stream :as ms]
            [aleph.http :as http]
            [byte-streams :as bs]
            [ring.mock.request :refer (request) :rename {request mock-request}]
            [bones.http.handlers :as handlers]
            [schema.core :as s]))

;; start example implementation

(def conf {:http/auth {:secret  (apply str (map char (range 32)))
                       :cookie-name "pizza"}})

(defn event-stream-handler [req auth-info]
  (let [output-stream (ms/stream)
        count (Integer/parseInt (get-in req [:query "count"] "0"))
        source (ms/->source (range 2))]
    (ms/connect
     source
     output-stream)
    ;; must return the stream
    output-stream))

(defn query-handler [args auth-info req]
  ["ok" args auth-info req])

(def query-schema {:name s/Str})

(defn login-handler [args req]
  [args "Welcome"])

(defn logout-handler [req]
  "goodbye")

(def login-schema {:username s/Str :password s/Str})

(defn who [args auth-info req]
  {:message (str "Hello" (:first-name args))})
(defn what [args auth-info req] args)
(defn where [args auth-info req] args)

(def commands [[:who {:first-name s/Str} who]
               [:what {:weight-kg s/Int} what]
               [:where {:room-no s/Int}  where]])

(def test-handlers
  {:login [login-schema login-handler]
   :logout logout-handler
   :commands commands
   :query [query-schema query-handler]
   :event-stream event-stream-handler})

;; end example

;; start setup
(def shield (.start (auth/map->Shield {:conf conf})))

(defn new-app [& opts]
  (handlers/app (merge test-handlers (apply array-map opts )) shield))

(def valid-token
  ;; this token was encrypted from the login response
  ;; if the secret changes, this wil have to change; TODO: generate this
  {"Authorization" "Token eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1MiLCJlbmMiOiJBMTI4R0NNIn0.-KBhDMBnLd1tRL4u_fxC5nKTWB1TA7mt.vZ36HSF4yNVRxjP5.37-PnQnhKF3QMclC0jYObzcV.BwliiVaQu5n5Ylkvrs51lg"})

;; end setup

;; start helpers

(defn secure-and [headers]
  ;; security headers provided by yada
  (merge
   {"x-frame-options" "SAMEORIGIN"
    "x-xss-protection" "1; mode=block"
    "x-content-type-options" "nosniff"}
   headers))

(defn get-response [ctx]
  (-> ctx
      :response
      deref
      (update :body (fnil bs/to-string ""))))

(defn GET [app path params headers]
  (-> (p/session app)
      (p/request path
                 :request-method :get
                 :content-type "application/edn"
                 :headers headers
                 :params params)
      (get-response)))

(defn api-get [app path params]
  (GET app path params valid-token))

(defn POST [app path params headers]
  (let [body (pr-str params)]
    (-> (p/session app)
        (p/request path
                   :request-method :post
                   :content-type "application/edn"
                   :headers (merge headers
                                   ;; required for parameter parsing
                                   {"Content-Length" (count body)})
                   :body body)
        (get-response))))

(defn api-post [app path params]
  (POST app path params valid-token))

(defn post-command [data]
  (api-post (new-app) "/api/command" data))

(defn OPTIONS [app path]
  (-> (p/session app)
      (p/request path
                 :request-method :options
                 :headers {"Access-Control-Request-Method" "POST"
                           "Origin" "http://localhost:3449"
                           "Access-Control-Request-Headers" "content-type"})
      (get-response)))

(defmacro has [response & attrs]
  `(are [k v] (= v (k ~response))
    ~@attrs))

;; end helpers

;; start tests

(deftest commands
  (let [app (new-app)
        response (GET app "/api/commands" {} {})]
    (has response
         :status 200
         :body "((:who {:first-name java.lang.String}) (:what {:weight-kg Int}) (:where {:room-no Int}))\n")))

(deftest not-found
  (let [app (new-app)
        response (GET (new-app) "/api/nothing" {} {})]
    (has response
         :status 404
         ;; note: json?-what?
         :headers {"content-length" "9", "content-type" "application/json"}
         :body "not found")))

(deftest query
  (testing "valid params"
    (let [app (new-app)
          response (api-get app "/api/query" {:name "abc"})]
      (has response
           :status 200
           :headers (secure-and {"content-length" "536", "content-type" "application/edn"})
           :body "[\"ok\" {:query {:name \"abc\"}} {\"default\" {:data \"Welcome\"}} {:remote-addr \"localhost\", :params nil, :route-params nil, :headers {\"host\" \"localhost\", \"authorization\" \"Token eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1MiLCJlbmMiOiJBMTI4R0NNIn0.-KBhDMBnLd1tRL4u_fxC5nKTWB1TA7mt.vZ36HSF4yNVRxjP5.37-PnQnhKF3QMclC0jYObzcV.BwliiVaQu5n5Ylkvrs51lg\", \"content-type\" \"application/edn\"}, :server-port 80, :content-type \"application/edn\", :uri \"/api/query\", :server-name \"localhost\", :query-string \"name=abc\", :body nil, :scheme :http, :request-method :get}]\n")))
  (testing "missing required param"
    (let [app (new-app)
          response (api-get app "/api/query" {:q 123})]
      (has response
           :status 400
           :headers {"content-length" "49", "content-type" "application/edn"}
           :body "{:name missing-required-key, \"q\" disallowed-key}\n")))
  (testing "with any-any schema"
    (let [app (new-app :query [{} query-handler])
          response (api-get app "/api/query" {})]
      (has response
           :status 200)))
  (testing "returns an empty string"
    (let [app (new-app :query [{} (fn [_ _ _] "")])
          response (api-get app "/api/query" {})]
      (has response
           :body ""
           :status 200)))
  (testing "returns 404 when nil"
    (let [app (new-app :query [{} (fn [_ _ _] nil)])
          response (api-get app "/api/query" {})]
      (has response
           :status 404))))

(deftest login
  (testing "set-cookie"
    (let [app (new-app)
          response (POST app "/api/login" {:username "abc" :password "123"} {})]
      (is (= 200 (:status response)))
      (is (= (get (:headers response) "content-type") "application/edn"))
      (is (contains? (:headers response) "set-cookie"))
      (is (= "token"  (re-find #"token" (:body response))))))
  (testing "returns 404 when nil"
    (let [app (new-app :login [login-schema (fn [_ _] nil)])
          response (api-post app "/api/login" {:username "abc" :password "123"})]
      (has response
           :body "invalid credentials"
           :status 401))))

(deftest logout
  (testing "unset cookie"
    (let [app (new-app)
          response (GET app "/api/logout" {} {})]
      (has response
           :status 200
           :headers (secure-and {"content-length" "7"
                                 "content-type" "application/edn"
                                 "set-cookie" '("pizza=")})
           :body "goodbye")))

  (testing "returns 404 when nil"
     (let [app (new-app :login [{} (fn [_ _ _] nil)])
           response (api-get app "/api/query" {})]
       (has response
            :status 404))))

(deftest commands
  (testing "valid command"
    (let [app (new-app)
          response (api-post app "/api/command" {:command :who
                                                 :args {:first-name "Santiago"}})]
      (has response
           :status 200
           :headers (secure-and {"content-length" "27", "content-type" "application/edn"})
           :body "{:message \"HelloSantiago\"}\n")))
  (testing "invalid command"
    (let [app (new-app)
          response (api-post app "/api/command" {:command :who
                                             :args {:last-name "Santiago"}})]
      (has response
           :status 400
           :headers (secure-and {"content-length" "70", "content-type" "application/edn"})
           :body "{:args {:first-name missing-required-key, :last-name disallowed-key}}\n")))
  (testing "empty body"
    (are [t v] (= t v)
      "(not (map? \"\"))" (:body (post-command "")))))

(deftest events
  (testing "format events"
    (are [in out] (= out (handlers/format-event in))
      {:event 1 :id 2 :data 3} "event: 1\nid: 2\ndata: 3\n\n"
      "whatever\n"             "data: whatever\n\n\n"
      {:id "oops"}             "id: oops\ndata: {:id \"oops\"}\n\n"
      {:event "oops"}          "event: oops\ndata: {:event \"oops\"}\n\n"))
  (testing "get number stream"
    (let [app (new-app)
          response (api-get app "/api/events" {})]
      (has response
           :status 200
           :headers (secure-and {"content-type" "text/event-stream"})
           :body "data: 0\n\ndata: 1\n\n"
           ))))

(deftest cors
  (testing "login"
    (let [app (new-app)
          response (OPTIONS app "/api/login")]
      (has response
           :status 200
           :headers {"allow" "POST, OPTIONS", "content-length" "0", "access-control-allow-origin" "http://localhost:3449", "access-control-allow-credentials" "true", "access-control-allow-methods" "GET, POST", "access-control-allow-headers" "Content-Type, Authorization", "x-frame-options" "SAMEORIGIN", "x-xss-protection" "1; mode=block", "x-content-type-options" "nosniff"}))))
