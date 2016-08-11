# bones.http


bones.http is a CQRS implementation built on Pedestal. It offers authentication
with Buddy and validation with Prismatic Schema. It has the goal of a slim API
to make getting started as easy as possible.

[![Build Status](https://travis-ci.org/teaforthecat/bones.http.svg?branch=master)](https://travis-ci.org/teaforthecat/bones.http)

## Usage

In the beginning there was an atom.

```clojure
(def sys (atom {}))
```

Then the atom, with an empty configuration, grew into a system that had a web
server in it.

```clojure
(http/build-system sys {})
```

Once it was started, the system went on to reach out to the web.

```clojure
(http/start-system sys)
```

The web had a lot to say

```sh
curl localhost:8080/api/command -X POST -D '{:twitter "words, some news"}' -H
"Content-Type: application/edn"
```

But the system did not even

```sh
401 Not Authorized
```

The system learned of people on the web

```clojure
(defn i-know-you [args req]
  (if (= (:username args) (:password args))
    {:you-must-be-twins "do you have any requests?"}))
(http/register-command :login {:username s/Str :password s/Str} ::i-know-you)
```

The web replied

```sh
curl localhost:8080/api/command -D '{:todo {:status "new" :text "travel to
distant lands"}}'
```

The system was shy

```sh
400 command not found
```

The system tried to understand

```clojure
(defn todo [args req]
  (record-todo args :user-info (:identity req)))
(http/register-command :todo {:status (s/enum "new" "done") :text s/Str})
```

The web was persistant

```sh
curl localhost:8080/api/command -D '{:command :todo :args {:status "new" :text "travel to
distant lands"}}'
```

Eventually the system came around

```sh
200
```

And the system was deployed to distant lands
...

And reported back various findings

```clojure
(defn report-findings [args req]
  (find-findings args))
(http/register-query-handler ::report-findings {:place s/Str (s/optional-key :todos) (s/enum
"new" "done")})
```

The web had many questions

```sh
curl localhost:8080/api/query?place=canada&todos=new -X GET
```

And learned many things

```sh
200 [{:todos ["fishing"]}]
```

## License

Copyright © 2016 Chris Thompson

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
