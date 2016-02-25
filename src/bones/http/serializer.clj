(ns bones.http.serializer
  (:require [cognitect.transit :as transit])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))


(def default-data-format :msgpack)

(defn transit-encoder [data-format]
  (fn [data]
    (.toByteArray
     (let [buf (ByteArrayOutputStream. 4096)
           writer (transit/writer buf data-format)]
       (transit/write writer data)
       buf))))

(defn transit-decoder [data-format]
  (fn [buffer]
    (transit/read (transit/reader (ByteArrayInputStream. buffer) data-format))))

(comment
  ((transit-decoder :json)
   ((transit-encoder :json) "hello"))
)

;; TODO make configurable somehow
;; this var must resolve to a function, used in bones.jobs.build.clj
(def serialize   (transit-encoder default-data-format))
(def deserialize (transit-decoder default-data-format))
