# Container image build

## Why a container image instead of a zip

The Jolt sibling deploys a zip on `provided.al2023`, whose execution
environment is Amazon Linux 2023 (AL2023) with glibc 2.34. jank's own CI
builds on `ubuntu-24.04`, whose glibc is 2.39. The Jolt sibling hit the
same mismatch with its own prebuilt binary (needing glibc >= 2.35) and
solved it by building jolt from source *inside* an AL2023 container.

Doing that for jank would mean building its full LLVM/Clang toolchain
from source on AL2023. That is much heavier than Jolt's ~4-minute Chez
Scheme build, and nobody in the jank ecosystem had shown it working when
this port was written.

So this port deploys as a **Lambda container image**. AWS's own
documentation says a non-AWS base image just needs to "include a
runtime interface client... if you're using a language that doesn't
have an AWS-provided runtime interface client, you must create your
own", which is what `runtime.jank` already is. `ubuntu:24.04` plus
jank's own APT package (`ppa.jank-lang.org`) avoids the
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
spike confirmed jank's PPA for Ubuntu 24.04 publishes **amd64 only**:
`dists/noble/Release` states `Architectures: amd64` and there is no
`binary-arm64/` directory. That differs from the Jolt sibling's
arm64-by-default choice; see the design spec's Open Risks for the full
detail.

## Deploying a container image needs Amazon ECR

Unlike the zip-based Jolt sibling (a direct `--zip-file` upload),
Lambda container-image functions must be pushed to **Amazon ECR**.
`bb deploy` creates the ECR repository if it doesn't exist,
authenticates Docker to it, tags and pushes the local image, then
creates or updates the Lambda function with
`--package-type Image --code ImageUri=...`. `bb teardown` deletes the
ECR repository along with the function and IAM role.

### A plain `docker push` can produce an image Lambda rejects

If `bb deploy` fails with `InvalidParameterValueException: The image
manifest, config or layer media type for the source image ... is not
supported`, the cause is Docker Desktop's **default** behavior on a
containerd-backed image store (the default since Docker Desktop
enabled it), not an environment quirk like the README's "Restricted
networks" issues. A plain `docker build` attaches a build-provenance
attestation to every image, so a plain `docker push` writes the
registry tag as an OCI image **index** (the single-platform image
manifest plus a small attestation manifest) instead of a single image
manifest, and Lambda's `CreateFunction`/`UpdateFunctionCode` reject
that index outright. `bb deploy`'s `push-image!` avoids this with
`docker push --platform <arch>`, Docker's documented flag for pushing a
single-platform manifest without the index/attestation wrapper. It was
verified against a real deploy on 2026-09-11. If you adapt this
project's deploy script elsewhere, keep that flag: any recent Docker
Desktop install needs it.
