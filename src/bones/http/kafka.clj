(ns bones.http.kafka
  (:require [bones.http.serializer :refer [serialize deserialize]]
            [com.stuartsierra.component :as component]
            ;; [taoensso.timbre :as log]
            ;; [clojure.core.async :as a]
            ;; [byte-streams :as bs]
            [clj-kafka.core :refer [with-resource]]
            [clj-kafka.zk :as zk]
            [clj-kafka.consumer.zk :as zkc]
            [clj-kafka.new.producer :as nkp]

            [manifold.deferred :as d]
            [manifold.stream :as ms]
            [manifold.bus :as mb] ))

(defprotocol Produce
  (produce [cmp topic key data]))

(defrecord Producer [bootsrap-servers serializer]
  component/Lifecycle
  (start [cmp]
    (if-let [producer (:producer cmp)]
      (.close producer))
    (assoc cmp :producer (nkp/producer {"bootstrap.servers" bootsrap-servers}
                                (nkp/byte-array-serializer)
                                (nkp/byte-array-serializer))))
  (stop [cmp]
    (if-let [producer (:producer cmp)]
      (.close producer))
    (dissoc cmp :producer))
  Produce
  (produce [cmp topic key data]
    (if (:producer cmp)
      (let [bytes (serializer data)
            key-bytes (.getBytes (str key)) ;; this is all necessary. I know.
            record (nkp/record topic key-bytes bytes)]
        (nkp/send (:producer cmp) record ))
      (println "no producer"))))



(defn produce [thing])


(comment

(def p (atom (Producer. "127.0.0.1:9092" serialize)))

(reset! p (.start @p))
;; (reset! p (.stop @p))
(:producer @p)
@(.produce @p "bones.jobs-test..dummy-x-input" "123" {:message {:a 1 :b 2}})

(def b (ms/stream))

(ms/closed? b) ;;=> false

(def csmr (zkc/consumer {"zookeeper.connect" "127.0.0.1:2181"
                         "group.id" "1"
                         "auto.offset.reset" "smallest"}))

(def a (ms/->source (zkc/messages csmr "bones.jobs-test..dummy-x-output")))

;; this is a very big deal right here
(ms/on-closed b #(.shutdown csmr) )

(ms/drained? a) ;;=> false

(ms/connect
 a
 b
 ;; when the sink closes the source will close
 ;; this means when the websocket connection closes, .hasNext will cease being called on the consumer
 ;; resulting in no lost messages (or close as can be)
 ;; the kafka consumer will .shutdown in the on-closed callback on the sink/websocket
 {:upstream? true
  :description "connect a websocket to a kafka consumer"})

(ms/consume println b) ;; zomg look on in amazement
;; a stream can be consumed multiple times - great for debugging
;;(ms/consume println b) ;; zomg look on in amazement

(ms/closed? b) ;;=> false
(ms/drained? a) ;;=> false

(ms/close! b) ;; this will shutdown the consumer and, when restarted, it will pickup where it left off. fabulous.
(ms/closed? b) ;;=> true



(defrecord Consumer [conf]
  component/Lifecycle
  (start [cmp]
    (if (:consumer cmp)
      (do
        (log/info "consumer exists")
        cmp)
      (-> cmp
          (assoc :consumer (zkc/consumer {"zookeeper.connect" (:zookeeper-addr conf)
                                          "group.id" "1"
                                          "auto.offset.reset" "smallest"})))))
  (stop [cmp]
    (if-let [consumer (:consumer cmp)]
      (do
        (.shutdown consumer)
        (dissoc cmp :consumer))
      (do
        (log/info "no consumer")
        cmp))))

)
