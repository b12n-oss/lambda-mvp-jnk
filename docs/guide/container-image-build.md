# Container image build

## Why a container image instead of a zip

The Jolt sibling deploys a zip on `provided.al2023`, whose execution
environment is Amazon Linux 2023 (AL2023) -- glibc 2.34. jank's own CI
builds on `ubuntu-24.04`, whose glibc is 2.39. This is the same class
of mismatch the Jolt sibling hit with its own prebuilt binary (needing
glibc >= 2.35), which it solved by building jolt from source *inside*
an AL2023 container.

Replaying that fix for jank would mean building jank's full
LLVM/Clang toolchain from source on AL2023 -- a much heavier lift than
Jolt's ~4-minute Chez Scheme build, and unproven anywhere in the jank
ecosystem at the time this port was written.

Instead, this port deploys as a **Lambda container image**. AWS's own
documentation confirms a non-AWS base image just needs to "include a
runtime interface client... if you're using a language that doesn't
have an AWS-provided runtime interface client, you must create your
own" -- exactly what `runtime.jank` already is. `ubuntu:24.04` +
jank's own APT package (`ppa.jank-lang.org`) sidesteps the
glibc/LLVM-from-source problem entirely.

## The build

```dockerfile
FROM ubuntu:24.04
# ... install jank via its APT package ...
COPY src ./src
RUN jank compile --module-path src --target-dir target --name bootstrap net.b12n.lambda-mvp.main
ENTRYPOINT ["/var/task/target/bootstrap"]
```

`bb image` builds this for `LAMBDA_ARCH` (default `amd64`). Phase 0's
spike confirmed jank's PPA for Ubuntu 24.04 publishes **amd64 only** --
`dists/noble/Release` states `Architectures: amd64` and there is no
`binary-arm64/` directory. This is a real, confirmed divergence from
the Jolt sibling's arm64-by-default choice -- see the design spec's
Open Risks for the full detail.

## Deploying a container image needs Amazon ECR

Unlike the zip-based Jolt sibling (a direct `--zip-file` upload),
Lambda container-image functions must be pushed to **Amazon ECR**
specifically. `bb deploy` creates the ECR repository if it doesn't
exist, authenticates Docker to it, tags and pushes the local image,
then creates or updates the Lambda function with
`--package-type Image --code ImageUri=...`. `bb teardown` deletes the
ECR repository along with the function and IAM role.

### A plain `docker push` can produce an image Lambda rejects

If `bb deploy` fails with `InvalidParameterValueException: The image
manifest, config or layer media type for the source image ... is not
supported`, this is Docker Desktop's **default** behavior on a
containerd-backed image store (the default since Docker Desktop
enabled it), not an environment quirk like the "Restricted networks"
issues above. A plain `docker build` attaches a build-provenance
attestation to every image, so a plain `docker push` writes the
registry tag as an OCI image **index** (the real single-platform image
manifest plus a small attestation manifest) instead of a single image
manifest -- and Lambda's `CreateFunction`/`UpdateFunctionCode` reject
that index outright. `bb deploy`'s `push-image!` already works around
this with `docker push --platform <arch>` (Docker's own documented flag
for pushing a single-platform manifest, dropping the index/attestation
wrapper) -- verified against a real deploy on 2026-09-11. If you're
adapting this project's deploy script elsewhere, keep that flag; it's
required, not optional, on any recent Docker Desktop install.
