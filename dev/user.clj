(ns user
  (require [clojure.core.async :as a]
           [clojure.spec :as s]
           [manifold.stream :as ms]
           [bones.http :as http])
  (:require [buddy.auth.protocols :as proto]))

;; the global reload-able system
(def sys (atom {}))

;; SSE handler
(defn event-stream-handler [req auth-info]
  (let [output-stream (ms/stream)
        source (ms/->source (range 10))]
    (ms/connect
     source
     output-stream)
    ;; must return the stream
    output-stream))

;; command handler / your project domain function
(defn add-race [args auth-info req]
  [args auth-info])

;; all the commands with names and schemas
(def commands
  [[:add-race {:red-truck s/Int
               :blue-truck (s/maybe s/Int)}
    add-race]])

;; login handler
(defn login [args req]
  (if (= (:password args) (:username args))
    {:anything "to-identify"}
    nil ;; will return 401
    ))

;; put it all together
(defn start []
  (http/build-system sys {:http/handlers {:login [{:username s/Str :password s/Str} login]
                                          :commands commands
                                          :query [{:q s/Any} (fn [args auth-info req]
                                                               [args auth-info])]
                                          :event-stream event-stream-handler}
                          :http/auth {:secret "CypOW2ZYqvB42ahTI9GdXZ5v4sphlwdC"
                                      :allow-origin "http://localhost:3449"}
                          :http/service {:port 8080}})
  (http/start sys))

(defn stop []
  (http/stop sys))

(comment
  (println "hi")
  (start)
  (stop)

  ; After starting the webserver by evaluating `(start)', these commands can be pasted into a terminal to see the output


  ;;### login request
  ;;    $ curl localhost:8080/api/login -v -H "Content-Type: application/edn" -d '{:username "hi" :anything true}'
  ;; returns token


  ;;#### POST Command
  ;;    $ curl localhost:8080/api/command -v -X POST -H "Content-Type: application/edn" -d '{:command :add-race :args {:red-truck 123 :blue-truck 456}}' -H "Authorization: Token eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1MiLCJlbmMiOiJBMTI4R0NNIn0.HVUpeQY0SgjN5KGXXU7zQnkZhacEFm1d.WZq2kqGbQmJ5HvzA.ZbkbjUimjPH-KCCPRQ.qoJeedBfruV59vOqUdpnGA"
  ;; returns args and auth-info


  ;;### GET Query
  ;;    $ curl localhost:8080/api/query?q=anything -v -X GET -H "Content-Type: application/edn" -H "Authorization: Token eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1MiLCJlbmMiOiJBMTI4R0NNIn0.HVUpeQY0SgjN5KGXXU7zQnkZhacEFm1d.WZq2kqGbQmJ5HvzA.ZbkbjUimjPH-KCCPRQ.qoJeedBfruV59vOqUdpnGA"
  ;; returns args and auth-info


  ;;### SSE with token
  ;;    $ curl localhost:8080/api/events -H "Authorization: Token eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1MiLCJlbmMiOiJBMTI4R0NNIn0.HVUpeQY0SgjN5KGXXU7zQnkZhacEFm1d.WZq2kqGbQmJ5HvzA.ZbkbjUimjPH-KCCPRQ.qoJeedBfruV59vOqUdpnGA"
  ;; 10 numbers streaming


  ;;### SSE with cookie
  ;;    $ curl localhost:8080/api/events --cookie "bones-session=eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1MiLCJlbmMiOiJBMTI4R0NNIn0.HVUpeQY0SgjN5KGXXU7zQnkZhacEFm1d.WZq2kqGbQmJ5HvzA.ZbkbjUimjPH-KCCPRQ.qoJeedBfruV59vOqUdpnGA" -v
  ;; 10 numbers streaming


  ;;### WebSocket
  ;;    $ curl localhost:8080/api/ws -v -N -H "Connection: Upgrade" -H "Upgrade: websocket" -H "Host: localhost:8080" -H "Origin: localhost:8080" -H "Authorization: Token eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1MiLCJlbmMiOiJBMTI4R0NNIn0.HVUpeQY0SgjN5KGXXU7zQnkZhacEFm1d.WZq2kqGbQmJ5HvzA.ZbkbjUimjPH-KCCPRQ.qoJeedBfruV59vOqUdpnGA"
  ;; not pretty, but you see 10 numbers

  )
