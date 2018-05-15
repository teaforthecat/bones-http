(ns bones.http.handlers-test
  (:require [clojure.test :refer [deftest testing is are use-fixtures run-tests]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :refer [*logger-factory*]]
            [clojure.tools.logging.impl :as impl]
            [bidi.bidi :refer [match-route]]
            [bidi.ring :refer [make-handler]]
            [yada.resources.classpath-resource :as yr]
            [yada.yada :refer [yada]]
            [bones.http.auth :as auth]
            [bones.http.handlers :as handlers]
            [bones.http.service :as service]
            [byte-streams :as bs]
            [clj-time.core :as t]
            [clj-time.format :as tf]
            [manifold.stream :as ms]
            [peridot.core :as p]))


;; start logger stub
(def ^{:dynamic true} *entries* (atom []))

(defn test-factory [enabled-set]
  (reify impl/LoggerFactory
    (name [_] "test factory")
    (get-logger [_ log-ns]
      (reify impl/Logger
        (enabled? [_ level] (contains? enabled-set level))
        (write! [_ lvl ex msg]
          (swap! *entries* conj [(str log-ns) lvl ex msg]))))))

(use-fixtures :once
  (fn [f]
    (binding [*logger-factory*
              (test-factory #{:trace :debug :info :warn :error :fatal})]
      (f))))

(use-fixtures :each
  (fn [f]
    (f)
    (swap! *entries* (constantly []))))
;; end logger stub



;; start example implementation

(def conf {:bones.http/auth {:secret  (apply str (map char (range 32)))
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

(s/def ::name string?)

(def query-schema (s/keys :req-un [::name]))

(defn login-handler [args req]
  (if (= "abc" (:username args))
    [args "Welcome"]
    nil ;; 401
    ))

(defn logout-handler [req]
  "goodbye")

(s/def ::username string?)
(s/def ::password string?)

(def login-schema (s/keys :req-un [::username ::password]))

(defn who [args auth-info req]
  {:message (str "Hello" (:first-name args))})
(defn what [args auth-info req] args)
(defn where [args auth-info req]
  (condp = (:room-no args)
    5 (throw (ex-info "occupied" {:status 422 :message "room is occupied"}))
    9 (throw (ex-info "Server Error" {:status 500 :message "hello" :explosion 'oops}))
    "room granted"))

(s/def ::first-name string?)
(s/def ::what integer?)
(s/def ::where integer?)

(s/def ::who (s/keys :req-un [::first-name]))
(s/def ::what (s/keys :req-un [::weight-kg]))
(s/def ::where (s/keys :req-un [::room-no]))

(def commands [[:who ::who who]
               [:what ::what what]
               [:where ::where where]])

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
  {"Authorization" "Token eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1QiLCJlbmMiOiJBMTI4R0NNIn0.x6MngqAvPPrBnlr7jERY92QR30XGaXz0.2g-lzuaNPRaUqay7.Ll_Rsl6pOp6SKqEmgQ.EG0A18FZsqkbnAq3KCu8-g"})

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
(deftest routes
  (testing "default routes"
    (let [routes-vec (handlers/routes test-handlers shield)
          combined-routes (handlers/combine-routes routes-vec (handlers/default-extra-route))
          test-fn #(get-in (match-route combined-routes %) [:handler :id])]
      (are [route-id path] (= route-id (test-fn path))
        :bones/command "/api/command"
        :bones/not-found "/api/nothing"
        :bones/not-found "/nothing")))
  (testing "user defined routes"
    (let [routes-vec (handlers/routes test-handlers shield)
          ;; struggling to route "/" to index.html
          conf-routes ["/public" (yada
                                  (assoc
                                   (yr/new-classpath-resource
                                    "public" {:index-files ["index.html"]})
                                   :id :bones/public))]
          combined-routes (handlers/combine-routes routes-vec conf-routes)
          test-fn #(get-in (match-route combined-routes %) [:handler :id])]
      (are [route-id path] (= route-id (test-fn path))
        :bones/command "/api/command"
        :bones/not-found "/api/nothing"
        :bones/not-found "/arst"
        :bones/public "/public"))))

(deftest commands
  (let [app (new-app)
        response (GET app "/api/commands" {} {})]
    (has response
         :status 200
         :body "((:who :bones.http.handlers-test/who) (:what :bones.http.handlers-test/what) (:where :bones.http.handlers-test/where))\n")))

(deftest not-found
  (testing "default extra routes"
    (let [app (new-app)
          response (GET (new-app) "/nothing" {} {})]
      (has response
           :status 404
           ;; note: json?-what?
           :headers {"content-length" "9", "content-type" "application/json"}
           :body "not found")))
  (testing "not found path under mount-path"
    (let [app (new-app)
          response (GET (new-app) "/api/nothing" {} {})]
      (has response
           :status 404
           ;; note: json?-what?
           :headers {"content-length" "9", "content-type" "application/json"}
           :body "not found"))))

(deftest query
  (testing "valid params"
    (let [app (new-app)
          response (api-get app "/api/query" {:name "abc"})]
      (has response
           :status 200
           :headers (secure-and {"content-length" "516", "content-type" "application/edn"})
           ;; Note: parameters are `keywordized'
           :body "[\"ok\" {:name \"abc\"} {\"default\" {:abc \"123\"}} {:remote-addr \"localhost\", :params nil, :route-params nil, :headers {\"host\" \"localhost\", \"authorization\" \"Token eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1QiLCJlbmMiOiJBMTI4R0NNIn0.x6MngqAvPPrBnlr7jERY92QR30XGaXz0.2g-lzuaNPRaUqay7.Ll_Rsl6pOp6SKqEmgQ.EG0A18FZsqkbnAq3KCu8-g\", \"content-type\" \"application/edn\"}, :server-port 80, :content-type \"application/edn\", :uri \"/api/query\", :server-name \"localhost\", :query-string \"name=abc\", :body nil, :scheme :http, :request-method :get}]\n"
           )))
  (testing "missing required param"
    (let [app (new-app)
          response (api-get app "/api/query" {:q 123})]
      (has response
           :status 400
           :headers (secure-and {"content-length" "288", "content-type" "application/edn"})
           ;; TODO: expound
           ;; :body "#:clojure.spec.alpha{:problems ({:path [], :pred (clojure.core/fn [%] (contains? % :name)), :val {:q \"123\"}, :via [], :in []})}\n"
           )))
  (testing "with empty query defaults to empty map, which can be valid"
    (let [app (new-app :query [map? query-handler])
          response (api-get app "/api/query" {})]
      (has response
           :status 200)))
  (testing "returns an empty string if given"
    (let [app (new-app :query [map? (fn [_ _ _] "")])
          response (api-get app "/api/query" {})]
      (has response
           :body ""
           :status 200)))
  (testing "returns 404 when nil"
    (let [app (new-app :query [map? (fn [_ _ _] nil)])
          response (api-get app "/api/query" {})]
      (has response
           :status 404))))

(deftest login
  (testing "set-cookie and token"
    (let [app (new-app)
          response (POST app "/api/login" {:username "abc" :password "123"} {})]
      (is (= 200 (:status response)))
      (is (= (get (:headers response) "content-type") "application/edn"))
      (is (contains? (:headers response) "set-cookie"))
      (is (= "token"  (re-find #"token" (:body response))))))
  (testing "returns 400 when nil"
    (let [app (new-app :login [login-schema (fn [_ _] nil)])
          response (api-post app "/api/login" {:username "abc" :password "123"})]
      (has response
           :body "invalid credentials"
           :status 401)))
  (testing "shares data from the token data via meta data"
    (let [response-data ^{:share [:roles]} {:userid "not-shared" :roles [:janitor]}
          app (new-app :login [login-schema (fn [_ _]  response-data)])
          response (api-post app "/api/login" {:username "abc" :password "123"})
          body (read-string (:body response))]
      (is (= (body "share") {:roles [:janitor]})))))

;; from yada.cookies
(defn cookie-now []
  (tf/unparse (tf/formatters :rfc822) (t/now)))

(deftest logout
  (testing "unset cookie"
    (let [app (new-app)
          response (GET app "/api/logout" {} {})]
      (has response
           :status 200
           :headers (secure-and {"content-length" "7"
                                 "content-type" "application/edn"
                                 ;; luckily this finishes in under a second
                                 "set-cookie" (list (str "pizza=nil; Expires=" (cookie-now)))})
           :body "goodbye"
           ))))

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
           :headers (secure-and {"content-length" "258", "content-type" "application/edn"})
           ;; :body "#:clojure.spec.alpha{:problems ({:path [], :pred (contains? % :first-name), :val {:last-name \"Santiago\"}, :via [:bones.http.handlers-test/who], :in []})}\n"
           )))
  (testing "empty body"
    (are [t v] (= t v)
      "(not (map? \"\"))" (:body (post-command ""))))
  (testing "throwing exception intentionally for a status response"
    (let [response (post-command {:command :where :args {:room-no 5}})]
      (has response
           :status 422
           :body "room is occupied")))
  (testing "triggering an exception unintentionally"
    ;; this writes an exception to stderr, I think, I can't prove it
    (let [response (post-command {:command :where :args {:room-no 9}})]
      (has response
           :status 500
           :body "Server Error"))))

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
           :headers (secure-and {"allow" "POST, OPTIONS",
                                 "content-length" "0",
                                 "access-control-allow-origin" "http://localhost:3449",
                                 "access-control-allow-credentials" "true",
                                 "access-control-allow-methods" "GET, POST",
                                 "access-control-allow-headers" "Content-Type, Authorization"})))))

;; testing the request doesn't work because the (:request ctx) is a map, but
;; aleph expects NettyRequest which it provides when routes are run by the aleph
;; server, not sure how to proceed here yet
;; (deftest websocket
;;   (testing "get number stream"
;;     (let [app (new-app)
;;           response (api-get app "/api/ws" {"Connection" "Upgrade"
;;                                            "Upgrade" "websocket"})]
;;       (has response
;;            :status 200
;;            :body "0\n\n1\n\n"
;;            :headers (secure-and {"Connection" "Upgrade"
;;                                  "Upgrade" "websocket"
;;                                  "Sec-Websocket-Accept" "wat"})))))
