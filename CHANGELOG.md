# Changelog

All notable changes to this project are documented here.

## Unreleased

### Added
- AWS Lambda custom runtime in jank, deployed as a container image
  (`ubuntu:24.04` + jank's APT package), implementing the Lambda
  Runtime API contract directly.
- `http.jank`: hand-rolled HTTP/1.1-over-raw-socket client (jank has
  no HTTP client in its ecosystem).
- `bb probe`/`bb check`/`bb image`/`bb deploy`/`bb invoke`/`bb bench`/
  `bb demo`/`bb teardown`/`bb test`/`bb clean` task suite.
- Cold/warm boot-time benchmarking (`bb bench`), reusing the Jolt
  sibling's `script/bench.clj` parsing/formatting logic verbatim.

### Not included (see README's Extension points)
- `provided.al2023` zip parity with the Jolt sibling.
- A function URL / public HTTP endpoint.
- Multi-architecture image builds.

### Known limits
- `http.jank` opens one TCP connection per Runtime API call rather
  than a persistent connection -- see docs/guide/runtime-api-loop.md.
- Cold/warm numbers from `bb bench` are not directly comparable to the
  Jolt sibling's own numbers -- different packaging model, different
  cold-start profile.
- A compiled jank binary exits 0 even on an uncaught startup throw, and
  `main.jank` has no `try`/`catch` to send a Runtime API init-error
  either -- see docs/guide/runtime-api-loop.md.
