(ns bones.http.commands
  (:require [clojure.spec :as s]
            [clojure.string :as string]))


(s/def ::args map?)
(s/def ::command keyword?)
(s/def ::body (s/keys :req-un [::command ::args]))

;; a default is not needed due to the `check-command-exists' interceptor
;; get the command on the first argument
(defmulti command (fn [cmd auth-info req] (:command cmd)))

(def command-map (atom {}))

(defn add-command
  "add command by name to the one command-map, used to find the spec on request"
  [command-name spec]
  (swap! command-map assoc command-name {:spec spec}))

(defn resolve-command [command-name]
  (cond
    (keyword? command-name)
      (let [nmspc (namespace command-name)
            f     (name command-name)]
        (if nmspc
          (ns-resolve (symbol nmspc) (symbol f))
          (resolve (symbol f))))
    (symbol? command-name)
      (resolve command-name)
    (fn? command-name)
      command-name))

(defn check [body]
  (if (s/valid? ::body body)
    (let [cmd (:command body)
          command-spec (get-in @command-map [cmd :spec])]
      (if command-spec
        (s/explain-data command-spec (:args body))
        (str cmd " not found in registered commands")))))

(defn register-command
  "the command can have the same name of the function (implicit) - it must also
  have the same namespace as the call of this `register-command' function, or a third
  argument can be given that resolves to a function in another namespace, that
  way the command-name can be different from the function name if desired.

    * resolves a keyword to a function
    * adds a method to `command'
    * adds a spec to `Command'"
  ([command-name spec]
   (register-command command-name spec command-name))
  ([command-name spec explicit-handler]
   (let [command-handler (resolve-command explicit-handler)]
     (if (nil? command-handler)
       (throw (ex-info (str "could not resolve command to a function: "
                            ;; in case the ex-data isn't shown
                            (pr-str explicit-handler))
                       {:command explicit-handler})))
     (add-command command-name spec)
     (defmethod command command-name [command auth-info req]
       (command-handler (:args command) auth-info req)))))


(defn register-commands [commands]
  (doseq [command-vec commands]
    (apply register-command command-vec)))
