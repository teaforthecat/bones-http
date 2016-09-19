# bones.http


bones.http is a CQRS implementation built on
[yada](https://github.com/juxt/yada). It offers authentication with Buddy and
validation with Prismatic Schema. It has the goal of a slim API to make getting
started as easy as possible.

[![Build Status](https://travis-ci.org/teaforthecat/bones.http.svg?branch=master)](https://travis-ci.org/teaforthecat/bones.http)

## Commands

In the beginning there was an atom.

```clojure
(def sys (atom {}))
```

Lets say we have a function that writes data to a database, and we want to
connect it to the web.

We can do this by creating a bones command handler. This is a function that
takes two arguments, the first is a schema-defined map. The second is also a
map, and contains identification information gathered from the request.

Here is a contrived example:
```clojure
(defn new-widget [args auth-info]
  (let [{:keys [width height]} args
        {:keys [user-id]} auth-info]
    (if (insert-into "widgets" width height user-id)
      :success
      :error)))
```

We'd like to be confident that the arguments received are what we want and
expect.  We accomplish this by providing a schema for this command handler.

```clojure
(require '[schema.core :as s])
(defn widget-schema {:width s/Int :height s/Int })
```

Well give it a name matching the function name, and put it all together into an
array of properly formatted commands.

```clojure
;               name         schema         function
(def commands [[:new-widget widget-schema 'new-widget]])
```

When bones receives a command it will execute the function of the command
matching the name given, and pass the args of the request as the first
parameter. The response body will consist of the return value of this function.

_only edn is accepted currently_
```sh
curl localhost:8080/api/command -X POST -d '{:command :new-widget :args {:width 3 :height 5}' \
  -H "Content-Type: application/edn"
```

...more to come

## License

Copyright © 2016 Chris Thompson

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
