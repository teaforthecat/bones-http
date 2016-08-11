(ns bones.http.redis
  (:require [com.stuartsierra.component :as component]
            [taoensso.carmine :as car]
            [manifold.stream :as ms]
            [bones.system :refer [sys]]))

(defrecord Redis [conf spec channel-prefix]
  component/Lifecycle
  (start [cmp]
    (let [override #(or (get-in (:conf cmp) [:redis %]) (% cmp))]
      (-> cmp
          (assoc :spec (override :spec))
          (assoc :channel-prefix (override :channel-prefix))))))

;; for a single namespace
(def close-listener car/close-listener)

;; todo publish ...
(defn publish [user-id message]
  (let [{:keys [spec chan-prefix]} (:redis @sys)
        channel (str chan-prefix user-id)]
    (car/wcar {:spec spec}
               (car/publish channel message)) ))

(defn message-handler
  "returns a function that destructures a redis message and sends the good stuff
  to the stream"
  [stream]
  (fn [[type channel message]]
    (if (= type "message")
      (ms/put! stream message))))

(defn subscribe [user-id stream]
  (let [{:keys [spec chan-prefix]} (:redis @sys)
        channel (str chan-prefix user-id)]
    (car/with-new-pubsub-listener {:spec spec}
      {channel (message-handler stream)}
      (car/subscribe channel))))
