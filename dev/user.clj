(ns user
  (require [clojure.core.async :as a]
           [schema.core :as s]
           [manifold.stream :as ms]
           [bones.http.core :as http]))

(def sys (atom {}))

(defn event-stream-handler [req auth-info]
  (let [output-stream (ms/stream)
        source (ms/->source (range 10))]
    (ms/connect
     source
     output-stream)
    ;; must return the stream
    output-stream))

(defn add-account [args auth-info req]
  [args auth-info])

(def commands
  [[:add-account {:account/xact-id s/Int
                  :account/evo-id (s/maybe s/Int)}
    add-account]])

(defn login [args req]
  (if (= (:password args) (:username args))
    {:anything "to-identify"}
    nil ;; will return 401
    ))

(defn start []
  (http/build-system sys {:http/handlers {:login [{:username s/Str :password s/Str} login]
                                          :commands commands
                                          :query [{:q s/Any} (fn [args auth-info req]
                                                               [args auth-info])]
                                          :event-stream event-stream-handler}
                                        ;(bones.http.auth/gen-secret)
                          :http/auth {:secret "CypOW2ZYqvB42ahTI9GdXZ5v4sphlwdC"
                                      :allow-origin "http://localhost:3449"}
                          :http/service {:port 8080}})
  (http/start sys))

(defn stop []
  (http/stop sys))

(comment
;; SSE curl
;; curl "localhost:8080/api/events" --cookie "bones-session=w0Sr6aFcdwmtcnbmuxfmiuYgtmo0VUGylXKtdpVpv%2B7c5y3FVjWYdJ21cgv2g%2BlNkgdyYvE%2BvpqQWHUW%2BhcobzDOqd8M6SIWVWtp%2FXP9wG4%3D--fTYQgX1%2BSQkz2Q%2Bc5qaEhYGPMlBU2hdcdSy5bcs%2B2S0%3D" -v

(println "hi")
(start)
(stop)
  )
