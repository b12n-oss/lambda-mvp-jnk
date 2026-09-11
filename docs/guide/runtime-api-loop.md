# The Runtime API loop

## The contract

Identical to the Jolt sibling's -- see
[lambda-mvp-jlt's runtime-api-loop.md](https://github.com/b12n-oss/lambda-mvp-jlt/blob/main/docs/guide/runtime-api-loop.md)
for the full contract table. The only thing that changes in this port
is the transport.

## `http.jank`: no HTTP client exists in jank's ecosystem

Jolt has `jolt-lang/http-client`. jank has nothing equivalent anywhere
in its ecosystem, so `src/net/b12n/lambda_mvp/http.jank` is a
hand-rolled HTTP/1.1-over-raw-socket client, built directly on BSD
sockets via jank's `cpp/` C++ interop.

This is tractable specifically because the Runtime API is plain HTTP
on a loopback address with no TLS -- a general-purpose HTTP/HTTPS
client would be a much bigger undertaking.

Four interop gotchas shaped the implementation (see the code comments
in `http.jank` for exactly where each applies):

1. `htons` is a preprocessor macro on Darwin (likely glibc too), not a
   linkable symbol -- wrapped in a small `cpp/raw` C helper.
2. `cpp/new` does not zero-initialize a struct -- `sockaddr_in`'s
   platform-specific padding is explicitly `memset` before use.
3. `(cpp/new cpp/sockaddr_in)` already returns a pointer -- taking its
   address again produces a corrupting double pointer that compiles
   cleanly and fails at runtime.
4. `cpp/unsafe-cast` refuses a `sockaddr_in*` -> `sockaddr*` cast
   outright -- done inside a `cpp/raw` C helper instead, where ordinary
   C++ casting rules apply.

## One TCP connection per call

`http.jank` connects, sends, receives, and closes for every single
`get`/`post` call -- it does not keep a persistent connection open
across the Runtime API's poll/execute/respond cycle. This is simpler
and loopback connects are cheap, but it's a real, deliberate
simplification: the real AWS Runtime API endpoint's behavior under
this pattern (versus a client that reuses one keep-alive connection)
was verified only against the offline mock server and a generic local
test server during this port's development, not against AWS itself
until the real deploy/invoke smoke test.

## Testing without AWS: the mock Runtime API

`tools/mock_runtime_api.py` is reused verbatim from the Jolt sibling --
canned events, real header set, PASS/FAIL assertion, 410 Gone to end
the loop. `bb probe` wires it to the compiled binary.
