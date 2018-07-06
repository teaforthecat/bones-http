(ns bones.http
  (:require [com.stuartsierra.component :as component]
            [bones.http.auth :as auth]
            [bones.http.handlers :as handlers]
            [bones.http.commands]
            [bones.http.service :as service]
            [clojure.spec.alpha :as s]))

(s/def ::command-vec (s/cat :name keyword?  :spec keyword? :handler symbol?))
;; the inner spec here resets reg-op context which provides the dive into the nested vector
(s/def ::commands (s/* (s/spec ::command-vec)))
;; query is a single vector or tuple of spec and handler function
(s/def ::query (s/cat :spec keyword? :handler (s/or :fn fn? :symbol symbol?)))
;; same shape as query
(s/def ::login ::query)

;; not sure if these should be required
(s/def ::handlers (s/keys :opt-un [::commands ::query ::login]))

(s/def ::conf (s/keys :opt-un [::handlers]))

(defn throw-spec
  "a slightly improved report of the problem"
  [spec value]
  (let [errors (s/explain-data spec value)]
    (throw (ex-info (str spec "\n value: \n" value "\n does not conform to: \n" spec)
                    {
                     :spec spec
                     :paths (->> errors :clojure.spec.alpha/problems (map :path))
                     :description (s/describe spec)
                     :error errors
                     :value value
                     }))))

(defn validate [conf]
  (if (s/valid? ::conf conf)
    conf
    (throw-spec ::conf conf)))

(comment
  (s/describe ::command)
  (s/describe ::commands)
  (s/explain-data ::conf {::handlers {:commands [[:a :b :c]]}})
  (s/assert ::conf {::handlers {:commands [[:a 123]]}})

  )

(defn build-system [sys conf]
  ;; simplify the api even more by not requiring a system-map from the user
  {:pre [(instance? clojure.lang.Atom sys)
         (instance? clojure.lang.Associative conf)]}
    (swap! sys #(-> (apply component/system-map (reduce concat %)) ;; if already a system-map break apart and put back together
                    (assoc :conf (validate conf))
                    (assoc :shield (component/using (auth/map->Shield {}) [:conf]) )
                    (assoc :routes (component/using (handlers/map->App {})
                                                    [:conf :shield]))
                    ;; todo rename to keys to match records
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
