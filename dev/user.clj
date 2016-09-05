(ns user
  (require [clojure.core.async :as a]
           [schema.core :as s])
  (use [bones.http.core]))

(def sys (atom {}))

(defn send-counter-with-id
  "Sends value of counter and event id to sse context.
  Counts down while counter is greater than 0 and updates event id as well."
  [event-ch count-num event-id]
  ;; This is how you set a specific event name for the client to listen for
  (a/put! event-ch {:name "count-with-id"
                    :data (str count-num ", thread: " (.getId (Thread/currentThread)))
                    :id event-id})
  (Thread/sleep 1500)
  (if (> count-num 0)
    (recur event-ch (dec count-num) (dec count-num))
    (do
      (a/put! event-ch {:name "close" :data ""})
      (a/close! event-ch))))

(defn events [event-channel ctx]
  (send-counter-with-id event-channel  10 "10"))

(defn ws-handler [ws-session send-ch]
  (println ws-session )
  (send-counter-with-id send-ch 10 "10"))

(defn ws-onclose [num-code reason-text]
  (println num-code)
  (println reason-text))

(defn i-know-you [args req]
  (if (= (:username args) (:password args)) ; database call goes here
    {:you-must-be-twins "do you have any requests?"}))

;; minimum requirements
(register-command :login {:username s/Str :password s/Str} ::i-know-you)

(defn start []
  (build-system sys {:http/handlers {:event-stream-handler events
                                     :ws-handler ws-handler
                                     :ws-onclose ws-onclose}
                                        ;(bones.http.auth/gen-secret)
                     :httt/auth {:secret "CypOW2ZYqvB42ahTI9GdXZ5v4sphlwdC"}
                     ;; :http/service {:port 3000}})
                     :http/service {:port 8080}})
  (start-system sys))

(defn stop []
  (stop-system sys))

(comment
;; curl "localhost:3000/api/events" --cookie "bones-session=w0Sr6aFcdwmtcnbmuxfmiuYgtmo0VUGylXKtdpVpv%2B7c5y3FVjWYdJ21cgv2g%2BlNkgdyYvE%2BvpqQWHUW%2BhcobzDOqd8M6SIWVWtp%2FXP9wG4%3D--fTYQgX1%2BSQkz2Q%2Bc5qaEhYGPMlBU2hdcdSy5bcs%2B2S0%3D" -v
  ;; curl "localhost:3000/api/ws" --cookie "bones-session=w0Sr6aFcdwmtcnbmuxfmiuYgtmo0VUGylXKtdpVpv%2B7c5y3FVjWYdJ21cgv2g%2BlNkgdyYvE%2BvpqQWHUW%2BhcobzDOqd8M6SIWVWtp%2FXP9wG4%3D--fTYQgX1%2BSQkz2Q%2Bc5qaEhYGPMlBU2hdcdSy5bcs%2B2S0%3D" -v  -H "Connection: Upgrade" -H "Upgrade: websocket"

  ;; curl "localhost:3000/api/ws" -v -H "Authorization: Token $TOKEN" -H "Connection: Upgrade" -H "Upgrade: websocket"
(println "hi")
(start)
(stop)
  )
