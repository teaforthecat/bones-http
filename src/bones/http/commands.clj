(ns bones.http.commands
  (:require [schema.core :as s]
            [schema.experimental.abstract-map :as abstract-map]
            [clojure.string :as string]))

(s/defschema Command
  (abstract-map/abstract-map-schema
   :command
   {:args {s/Keyword s/Any}}))

;; a default is not needed due to the `check-command-exists' interceptor
(defmulti command :command)

(defn add-command
  "ensure a unique command is created based on a name spaced keyword"
  [command-name schema]
  (let [varname (-> command-name
                    str
                    (string/replace "/" "-")
                    (string/replace "." "-")
                    (subs 1)
                    (str "-schema")
                    (symbol))]
    (abstract-map/extend-schema! Command
                                 {:args schema}
                                 varname
                                 [command-name])))

(defn resolve-command [command-name]
  (if (keyword? command-name)
    (let [nmspc (namespace command-name)
          f     (name command-name)]
      (if nmspc
        (ns-resolve (symbol nmspc) (symbol f))
        (resolve (symbol f))))
    (if (fn? command-name)
      command-name)))

(defn register-command
  "the command can have the same name of the function (implicit) - it must also
  have the same namespace as the call of this `register-command' function, or a third
  argument can be given that resolves to a function in another namespace, that
  way the command-name can be different from the function name if desired.

    * resolves a keyword to a function
    * adds a method to `command'
    * adds a schema to `Command'"
  ([command-name schema]
   (register-command command-name schema command-name))
  ([command-name schema explicit-handler]
   (let [command-handler (resolve-command explicit-handler)]
     (if (nil? command-handler)
       (throw (ex-info (str "could not resolve command to a function: "
                            ;; in case the ex-data isn't shown
                            (pr-str explicit-handler))
                       {:command explicit-handler})))
     (add-command command-name schema)
     (defmethod command command-name [command req]
       (command-handler (:args command) req)))))

(defn register-commands [commands]
  (map (partial apply register-command) commands))
