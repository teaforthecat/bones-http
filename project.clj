(defproject bones/http "0.3.0"
  :description "A spec-driven CQRS implementation built on Yada"
  :url "https://github.com/teaforthecat/bones-http"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha14"]
                 [com.stuartsierra/component "0.3.2"]
                 [buddy/buddy-auth "1.4.1"]
                 [buddy/buddy-hashers "1.2.0"]
                 [aleph "0.4.2-alpha12"]
                 [yada/lean "1.2.1"]
                 [bidi "2.0.16"]
                 [com.taoensso/timbre "4.8.0"]
                 ]

  :profiles {:dev {:dependencies [[peridot "0.4.4"]]}}

  )
