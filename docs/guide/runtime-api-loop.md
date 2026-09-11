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

Six interop gotchas shaped the implementation (see the code comments
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
5. `send`'s second parameter is `const void*`, and jank has no
   object->void* conversion path -- casting the jank string to
   `(:* cpp/char)` first routes through a conversion path that already
   works, and C++ implicitly widens `char*` to `const void*` from
   there.
6. A `defn` that shadows a `clojure.core` symbol (here, `get`) compiles
   clean with only a warning, but crashes the COMPILED BINARY at load
   time with a hard runtime error -- `(:refer-clojure :exclude [get])`
   in the ns form is required, not stylistic.

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

## Startup failures are silent: no try/catch, no init-error, exit 0

`main.jank`'s `-main` calls `runtime/run` with no `try`/`catch` around
it, unlike the Jolt sibling's `main.clj`. That's not an oversight: a
trivial `try`/`throw`/`catch` program with zero interop content took
15+ minutes to `jank compile` and never finished, confirmed directly
rather than assumed (spec Open Risk #9). `main.jank` can't afford that
cost, so the try/catch stays out until a future jank release fixes the
underlying compile-time issue.

The consequence is worth knowing before you deploy this. If
`runtime/run` throws during startup -- a malformed
`AWS_LAMBDA_RUNTIME_API`, or the initial connection to the Runtime API
sidecar failing -- the exception propagates all the way up, uncaught.
jank prints its own error report to stderr (message, data, and a stack
trace), but the process then exits 0 anyway, regardless of the throw.
This was verified directly against a trivial uncaught-throw probe, not
assumed.

Two things follow from that exit code. First, `runtime/post-init-error`
never runs, because there's no catch point left to call it from (the
function stays defined and unused in `runtime.jank`, ready to wire back
in once the compile-time cost is fixed). So the Lambda Runtime API never
sees an init-error report for this failure. Second, nothing in the
process's own exit status signals that anything went wrong, so a
container orchestrator or Lambda's own supervisor watching this process
gets no signal from it either.

If a deployment seems to hang or never responds, don't trust a clean
exit code or the absence of an init-error report to mean startup
succeeded -- neither one tells you that here. Check CloudWatch Logs for
the function instead. jank's own printed error report, with the
message, data, and stack trace, is the only place a startup throw
actually shows up.

## A handler throw crashes the whole process too, not just that invocation

The startup case above is about `main.jank` calling `runtime/run` for
the first time. The same missing `try`/`catch` also wraps every
SUBSEQUENT call to `handler` inside `run`'s own loop -- there's no
catch point around `(handler body ctx)` either, for the identical
reason (spec Open Risk #9: a trivial `try`/`catch` took 15+ minutes to
`jank compile` and never finished).

The Jolt sibling wraps each invocation's handler call individually, so
a single bad event reports a per-invocation `HandlerError` to the
Runtime API and the loop continues to the next event. This port can't
do that yet: if `handler` throws for any reason on ANY invocation, the
exception propagates uncaught, the process exits (with the same exit-0
behavior described above), and the whole Lambda execution environment
goes down -- not just the one invocation that triggered it. The next
invocation gets a fresh cold start instead of a graceful per-event
error response.

For this project's own demo handler (string-building and an atom
`swap!`, no I/O, no parsing of untrusted structure) that path is
unreachable in practice. It becomes a real concern the moment a
handler you write can throw -- parsing the event JSON, calling out to
another service, anything I/O-bound. Until a future jank release
resolves the try/catch compile-time cost, keep handlers defensive
(validate input before it can throw, or return an error payload rather
than throwing) rather than relying on the runtime loop to catch a
mistake for you.

## Testing without AWS: the mock Runtime API

`tools/mock_runtime_api.py` is reused verbatim from the Jolt sibling --
canned events, real header set, PASS/FAIL assertion, 410 Gone to end
the loop. `bb probe` wires it to the compiled binary.
