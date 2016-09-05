(ns bones.http.service
  (:require [com.stuartsierra.component :as component]
            [io.pedestal.http :as http]
            [io.pedestal.http :as server]
            [io.pedestal.http.jetty.websockets :as ws]))

(defn service
  "configures web server with routes,static files,websockets"
  [routes conf]
  (let []
    {:env :prod
     ::server/join? (or (:join? conf) false) ;; block caller?
     ::http/routes (:routes routes)
     ::http/resource-path (or (:resource-path conf) "/public")
     ::http/type :jetty
     ;; ::http/allowed-origins {:allowed-origins #(some #{"http://localhost:8080"
     ;;                                                   "http://localhost:3449"}
     ;;                                                [%])
     ;;                         :creds true}
     ::http/port (or (:port conf) 8080)
     ::http/container-options {:context-configurator #(ws/add-ws-endpoints % (:ws-paths routes))
                               :h2c? false ;;?
                               :h2? false
                               :ssl? false}}))

(defrecord Server [routes conf]
  component/Lifecycle
  (start [cmp]
    (let [config (get-in cmp [:conf :http/service])
          service-map (service (get-in cmp [:routes]) config)
          service (server/create-server service-map)]
      (assoc cmp :server (server/start service))))
  (stop [cmp]
    (update cmp :server server/stop)))

(defn start-system [system & components]
  (swap! system component/update-system components component/start))

(defn stop-system [system & components]
  (swap! system component/update-system-reverse components component/stop))

