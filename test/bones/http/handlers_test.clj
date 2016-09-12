(ns bones.http.handlers-test
  (:require [bones.http.handlers :as handlers]
            [bones.http.auth :as auth]
            [clojure.test :refer [deftest testing is are]]
            [clojure.edn :as edn]
            ;; [io.pedestal.test :refer [response-for]]
            ;; [io.pedestal.http :as bootstrap]
            ;; [io.pedestal.http.route.definition :refer [defroutes]]
            [bones.http.service :as service]
            ;; [ring.middleware.session.store :as store]
            ;; [ring.util.codec :as codec]
            [peridot.core :as p]
            [clojure.core.async :as a]
            [manifold.stream :as ms]
            [aleph.http :as http]
            [byte-streams :as bs]
            [ring.mock.request :refer (request) :rename {request mock-request}]
            [bones.http.yada :as yada]
            [schema.core :as s]
            ))

(def conf {:http/auth {:secret  (apply str (map char (range 32)))
                       :cookie-name "pizza"}})

(def shield (.start (auth/map->Shield {:conf conf})))

(defn who [args req]
  {:message (str "Hello" (:first-name args))})
(defn what [args req] args)
(defn where [args req] args)

(def commands [[:who {:first-name s/Str} ::who]
               [:what {:weight-kg s/Int}]
               [:where {:room-no s/Int}]])

(defn event-stream-handler [req auth-info]
  (let [output-stream (ms/stream)
        count (Integer/parseInt (get-in req [:query "count"] "0"))
        source (ms/->source (range 2))]
    (ms/connect
     source
     output-stream)
    ;; must return the stream
    output-stream))

(defn ws-handler [{:keys [params] :as req}]
  (let [cnt (Integer/parseInt (get params "count" "0"))
        body @(http/websocket-connection req)]
    (a/go-loop [i 0]
      (if (< i cnt)
        (let [_ (a/<! (a/timeout 100))]
          (ms/put! body (str i "\n"))
          (recur (inc i)))
        (ms/close! body)))
    ;;no return value
    ))

(defn query-handler [req]
  ;; {:status 200
  ;;  :headers {"content-type" "text"}
  ;;  :body "hello"}
  "hello")

(defn login-handler [req]
  "Welcome")

(def test-handlers
  {
   :login login-handler
   :commands commands
   :query query-handler
   :query-schema {:name String}
   :event-stream event-stream-handler
   :websocket ws-handler
   })


(defn new-app [] (yada/app test-handlers shield))

(defn get-response [ctx]
  (-> ctx
      :response
      deref
      (update :body bs/to-string)))

(defn GET [app path params]
  (-> (p/session app)
      (p/request path
                 :request-method :get
                 :content-type "application/edn"
                 :params params)
      (get-response)))

(def valid-token
  {"Authorization" "Token eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1MiLCJlbmMiOiJBMTI4R0NNIn0.-KBhDMBnLd1tRL4u_fxC5nKTWB1TA7mt.vZ36HSF4yNVRxjP5.37-PnQnhKF3QMclC0jYObzcV.BwliiVaQu5n5Ylkvrs51lg"})

(defn POST [app path params]
  (-> (p/session app)
      (p/request path
                 :request-method :post
                 :content-type "application/edn"
                 ;; this token was encrypted from then login response
                 :headers valid-token
                 :body (pr-str params))
      (get-response)))

(defmacro has [response & attrs]
  `(are [k v] (= v (k ~response))
    ~@attrs))

(deftest get-commands
  (let [app (new-app)
        response (GET app "/api/commands" {})]
    (has response
         :status 200
         :headers {}
         :body "[[:who {:first-name java.lang.String}] [:what {:weight-kg Int}] [:where {:room-no Int}]]\n")))

(deftest not-found
  (let [app (new-app)
        response (GET (new-app) "/api/nothing" {})]
    (has response
         :status 404
         ;; note: json?-what?
         :headers {"content-length" "9", "content-type" "application/json"}
         :body "not found")))

(deftest get-query
  (testing "valid params"
    (let [app (new-app)
          response (GET app "/api/query" {:name "abc"})]
      (has response
           :status 200
           :headers {"x-frame-options" "SAMEORIGIN", "x-xss-protection" "1; mode=block", "x-content-type-options" "nosniff", "content-length" "5", "content-type" "application/edn"}
           :body "hello")))
  (testing "missing required param"
    (let [app (new-app)
          response (GET app "/api/query" {:q 123})]
      (has response
           :status 400
           :headers {"content-length" "49", "content-type" "application/edn"}
           :body "{:name missing-required-key, \"q\" disallowed-key}\n"))))

(deftest post-login
  (let [app (new-app)
        response (POST app "/api/login" {:username "abc" :password "123"})]
          (is (= 200 (:status response)))
          (is (= (get (:headers response) "content-type") "application/edn"))
          (is (contains? (:headers response) "set-cookie"))
          (is (= "token"  (re-find #"token" (:body response))))))


(deftest post-command
  (testing "valid command"
    (let [app (new-app)
          response (POST app "/api/command" {:command :who
                                             :args {:first-name "Santiago"}})]
      (has response
           :status 200
           :headers {"x-frame-options" "SAMEORIGIN", "x-xss-protection" "1; mode=block", "x-content-type-options" "nosniff", "content-length" "27", "content-type" "application/edn"}
           :body "{:message \"HelloSantiago\"}\n")))
  (testing "invalid command"
    (let [app (new-app)
          response (POST app "/api/command" {:command :who
                                             :args {:last-name "Santiago"}})]
      (has response
           :status 400
           :headers {"x-frame-options" "SAMEORIGIN", "x-xss-protection" "1; mode=block", "x-content-type-options" "nosniff", "content-length" "70", "content-type" "application/edn"}
           :body "{:args {:first-name missing-required-key, :last-name disallowed-key}}\n"))))

(deftest get-events
  (testing "format events"
    (are [in out] (= out (yada/format-event in))
      {:event 1 :id 2 :data 3} "event: 1\nid: 2\ndata: 3\n\n"
      "whatever\n"             "data: whatever\n\n\n"
      {:id "oops"}             "id: oops\ndata: {:id \"oops\"}\n\n"
      {:event "oops"}          "event: oops\ndata: {:event \"oops\"}\n\n"))
  (testing "get number stream"
    (let [app (new-app)
          response (GET app "/api/events" {})]
      (has response
           :status 200
           :headers {"x-frame-options" "SAMEORIGIN", "x-xss-protection" "1; mode=block", "x-content-type-options" "nosniff", "content-type" "text/event-stream"}
           :body "data: 0\n\ndata: 1\n\n"
           ))))










;; (defn test-events [event-channel ctx]
;;   (a/put! event-channel {:name "greetings"
;;                          :data "hello"
;;                          :id 1})
;;   (a/close! event-channel))

;; (def conf {:http/auth {:secret  (apply str (map char (range 32)))
;;                        :cookie-name "pizza"}
;;            :http/handlers {:event-stream-handler test-events}})
;; (def shield (.start (auth/map->Shield {:conf conf})))
;; (def cqrs (.start (handlers/map->CQRS {:shield shield
;;                                        :conf conf})))
;; (def routes (:routes cqrs))
;; (def service
;;   (::bootstrap/service-fn (bootstrap/create-servlet (service/service routes {}))))

;; (defn edn-post
;;   ([body-params]
;;    (edn-post body-params {}))
;;   ([body-params headers]
;;    (edn-post body-params headers "/api/command"))
;;   ([body-params headers path]
;;    (response-for service
;;                  :post path
;;                  :body (pr-str body-params)
;;                  :headers (merge {"Content-Type" "application/edn"
;;                                   "Accept" "application/edn"}
;;                                  headers))))

;; (defn login-post [body-params]
;;   (edn-post body-params {} "/api/login"))

;; (defn http-get
;;   ([path]
;;    (http-get path {}))
;;   ([path headers]
;;    (response-for service
;;                  :get path
;;                  :headers headers)))

;; (defn edn-get
;;   ([path]
;;    (edn-get path {}))
;;   ([path headers]
;;    (http-get path (merge {"Content-Type" "application/edn"
;;                           "Accept" "application/edn"}
;;                          headers))))

;; (defn edn-body [{:keys [body]}]
;;   (-> body edn/read-string))

;; (def valid-token
;;   {"authorization" (str "Token " (auth/token shield {:identity {:user-d 123}}))})

;; (def invalid-token
;;   {"authorization" "Token nuthin"})

;; (deftest register-command-test
;;   ;; this affects the tests below - maybe use fixtures somehow
;;   (testing "optional handler argument"
;;     (is (handlers/register-command :test {} ::hello)))
;;   (testing "explicit handler with a namespace"
;;     (is (handlers/register-command :test {} ::handlers/echo)))
;;   (testing "non existing function throws error"
;;     (is (thrown? clojure.lang.ExceptionInfo
;;                  (handlers/register-command :nope {}))))
;;   (testing "all at once with register-commands"
;;     (is (handlers/register-commands [[:test {} ::hello]
;;                                      [:test {} ::handlers/echo]]))))

;; ;; pretend command handler
;; (defn hello [args req]
;;   (let [who (get-in args [:who])]
;;     {:greeting (str "hello " who)}))

;; (handlers/register-command :hello {:who s/Str})

;; ;; pretend command handler
;; (defn thrower [args req]
;;   (throw (ex-info "something fake" {:other "stuff" :status 555})))

;; (handlers/register-command :thrower {:what s/Keyword})

;; (deftest command-resource-test
;;   ;; given the registered commands above
;;   (testing "invalid token"
;;     (let [body-params {:command :hello :args {:who "mr teapot"}}
;;           response (edn-post body-params invalid-token)]
;;       (is (= "{:message \"Not Authenticated\"}" (:body response)))
;;       (is (= "application/edn"  (get (:headers response)  "Content-Type")))
;;       (is (= 401 (:status response)))))

;;   (testing "non-existant args"
;;     (let [response (edn-post {:command :echo #_no-args} valid-token)]
;;       (is (= "{:message \"args not valid\", :data {:args missing-required-key}}" (:body response)))
;;       (is (= "application/edn" (get (:headers response) "Content-Type")))
;;       (is (= 400 (:status response)))))

;;   (testing "non-existant command"
;;     (let [response (edn-post {:command :nuthin} valid-token)]
;;       (is (= {:message "command not found"
;;               :data {:message "command not found: :nuthin"
;;                      :available-commands '(:echo :hello :thrower :login)}}
;;              (edn-body response)))
;;       (is (= "application/edn" (get (:headers response) "Content-Type")))
;;       (is (= 400 (:status response)))))

;;   (testing "args that are something other than a map"
;;     (let [response (edn-post {:command :echo :args [:not :a-map]} valid-token)]
;;       (is (= "{:message \"args not valid\", :data {:args (not (map? [:not :a-map]))}}" (:body response)))
;;       (is (= "application/edn" (get (:headers response) "Content-Type")))
;;       (is (= 400 (:status response)))))

;;   (testing "built-in echo command with valid args"
;;     (let [response (edn-post {:command :echo :args {:yes :allowed}} valid-token)]
;;       (is (= "{:yes :allowed}" (:body response)))
;;       (is (= "application/edn" (get (:headers response) "Content-Type")))
;;       (is (= 200 (:status response)))))

;;   (testing "registered command with valid args"
;;     (let [body-params {:command :hello :args {:who "mr teapot"}}
;;           response (edn-post body-params valid-token)]
;;       (is (= "{:greeting \"hello mr teapot\"}" (:body response)))
;;       (is (= "application/edn" (get (:headers response) "Content-Type")))
;;       (is (= 200 (:status response)))))

;;   (testing "an exception's ex-data gets rendered and status gets used"
;;     (let [body-params {:command :thrower :args {:what :blowup}}
;;           response (edn-post body-params valid-token)]
;;       (is (= "application/edn" (get (:headers response) "Content-Type")))
;;       (is (= "{:message \"something fake\", :data {:other \"stuff\"}}" (:body response)))
;;       (is (= 555 (:status response))))))

;; ;; pretend command handler
;; (defn login [args req]
;;   (if (= "shortandstout" (:password args))
;;     {:user-id 123 :role "appliance"}))

;; (handlers/register-command :login {:username s/Str :password s/Str})

;; (def valid-login {:command :login :args {:username "mr teapot" :password "shortandstout"}})

;; (def invalid-login {:command :login :args {:username "mr teapot" :password "tallandfuzzy"}})

;; (deftest login-resource-test
;;   ;; given the registered login handler above
;;   (testing "Set-Cookie is set on response if login command returns data(non-nil)"
;;     (let [response (login-post valid-login)]
;;       (is (= 200 (:status response)))
;;       (is (= (get (:headers response) "Content-Type") "application/edn"))
;;       (is (contains? (:headers response) "Set-Cookie"))
;;       (is (contains? (edn-body response) :token))))
;;   (testing "no session is set if login command returns nil"
;;     (let [response (login-post invalid-login)]
;;       (is (= 400 (:status response)))
;;       (is (not (contains? (:headers response) "Set-Cookie")))
;;       (is (= {:message "Invalid Credentials"} (edn-body response)))
;;       (is (= (get (:headers response) "Content-Type") "application/edn")))))

;; (deftest command-list-resource-test
;;   ;; this is order/time sensitive because of the registered commands
;;   (testing "get"
;;     (let [response (edn-get "/api/command")]
;;       (is (= '{:available-commands ({:args {Any Any}
;;                                     :command (enum :echo)}
;;                                    {:args {:who java.lang.String}
;;                                     :command (enum :hello)}
;;                                    {:args {:what Keyword}
;;                                     :command (enum :thrower)}
;;                                     ;; login should probably be filtered out?
;;                                    {:args {:username java.lang.String
;;                                            :password java.lang.String}
;;                                     :command (enum :login)})}
;;              (edn-body response)))
;;       (is (= "application/edn" (get (:headers response) "Content-Type")))
;;       (is (= 200 (:status response))))))

;; ;; pretend query handler
;; (defn graph [params req]
;;   (let [g (-> params :q edn/read-string)]
;;     {:graph g}))

;; (handlers/register-query-handler :graph {:q s/Str} )

;; (deftest query-resource-test
;;   ;; given the registered query handler above
;;   (testing "a valid token or session is required"
;;     (let [response (edn-get "/api/query?q=abc" invalid-token)]
;;       (is (= 401 (:status response)))))
;;   (testing "the query handler can be overwritten by `register-query-handler'"
;;     (let [response (edn-get "/api/query?q=abc" valid-token)]
;;       (is (= "{:graph abc}" (:body response)))
;;       (is (= 200 (:status response)))))
;;   (testing "the query params gets checked against the schema"
;;     (let [response (edn-get "/api/query?something=else" valid-token)]
;;       (is (= "{:message \"query params not valid\", :data {:q missing-required-key, :something disallowed-key}}" (:body response)))
;;       (is (= 400 (:status response))))))

;; (defn parse-cookie [cookie]
;;   (-> cookie
;;       first
;;       (clojure.string/split #";")
;;       first))

;; (deftest session-requests
;;   (let [login-response (login-post valid-login)
;;         cookie (get-in login-response [:headers "Set-Cookie"])]
;;     (testing "command with session - reuse the cookie"
;;       (let [body-params {:command :echo :args {:who "mr teapot"}}
;;             response (edn-post body-params {"cookie" (parse-cookie cookie)})]
;;         (is (= "{:who \"mr teapot\"}" (:body response)))
;;         (is (= 200 (:status response)))))))

;; (deftest event-stream-test
;;   (testing "a stream is started"
;;     (let [response (http-get "/api/events" (merge valid-token
;;                                                   {"Content-Type" "text/event-stream"}))
;;           rn "\r\n"]
;;       (is (= (str
;;               "event: greetings" rn
;;               "data: hello" rn
;;               "id: 1" rn rn) (:body response)))
;;       (is (= 200 (:status response))))))
