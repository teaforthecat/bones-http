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
takes two arguments. The first is a schema-defined map, the second is also a
map, and contains identification information gathered from the request.

Here is a contrived example:
```clojure
(defn new-widget [args auth-info request]
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

We'll give it a name matching the function name, and put it all together into an
array of properly formatted commands.

```clojure
;               name         schema         function
(def commands [[:new-widget widget-schema 'new-widget]])
```

When bones receives a command it will execute the function of the command
matching the name given, and pass the args of the request as the first
parameter. The response body will consist of the return value of this function.

_only edn is accepted currently_
_"/api" is the default mount point and can be configured ..._
```sh
curl localhost:8080/api/command -X POST -d '{:command :new-widget :args {:width 3 :height 5}' \
  -H "Content-Type: application/edn" plus authentication...
```

You don't want everyone on the web to write to your database so let's add
authentication. This will allow us to be confident in the identity of the person making the
request.

Let say we have a function takes an email address and a password and returns a user-id.

We're returning "auth-info" here explicitly to illustrate the link between this
data, and the second parameter of the "new-widget" function above.
```clojure
(defn login [args request]
  (let [auth-info (find-user (:email args) (:password args))]
    auth-info))
```

If "find-user" returns, let's say, "{:user-id 123}", then "{:user-id 123}" will
be the second parameter to all of the command handlers.

The response contains a "Set-Cookie" header for the browser. This cookie's value
is the "auth-info" data encoded with a secret. The encoded data is also provided
in the response as "token". The same encoded data can be used to make api
requests and to keep a browser session.

Take note of two important things here. Keep the "auth-info" small, there is a
limit to the cookie size and that will break authentication. Keep your secret
safe. You'll want to put it into a configuration file or environment variable,
which we'll cover later...

You can generate a unique random secret with `bones.http.auth/generate-secret`

The browser will keep the session for you. To logout of the session, make a
request to the logout resource, which will clear the cookie with another
"Set-Cookie" header.

To make authenticated api requests use a header called "Authorization" with a
value of the encoded data prefixed with "Token " like this: `Authorization:
"Token WYdJ21cgv2g-2BlNkgdyYv.."`


_The "Authorization" header has a precedent in basic authentication, and Buddy
 uses the "Token " prefix in the JWE backend._

## Resources
if mount_point is the default of "/api"

- POST /api/command
- GET /api/query
- GET /api/events
- GET /api/login
- GET /api/logout





## License

Copyright © 2016 Chris Thompson

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
