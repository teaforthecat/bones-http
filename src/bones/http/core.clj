(ns bones.http.core
  (:require [com.stuartsierra.component :as component]
            [bones.http.auth :as auth]
            [bones.http.handlers :as handlers]
            [bones.http.yada :as y]
            [bones.http.service :as service]))

(defn build-system [sys conf]
  (swap! sys #(-> %
                  (assoc :conf conf)
                  (assoc :shield (component/using (auth/map->Shield {}) [:conf]) )
                  (assoc :routes (component/using (y/map->App {}) [:conf :shield]))
                  (assoc :http   (component/using (service/map->Server {}) [:conf :routes])))))

(defn start-system [system & components]
  (swap! system component/update-system components component/start))

(defn stop-system [system & components]
  (swap! system component/update-system-reverse components component/stop))

(defn start [sys]
  ;; order matters here, conf<-shield<-routes<-http
  (start-system sys :conf :shield :routes :http))

(defn stop [sys]
  ;; http is the only component that needs to be stopped
  (stop-system sys :http))
