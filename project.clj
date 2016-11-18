(defproject bones/http "0.2.2"
  :description "A CQRS implementation built on Pedestal"
  :url "https://github.com/teaforthecat/bones-http"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [io.pedestal/pedestal.service "0.5.0"]
                 [io.pedestal/pedestal.jetty "0.5.0"]
                 [aleph "0.4.1"]
                 [metosin/compojure-api "1.1.8"]
                 [buddy/buddy-auth "0.8.1"]
                 [buddy/buddy-hashers "0.9.1"]
                 [prismatic/schema "1.1.2"]
                 [com.stuartsierra/component "0.3.1"]
                 [yada "1.1.33"]
                 [bidi "2.0.10"]
                 [ring/ring-mock "0.3.0"]
                 ]

  :profiles {:dev {:dependencies [[peridot "0.4.4"]]}}

  )
