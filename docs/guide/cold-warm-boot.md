# Cold vs. warm boot

## What `bb bench` measures

Same three CloudWatch `REPORT` line fields the Jolt sibling's own
`bb bench` measures: **Init Duration** (cold-start container/runtime
bring-up, present only on the first invoke after a fresh execution
environment), **Duration** (handler execution time), and **Billed
Duration**. `bb bench` forces a fresh execution environment per memory
tier (any `update-function-configuration` call does this), takes one
cold sample, then `BENCH_WARM_SAMPLES` warm samples, and prints a
comparison table -- reusing `script/bench.clj`'s
`parse-report-line`/`format-table` verbatim from the Jolt sibling.

## Why these numbers aren't comparable to the Jolt sibling's

The Jolt sibling's published cold/warm numbers describe a **zip-based
`provided.al2023` custom runtime**: Lambda unzips a small archive and
execs `bootstrap` directly. This port's numbers describe a
**container-image function**: Lambda pulls and unpacks an OCI image
before the entrypoint runs. Those are different cold-start mechanisms
with different overheads -- a `bb bench` run here measures a real,
honest number for *this* deployment shape, but it is not a clean
jank-vs-jolt language comparison. Run `bb bench` for your own numbers;
don't read a delta between this repo's table and the Jolt sibling's as
"jank is faster/slower than jolt" without accounting for the packaging
difference first.

## Reproducing a run

```sh
bb image && bb deploy && bb bench
```

`BENCH_MEMORY_TIERS` (default `2048,3008`) and `BENCH_WARM_SAMPLES`
(default `5`) are overridable, same convention as the Jolt sibling.
