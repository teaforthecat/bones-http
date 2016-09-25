(ns bones.http.core
  (:require [com.stuartsierra.component :as component]
            [bones.http.auth :as auth]
            [bones.http.handlers :as handlers]
            [bones.http.commands]
            [bones.http.service :as service]
            [schema.core :as s]))

(defn validate [conf]
  (let [handlers (:http/handlers conf)
        {:keys [commands query login]} handlers
        name-schema (s/one s/Keyword :name)
        schema-schema (s/one (s/protocol s/Schema) :schema)
        ;; todo: figure out how to check for an fn; this handler schema is broken
        handler-schema (s/cond-pre s/Keyword s/Symbol s/Any)]
    ;; commands: [[name schema handler]]
    (s/validate [[name-schema
                  schema-schema
                  (s/maybe handler-schema)]]
                commands)
    ;; query: [schema handler]
    (s/validate (s/maybe  [schema-schema
                           handler-schema])
                query)
    ;; login: [schema handler]
    (s/validate (s/maybe [schema-schema
                          handler-schema])
                login)


    conf))

(defn build-system [sys conf]
  ;; simplify the api even more by not requiring a system-map from the user
  {:pre [(instance? clojure.lang.Atom sys)
         (instance? clojure.lang.Associative conf)]}
    (swap! sys #(-> (apply component/system-map (reduce concat %)) ;; if already a system-map break apart and put back together
                    (assoc :conf (validate conf))
                    (assoc :shield (component/using (auth/map->Shield {}) [:conf]) )
                    (assoc :routes (component/using (handlers/map->App {})
                                                    [:conf :shield]))
                    (assoc :http   (component/using (service/map->Server {}) [:conf :routes])))))

(defn start-system [system & components]
  (swap! system component/update-system components component/start))

(defn stop-system [system & components]
  (swap! system component/update-system-reverse components component/stop))

(defn start [sys]
  ;; order matters here (even though it shouldn't?)
  ;; conf<-shield<-routes<-http
  (start-system sys :conf :shield :routes :http))

(defn stop [sys]
  ;; http is the only component that needs to be stopped
  (stop-system sys :http))
