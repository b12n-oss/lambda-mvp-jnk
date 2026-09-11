# ubuntu:24.04 + jank's own APT package (ppa.jank-lang.org) -- NOT a
# from-source LLVM/Clang build. See docs/guide/container-image-build.md
# for why this port uses a Lambda CONTAINER IMAGE instead of the Jolt
# sibling's provided.al2023 zip: AL2023 ships glibc 2.34, jank's own CI
# builds on ubuntu-24.04 (glibc 2.39), and jank's toolchain (LLVM/Clang)
# is a much heavier from-source build than Jolt's Chez Scheme -- see
# the spec's Open Risks for the reasoning and what Phase 0 verified.
#
#   docker build --platform linux/amd64 -t lambda-mvp-jnk:latest .
#
# ARG LAMBDA_ARCH is set by `bb image` from Phase 0 Task 2's confirmed
# architecture -- do not hardcode a --platform here.
FROM ubuntu:24.04

# Force IPv4: this build environment's outbound network (buildkit's
# "host" network-mode worker + amd64/QEMU emulation on this Apple
# Silicon host) intermittently stalls ~60s per new IPv6 connection to
# archive.ubuntu.com/security.ubuntu.com, and in this build the stalls
# preceded an outright "At least one invalid signature was encountered"
# GPG failure on the InRelease files (Phase 0 Task 2 saw only the
# stalling, not a signature failure -- see Phase 2 Task 1's report for
# this exact log). This is a spike/build-environment artifact, not a
# jank issue -- see task-p0t2-report.md Finding 5. On a network without
# this IPv6 path problem this line is a no-op that can be dropped.
RUN echo 'Acquire::ForceIPv4 "true";' > /etc/apt/apt.conf.d/99force-ipv4

RUN apt-get update && \
    apt-get install -y curl gnupg ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# Trust this build environment's TLS-inspecting corporate proxy CA
# (Zscaler). Environment-specific to the sandboxed host this image was
# first built on -- NOT a jank requirement, and not a security
# weakening (no -k/--insecure, no Acquire::https::Verify-Peer
# "false" anywhere in this file). Without this, curl to
# ppa.jank-lang.org fails TLS verification ("unable to get local
# issuer certificate") and gpg then reports "no valid OpenPGP data
# found" on the empty response -- exactly reproduced in Phase 2 Task 1
# (see its report), same root cause as Phase 0 Task 2's Finding 5. The
# Zscaler Root CA is a public, non-secret root certificate (the same
# cert shipped to every Zscaler customer's endpoints; it carries no
# organization-identifying data beyond "Zscaler Inc." itself) valid
# until 2042. On a network without TLS interception this COPY/
# update-ca-certificates pair is a no-op that can be dropped.
COPY zscaler-root-ca.pem /usr/local/share/ca-certificates/zscaler-root-ca.crt
RUN update-ca-certificates

# NOTE: the jank.list file *served by* ppa.jank-lang.org is stale -- it's
# a flat-repo line ("https://ppa.jank-lang.org ./") that 404s on Release.
# The real repo is laid out per-Ubuntu-codename
# (dists/noble/main/binary-amd64/...), so the deb line is written
# directly instead of downloading the server's own (broken) jank.list.
# Verified 2026-09-11 (Phase 0 Task 2) against the live server.
RUN curl -s "https://ppa.jank-lang.org/KEY.gpg" | gpg --dearmor \
      -o /etc/apt/trusted.gpg.d/jank.gpg && \
    echo "deb [signed-by=/etc/apt/trusted.gpg.d/jank.gpg] https://ppa.jank-lang.org noble main" \
      > /etc/apt/sources.list.d/jank.list && \
    apt-get update && \
    apt-get install -y jank && \
    rm -rf /var/lib/apt/lists/*

# WORKDIR /var/task matches the deployed container's own cwd -- not an
# arbitrary build-only name (same convention the Jolt sibling's
# Dockerfile uses for the same reason).
WORKDIR /var/task
COPY src ./src

RUN jank compile --module-path src --target-dir target \
      --name bootstrap net.b12n.lambda-mvp.main

ENTRYPOINT ["/var/task/target/bootstrap"]
