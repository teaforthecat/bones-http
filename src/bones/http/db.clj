(ns bones.http.db
  "cheap in-memory database for development and testing environments"
  (:require [datascript.core :as d]
            [buddy.hashers :as hashers]))


(defonce algorithm {:alg :bcrypt+blake2b-512})
(defonce schema {})
(defonce conn (d/create-conn schema))

(defn create-user [{:keys [username password roles]}]
  (let [enc-pass (hashers/encrypt password algorithm)
        new-user {:db/id -1
                  :username username
                  :password enc-pass
                  :roles (or roles [:new-user])}]
    (d/transact! conn [ new-user ])))

(defn find-user [username]
  (let [db @conn]
    (->> username
         (d/q '[:find ?id ?roles ?password
                 :in $ ?username
                 :where [?id :username ?username]
                 [?id :roles ?roles]
                 [?id :password ?password]
                 ]
               db
               username)
         first
         (zipmap [:id :roles :password]))))

(defn authenticate-user [username password]
  (let [user (find-user username)
        pass (hashers/check password (:password user))]
    (if pass
      (dissoc user :password))))
