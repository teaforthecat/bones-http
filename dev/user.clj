(ns user
  (use [bones.http.core]))

(def sys (atom {}))

(defn start []
  (build-system sys {})
  (start-system sys))

(defn stop []
  (stop-system sys))
