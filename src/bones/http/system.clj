(ns bones.http.system
  (:require [bones.conf :as conf]
            [com.stuartsierra.component :as component]
            [aleph.http :refer [start-server]]
            [schema.core :as s]))


(s/defschema HttpConf
  {:http/handler-fn s/Any
   :http/port s/Int
   s/Any s/Any})


(defrecord HTTP [conf]
  component/Lifecycle
  (start [cmp]
    (s/validate HttpConf conf)
    (if (:server cmp)
      (do
        (println "server is running on port: " (:port cmp))
        cmp)
      (let [{:keys [:http/handler-fn :http/port]} conf
            ;; builds a handler each start
            server (start-server (handler-fn conf) {:port port})]
        (-> cmp
         (assoc :server server)
         ;; in case port is nil, get real port
         (assoc :port (aleph.netty/port server))))))
  (stop [cmp] ;; todo add force option
    (if-let [server (:server cmp)]
      (do
        (.close server) ;; this will hang if connections exist
        (dissoc cmp :server))
      cmp)))

(defn system [config]
  (atom (component/system-map
         :conf (conf/map->Conf (assoc config
                                      :sticky-keys (keys config)))
         :http (component/using
                (map->HTTP {})
                [:conf]))))

(defn start-system [system & components]
  (swap! system component/update-system components component/start))

(defn stop-system [system & components]
  (swap! system component/update-system-reverse components component/stop))
