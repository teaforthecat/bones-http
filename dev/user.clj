(ns user
  (require [clojure.core.async :as a]
           [schema.core :as s]
           [manifold.stream :as ms]
           [bones.http.core :as http])
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

;; all the commands with names
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

  ;; create token (login)
  (.token (:shield @sys) {:abc "123"})

  ;; check it
  (proto/-authenticate (get-in @sys [:shield :token-backend]) {} "eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1MiLCJlbmMiOiJBMTI4R0NNIn0.HVUpeQY0SgjN5KGXXU7zQnkZhacEFm1d.WZq2kqGbQmJ5HvzA.ZbkbjUimjPH-KCCPRQ.qoJeedBfruV59vOqUdpnGA") ;;=> {:abc "123"}

  ;; curl localhost:8080/api/events -H "Authorization: Token eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1MiLCJlbmMiOiJBMTI4R0NNIn0.HVUpeQY0SgjN5KGXXU7zQnkZhacEFm1d.WZq2kqGbQmJ5HvzA.ZbkbjUimjPH-KCCPRQ.qoJeedBfruV59vOqUdpnGA"

  ;; curl localhost:8080/api/events --cookie "bones-session=eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1MiLCJlbmMiOiJBMTI4R0NNIn0.HVUpeQY0SgjN5KGXXU7zQnkZhacEFm1d.WZq2kqGbQmJ5HvzA.ZbkbjUimjPH-KCCPRQ.qoJeedBfruV59vOqUdpnGA" -v
  )
