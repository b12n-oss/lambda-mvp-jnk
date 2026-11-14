# The Runtime API loop

## The contract

Identical to the Jolt sibling's. See
[lambda-mvp-jlt's runtime-api-loop.md](https://github.com/b12n-oss/lambda-mvp-jlt/blob/main/docs/guide/runtime-api-loop.md)
for the full contract table. Only the transport changes in this port.

## `http.jank`: no HTTP client exists in jank's ecosystem

Jolt has `jolt-lang/http-client`, but jank has nothing equivalent, so
`src/net/b12n/lambda_mvp/http.jank` is a hand-rolled
HTTP/1.1-over-raw-socket client, built directly on BSD sockets via
jank's `cpp/` C++ interop.

That is tractable because the Runtime API is plain HTTP on a loopback
address with no TLS. A general-purpose HTTP/HTTPS client would be a much
bigger job.

Six interop gotchas shaped the implementation (the code comments in
`http.jank` show where each applies):

1. `htons` is a preprocessor macro on Darwin (likely glibc too), not a
   linkable symbol, so it is wrapped in a small `cpp/raw` C helper.
2. `cpp/new` does not zero-initialize a struct, so `sockaddr_in`'s
   platform-specific padding is explicitly `memset` before use.
3. `(cpp/new cpp/sockaddr_in)` already returns a pointer. Taking its
   address again produces a corrupting double pointer that compiles
   cleanly and fails at runtime.
4. `cpp/unsafe-cast` refuses a `sockaddr_in*` -> `sockaddr*` cast
   outright, so the cast is done inside a `cpp/raw` C helper, where
   ordinary C++ casting rules apply.
5. `send`'s second parameter is `const void*`, and jank has no
   object->void* conversion path. Casting the jank string to
   `(:* cpp/char)` first routes through a conversion path that already
   works, and C++ implicitly widens `char*` to `const void*` from
   there.
6. A `defn` that shadows a `clojure.core` symbol (here, `get`) compiles
   with only a warning, but crashes the COMPILED BINARY at load time
   with a hard runtime error. `(:refer-clojure :exclude [get])` in the
   ns form is required.

## One TCP connection per call

`http.jank` connects, sends, receives, and closes for every `get`/`post`
call instead of keeping a persistent connection open across the Runtime
API's poll/execute/respond cycle. This is a deliberate simplification,
and loopback connects are cheap. The real AWS Runtime API's behavior
under this pattern (versus a client that reuses one keep-alive
connection) was checked only against the offline mock server and a
generic local test server during development, until the real
deploy/invoke smoke test.

## Startup failures are silent: no try/catch, no init-error, exit 0

`main.jank`'s `-main` calls `runtime/run` with no `try`/`catch` around
it, unlike the Jolt sibling's `main.clj`. This is deliberate: a trivial
`try`/`throw`/`catch` program with zero interop content took 15+
minutes to `jank compile` and never finished (confirmed directly, spec
Open Risk #9). `main.jank` can't afford that, so the try/catch stays out
until a jank release fixes the compile-time issue.

If `runtime/run` throws during startup (a malformed
`AWS_LAMBDA_RUNTIME_API`, or a failed initial connection to the Runtime
API sidecar), the exception propagates uncaught. jank prints its own
error report to stderr (message, data, and a stack trace), but the
process then exits 0 regardless of the throw. This was verified
directly against a trivial uncaught-throw probe.

Two things follow from that exit code. First, `runtime/post-init-error`
never runs, because no catch point is left to call it from (the
function stays defined and unused in `runtime.jank`, ready to wire back
in once the compile-time cost is fixed), so the Lambda Runtime API never
sees an init-error report. Second, the process's exit status signals
nothing, so a container orchestrator or Lambda's own supervisor watching
it gets no signal either.

If a deployment seems to hang or never responds, don't take a clean
exit code or a missing init-error report as proof that startup
succeeded. Check CloudWatch Logs for the function instead: jank's
printed error report, with the message, data, and stack trace, is the
only place a startup throw shows up.

## A handler throw crashes the whole process too, not just that invocation

The same missing `try`/`catch` also covers every SUBSEQUENT call to
`handler` inside `run`'s own loop. There is no catch point around
`(handler body ctx)`, for the identical reason (spec Open Risk #9).

The Jolt sibling wraps each invocation's handler call individually, so
a bad event reports a per-invocation `HandlerError` to the Runtime API
and the loop continues. This port can't do that yet: if `handler`
throws on ANY invocation, the exception propagates uncaught, the process
exits (with the same exit-0 behavior described above), and the whole
Lambda execution environment goes down, not just that invocation. The
next invocation gets a fresh cold start instead of a graceful
per-event error response.

For this project's demo handler (string-building and an atom `swap!`,
no I/O, no parsing of untrusted structure) that path is unreachable in
practice. It matters as soon as a handler you write can throw: parsing
the event JSON, calling another service, anything I/O-bound. Until a
jank release resolves the try/catch compile-time cost, keep handlers
defensive (validate input before it can throw, or return an error
payload rather than throwing) instead of relying on the runtime loop to
catch a mistake.

## Testing without AWS: the mock Runtime API

`tools/mock_runtime_api.py` is reused verbatim from the Jolt sibling:
canned events, real header set, PASS/FAIL assertion, 410 Gone to end
the loop. `bb probe` wires it to the compiled binary.
