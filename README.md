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
(require '[bones.http.core :as http])
(http/build-system sys {})
```

Once it was started, the system went on to reach out to the web.

```clojure
(http/start-system sys)
```

The web had a lot to say.

```sh
curl localhost:8080/api/command -X POST -d '{:twitter "words, some news"}' -H
"Content-Type: application/edn"
```

But the system did not even.

```sh
HTTP/1.1 401 Unauthorized
```

The system learned of people on the web.

```clojure
(require '[schema.core :as s]
(defn i-know-you [args req]
  (if (= (:username args) (:password args)) ; database call goes here
    {:you-must-be-twins "do you have any requests?"}))
(http/register-command :login {:username s/Str :password s/Str} ::i-know-you)
```

The web complied.

```sh
curl localhost:8080/api/login -X POST -d '{:command :login :args {:username "same" :password "same"}}' -H "Content-Type: application/edn" -i
```

And they became friends for a maximum of one year.

_Notice both a session cookie and token are returned. This response can be used by both
the browser and api clients._
```sh

HTTP/1.1 200 OK
Date: Fri, 12 Aug 2016 14:18:12 GMT
Strict-Transport-Security: max-age=31536000; includeSubdomains
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
X-XSS-Protection: 1; mode=block
Content-Type: application/edn
Set-Cookie: bones-session=pVMJ7uknf%2B5Hbpr2sj1cXAv%2FUw7L4YKhrnGZ0QfPmXh5oc18%2Ba%2FMM1yV7v0NfYLtM22gH9wEjqoLULcGNO%2FEILg%2FiOd6CNJyHlVJueqQPQs%3D--ByFS0EKRdBtOAhUkn6MbBfSOW4jBgmc39vRMHeTo3uI%3D;Path=/;Max-Age=31536000
Transfer-Encoding: chunked
Server: Jetty(9.3.8.v20160314)

{:token "eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1MiLCJlbmMiOiJBMTI4R0NNIn0.vOdZGyjQqsXL89x4StgQuyk28jPaJ-ji.3DcYJLZUbkXvXzPk.jvfS1FeuL4DkNDJIHvQEl8rvSzKKV7US_8Zqybda_cX5a-CpXMGOk_DX4c2ppXfPSA.za5U1C_HBonfezfe4dE2vg"}
```

```sh
export TOKEN="eyJhbGciOiJBMjU2S1ciLCJ0eXAiOiJKV1MiLCJlbmMiOiJBMTI4R0NNIn0.vOdZGyjQqsXL89x4StgQuyk28jPaJ-ji.3DcYJLZUbkXvXzPk.jvfS1FeuL4DkNDJIHvQEl8rvSzKKV7US_8Zqybda_cX5a-CpXMGOk_DX4c2ppXfPSA.za5U1C_HBonfezfe4dE2vg"
```

The system tried to understand what the web wanted.

```clojure
(def record-todo #'identity) ; database call goes here
(defn todo [args req]
  (record-todo args :user-info (:identity req)))
(http/register-command :todo {:status (s/enum "new" "done") :text s/Str :place s/Str})
```

The web gave the system a command.

```sh
curl localhost:8080/api/command -d '{:command :todo :args {:status "new" :text "travel to distant lands"}}' -H "Authorization: Token $TOKEN" -H "Content-Type: application/edn"
```

But the system needed more information.

```sh
HTTP/1.1 400 Bad Request
{:message "args not valid", :data {:args {:place missing-required-key}}}
```

The web was persistant, and gave the missing information.

```sh
curl localhost:8080/api/command -d '{:command :todo :args {:status "new" :text "travel to
distant lands" :place "texas"}}' -H "Authorization: Token $TOKEN" -H "Content-Type:
application/edn"
```

Eventually the system came around.

```sh
200 OK
```

And the system was deployed to distant lands.
...

And reported back various findings.

```clojure
(def find-findings #'identity) ;database call goes here
(defn report-findings [args req]
  (find-findings args))
(http/register-query-handler ::report-findings {:place s/Str (s/optional-key :todos) (s/enum
"new" "done")})
```

The web had many questions.

```sh
curl "localhost:8080/api/query?place=texas&todos=new" -H "Authorization: Token $TOKEN" -H "Content-Type: application/edn"
```

And learned many things.

_there is no database here, we're just echoing back the args_
```sh
{:place "texas", :todos "new"}
```

The end ... or is it?
https://github.com/teaforthecat/bones

## License

Copyright © 2016 Chris Thompson

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
