(defproject bones.http "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [bones.conf "0.1.4"]
                 [aleph "0.4.1-beta3"]
                 [yada "1.1.0-20160219.181822-27"]
                 [buddy/buddy-auth "0.8.1"]
                 [buddy/buddy-hashers "0.9.1"]
                 [clj-kafka "0.3.4"]]

  :profiles {:test
             {:dependencies [[matcha "0.1.0"]
                             [ring-mock "0.1.5"]
                             [peridot "0.4.2"]]}
             :dev
             {:dependencies [[datascript "0.15.0"]]}})
