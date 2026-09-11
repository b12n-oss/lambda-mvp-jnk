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

There's a second confound worth naming: the image itself is much
bigger than the code it runs. `jank compile` produces one small native
binary (single-digit megabytes), but the image around it also carries
the full `ubuntu:24.04` base plus jank's own APT package and its
LLVM/Clang toolchain dependencies -- none of which the compiled binary
needs at runtime, only at build time. A multi-stage build that copies
just the compiled binary into a minimal runtime base image (see
README's Extension points) would shrink the pulled/unpacked image
substantially and likely change the cold-start numbers measurably.
This port doesn't do that yet, so today's `bb bench` numbers include
the cost of pulling/unpacking packages the running function never
touches.

## A real measured run

Measured against this repo's own account (`ap-southeast-2`, x86_64,
2026-09-12), `jank compile`d from this port's exact `HEAD` at the time,
default tiers and sample count:

| Metric | 2048 MB | 3008 MB |
|---|---|---|
| Cold `Init Duration` | 510.3 ms | 64.0 ms |
| Cold `Duration` | 1.7 ms | 1.5 ms |
| Warm `Duration` (min/median/max) | 1.3 / 1.4 / 1.7 ms | 1.2 / 1.3 / 1.4 ms |
| Max Memory Used | 26 MB | 26 MB |

Marked illustrative, not a live guarantee, same as the Jolt sibling's
own numbers -- a single run, one account, one region, one moment in
time. Run `bb bench` yourself for a number that reflects your own
account and region.

One result here is worth naming rather than smoothing over: the 3008
MB tier's cold `Init Duration` (64.0 ms) is far BELOW the 2048 MB
tier's (510.3 ms), the opposite of what more memory buying a faster
cold start would predict on its own. The likely explanation isn't
memory size, it's ECR image-layer caching: `bb bench` runs the 2048 MB
tier first, which is this deploy's actual first invocation and so the
first time Lambda pulls this image's layers from ECR. By the time the
3008 MB tier's `update-function-configuration` forces a fresh
execution environment, Lambda has already cached those layers -- a
plain memory-size change doesn't force a fresh image pull, only a
fresh sandbox. So this run's two "cold" samples aren't independently
cold in the image-pull sense, only in the execution-environment sense,
and the 2048 MB number is closer to a genuine worst-case first-pull
cold start than the 3008 MB one is. A run that wants to isolate memory
size's own effect on cold start would need to force an image re-pull
between tiers (e.g. deploy under a fresh image tag, or a fresh
function name) rather than relying on `bb bench`'s existing
config-change-only reset.

Warm `Duration` and Max Memory Used both landed as expected: low
single-digit milliseconds once a sandbox has served an event, and a
small, roughly constant memory footprint (this port's demo handler
does nothing beyond string-building and an atom `swap!`) regardless of
the configured tier.

## Reproducing a run

```sh
bb image && bb deploy && bb bench
```

`BENCH_MEMORY_TIERS` (default `2048,3008`) and `BENCH_WARM_SAMPLES`
(default `5`) are overridable, same convention as the Jolt sibling.
