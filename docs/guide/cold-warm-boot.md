# Cold vs. warm boot

## What `bb bench` measures

The same three CloudWatch `REPORT` line fields as the Jolt sibling's
`bb bench`: **Init Duration** (cold-start container/runtime bring-up,
present only on the first invoke after a fresh execution environment),
**Duration** (handler execution time), and **Billed Duration**.
`bb bench` forces a fresh execution environment per memory tier (any
`update-function-configuration` call does this), takes one cold sample,
then `BENCH_WARM_SAMPLES` warm samples, and prints a comparison table.
It reuses `script/bench.clj`'s `parse-report-line`/`format-table`
verbatim from the Jolt sibling.

## Why these numbers aren't comparable to the Jolt sibling's

The Jolt sibling's published cold/warm numbers describe a **zip-based
`provided.al2023` custom runtime**: Lambda unzips a small archive and
execs `bootstrap` directly. This port's numbers describe a
**container-image function**: Lambda pulls and unpacks an OCI image
before the entrypoint runs. The cold-start mechanisms and their
overheads differ, so a `bb bench` run here is an honest number for
*this* deployment shape but not a clean jank-vs-jolt language
comparison. Run `bb bench` for your own numbers, and don't read a delta
between this repo's table and the Jolt sibling's as "jank is
faster/slower than jolt" without accounting for the packaging
difference first.

The image itself is also much bigger than the code it runs. `jank
compile` produces one small native binary (single-digit megabytes), but
the image around it carries the full `ubuntu:24.04` base plus jank's own
APT package and its LLVM/Clang toolchain dependencies, which the
compiled binary needs only at build time. A multi-stage build that
copies just the compiled binary into a minimal runtime base image (see
README's Extension points) would shrink the pulled/unpacked image
substantially and likely change the cold-start numbers measurably. This
port doesn't do that yet, so today's `bb bench` numbers include the cost
of pulling/unpacking packages the running function never touches.

## A real measured run

Measured against this repo's own account (`ap-southeast-2`, x86_64,
2026-09-12), `jank compile`d from this port's `HEAD` at the time, with
default tiers and sample count:

| Metric | 2048 MB | 3008 MB |
|---|---|---|
| Cold `Init Duration` | 510.3 ms | 64.0 ms |
| Cold `Duration` | 1.7 ms | 1.5 ms |
| Warm `Duration` (min/median/max) | 1.3 / 1.4 / 1.7 ms | 1.2 / 1.3 / 1.4 ms |
| Max Memory Used | 26 MB | 26 MB |

These numbers are illustrative, not a live guarantee, as with the Jolt
sibling's own: a single run, one account, one region, one moment in
time. Run `bb bench` yourself for your own account and region.

## A second run, 2026-09-19

The table above stays as recorded. This is a fresh `bb bench` against the
same account and region a week later, taken while the whole
`lambda-mvp-*` family was re-measured together after jolt shipped
v0.8.9:

| Metric | 2048 MB | 3008 MB |
|---|---|---|
| Cold `Init Duration` | 615.4 ms | 61.3 ms |
| Cold `Duration` | 1.7 ms | 1.6 ms |
| Warm `Duration` (min/median/max) | 1.3 / 1.4 / 1.6 ms | 1.2 / 1.5 / 1.6 ms |
| Max Memory Used | 26 MB | 26 MB |

Everything except Cold Init landed on top of the first run: handler time
within 0.1 ms, warm time within 0.2 ms, Max Memory Used identical at
26 MB for both tiers.

The order effect reproduced too, and more strongly. The 3008 MB tier's
Cold Init came in at 61.3 ms against the 2048 MB tier's 615.4 ms, the
same inversion the first run showed at 64.0 ms against 510.3 ms. The
second tier landed within about 3 ms across the two runs while the first
moved by more than 100 ms, which supports the explanation below: the
2048 MB tier pays the image pull, and the 3008 MB tier, running second in
the same session, does not.

One caveat this round surfaced that the first did not record: the
`Dockerfile` installs jank with `apt-get install -y jank` from the PPA,
with no version pin, so a rebuild picks up whatever the PPA currently
serves. This image was built from package version `0.1-noble`. A future
rebuild could measure a different jank, unlike the Jolt siblings, whose
`JOLT_VERSION` build arg pins theirs. Pinning it would make this table
reproducible in the way those are.

The 3008 MB tier's cold `Init Duration` (64.0 ms) is far BELOW the
2048 MB tier's (510.3 ms), the opposite of what more memory would
predict on its own. The likely explanation is ECR image-layer caching,
not memory size: `bb bench` runs the 2048 MB tier first, which is this
deploy's first invocation and so the first time Lambda pulls this
image's layers from ECR. By the time the 3008 MB tier's
`update-function-configuration` forces a fresh execution environment,
Lambda has cached those layers, because a memory-size change forces a
fresh sandbox but not a fresh image pull. So this run's two "cold"
samples are cold in the execution-environment sense but not
independently cold in the image-pull sense, and the 2048 MB number is
closer to a genuine worst-case first-pull cold start. To isolate memory
size's own effect on cold start, force an image re-pull between tiers
(e.g. deploy under a fresh image tag, or a fresh function name) instead
of relying on `bb bench`'s config-change-only reset.

Warm `Duration` and Max Memory Used both landed as expected: low
single-digit milliseconds once a sandbox has served an event, and a
small, roughly constant memory footprint regardless of the configured
tier (this port's demo handler does nothing beyond string-building and
an atom `swap!`).

## Reproducing a run

```sh
bb image && bb deploy && bb bench
```

`BENCH_MEMORY_TIERS` (default `2048,3008`) and `BENCH_WARM_SAMPLES`
(default `5`) are overridable, same convention as the Jolt sibling.
