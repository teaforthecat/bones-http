# bones.http


bones.http is a CQRS implementation built on
[yada](https://github.com/juxt/yada). It offers authentication with Buddy and
validation with clojure.spec.alpha. It has the goal of a slim API to make getting
started as easy as possible. For a quick implementation example see
[dev/user.clj](dev/user.clj).

[![Build Status](https://travis-ci.org/teaforthecat/bones-http.svg?branch=master)](https://travis-ci.org/teaforthecat/bones-http)

## Commands

Lets say we have a function that writes data to a database, and we want to
connect it to the web.

We can do this by creating a bones command handler. This is a function that
takes three arguments. The first is a clojure.spec.alpha-defined map. The second is also a
map, and contains identification information gathered from the request.

Here is a contrived example:
```clojure
(defn new-widget [args auth-info request]
  (let [{:keys [width height]} args
        {:keys [user-id]} auth-info]
    (if (insert-into "widgets" width height user-id)
      "yay!"
      (throw (ex-info "woah" {:status 422 :message "woah there"})))))
```

We'd like to be confident that the arguments received are what we want and
expect.  We accomplish this by providing a spec for this command handler.

```clojure
(require '[clojure.spec.alpha :as s])
(s/def ::width integer?)
(s/def ::height integer?)
(s/def ::widget (s/keys :req-un [::width ::height]))
```

We'll give it a name, and put it all together into a list of properly
formatted commands.

```clojure
;               name         spec         function
(def commands [[:new-widget ::widget 'new-widget]])
```

Then we'll start a web server using that variable, but this isn't a complete
system yet. 

```clojure
(require '[bones.http :as http])

(def sys (atom {}))
(http/build-system sys {:http/handlers {:commands commands})
(http/start sys)
```

When bones receives a command it will execute the function of the command
matching the name given, and pass the args of the request as the first
parameter. The response body will consist of the return value of this function.

_note: only edn is accepted currently_

_note: "/api" is the default mount point and can be configured ..._

Almost ready, test this out and see that this will result in a 401 response:

```sh
curl localhost:3000/api/command  \
  -X POST \
  -d '{:command :new-widget :args {:width 3 :height 5}' \
  -H "Content-Type: application/edn"
```

For testing, here is a made up secret and a token
derived from that secret, which will authenticate.

_note: you'll want to generate your own secret; see below_

```clojure
(require '[bones.http :as http])

(http/stop sys) ;; if started
(http/build-system sys {:http/handlers {:commands commands}
                        :http/auth {:secret "CypOW2ZYqvB42ahTI9GdXZ5v4sphlwdC"})
(http/start sys)
```

```sh
curl localhost:3000/api/command  \
  -X POST \
  -d '{:command :new-widget :args {:width 3 :height 5}' \
  -H "Content-Type: application/edn" \
  -H "Authorization: Token eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1MiLCJlbmMiOiJBMTI4R0NNIn0.HVUpeQY0SgjN5KGXXU7zQnkZhacEFm1d.WZq2kqGbQmJ5HvzA.ZbkbjUimjPH-KCCPRQ.qoJeedBfruV59vOqUdpnGA"
```

## Authentication

You don't want everyone on the web to write to your database so let's add
authentication. This will allow us to be confident in the identity of the person
making the request.

Let say we have a function that takes an email address and a password and
returns a user-id.

We're returning "auth-info" here explicitly to illustrate the link between this
data, and the second parameter of the "new-widget" function above.

```clojure
(defn login [args request]
  (let [auth-info (find-user (:email args) (:password args))]
    auth-info))
```

If "find-user" returns, let's say, "{:user-id 123}", then "{:user-id 123}" will
be the second parameter to all of the command handlers.

If the "login" function returns nil, the login attempt is taken as invalid and
an error response is returned.

A valid login response contains a "Set-Cookie" header for the browser. This
cookie's value is the "auth-info" data encoded with a secret. This encoded data
is also provided in the response as "token". The same encoded data can be used
to make api requests and to keep a browser session.

Take note of two important things here. Keep the "auth-info" small, there is a
limit to the cookie size. Keep your secret safe. You'll want to put it into a
configuration file or environment variable.

You can generate a unique random secret with `bones.http.auth/gen-secret`

The browser will keep the session for you. To logout of the session, make a
request to the logout resource, which will clear the cookie with another
"Set-Cookie" header.

To make authenticated API requests use a header called "Authorization" with a
value of the encoded data prefixed with "Token " like this: `"Authorization:
 Token WYdJ21cgv2g-2BlNkgdyYv.."`

The response of the "login" function above could be altered to add "share" data
to the response, along with the token. This is useful for sharing groups or
roles the user is in. The share data is sent via meta data: 

```clojure
(defn login [args request]
  (let [auth-info (find-user (:email args) (:password args))]
     ;; assuming find-user returns a map with key :role
     ^{:share [:role]}} auth-info))
```

_The "Authorization" header has a precedent in basic authentication, and Buddy
uses the "Token " prefix in the JWE/[JWT](https://jwt.io/) backend._

## Resources
If mount_point is the default ("/api")

- POST /api/command
- GET /api/query
- GET /api/events
- GET /api/login
- ANY /api/logout
- WebSocket /api/ws

## Query

Let's say we have a function that gets data from a database. We'll connect this
function to the web by creating a query handler. This is a function that takes
three arguments, similar to the command handler. The first parameter will be
the parsed query string from the web request, conformed to a spec. The second
is the auth-info, and the third is the whole request.

```clojure
(defn list-widgets [args auth-info request]
  (let [results (query-database (:q args))]
    (if results
      (render results)
      :no-results)))
```

We'll add this function to the configuration as well (see below), though there
is only one query handler.

```clojure
(s/def ::query-q map?)

;            spec     function
(def query [::query-q query-handler])
```

## SSE Event Stream

There is a protocol that all the browsers have implemented to maintain a
persistent connection to the web server. We can use this to push data in real
time to the browser. It is really simple to set up - if you have some experience
with streaming data or Clojure's lazy sequences. Here are some things you need
to know about a bones event-handler: There can be only one event-stream handler.
It does not have a spec attached to it like the other handlers, and it must
return a stream, or anything that Manifold can turn into a source.

```clojure
(defn event-stream-handler [request auth-info]
  (range 10))
```

The stream can consist of anything and it will be sent as "data: " in the SSE
protocol. If the message is a map with the special keys ":event" or ":id", they
will be added to the sent event. In this case three keys should be provided such
as:

```clojure
(defn event-stream-handler [request auth-info]
  (let [source (manifold.stream/->source (range 10))]
    (manifold.stream/transform
       source
       #({:event "test" :id % :data (* 2 %)}))))
```
_note: if using [bones.client](https://github.com/teaforthecat/bones-client), event types are not supported_

## WebSocket

Another way to consume an event-stream is via a WebSocket. You can use the same
function to serve both SSE and WebSocket connections. The WebSocket connection
will only serve the `:data` attribute. The `:event` and `:id` will be dropped
because those features aren't in the protocol. 

## Configuration

Normally we put our connections into a single global atom so we can control the
life cycle of the connections easily. Here, we're going to put all our
configuration for the bones system in this atom as well. Well bring it all
together like this:

```clojure
(require '[bones.http :as http])
(def sys (atom {}))
(def commands [[:new-widget ::widget 'new-widget]])
(def query [::query-q query-handler])
(def event-stream event-stream-handler)
(http/build-system sys {:http/handlers {:commands commands
                                        :query query
                                        :login login
                                        :event-stream event-stream}
                        :http/auth {:secret "keepitsecret"}})
(http/start sys)
```

The `http/start` function will ensure that all of the components are started in
the right order and all the dependencies are met.

There is also an `http/stop` function. Use these while developing in the repl.




## License

Copyright © 2016 Chris Thompson

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
