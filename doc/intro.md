# Introduction to bones.http

## Configuration

Bones is intended to be very configurable. This is done by dependency injection
using the Component library. A simple map can function as the "conf" component,
and it will be merged with defaults that are set for development. For
production, you will want to set at least one variable and keep it constant
through restarts: `:http.auth/secret`. You will also probably want to put
configuration into an edn file and one easy way to do that is with the [bones.conf](https://github.com/teaforthecat/bones.conf)
library.

## Secret

The secret must be 32 bytes**. This is a restriction of buddy and,
probably, the encryption algorithm in use. To do this you can put it in an
environment variable or a text file. A secret will be generated if you don't set
it. You can also generate a value:

```clojure
(bones.http.auth/gen-secret)
```


     cat resources/config/production.edn
     {:http.auth/secret "a 32 byte string, yes it is 32b."}


**: If you change the algorithm, (why would you do that?) you'll have to
    change the length of the secret.
