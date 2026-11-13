---
title: Bounded credential password work
---
# Bounded credential password work

The optional identity Spring Security artifact now supplies BoundedCredentialPasswords,
an explicit application-scoped owner for the existing [credential operations](saas-credential-primitives.md).
It implements the local expensive-work portion of the [D14 admission policy](saas-identity-credentials.md#5-public-outcomes-and-abuse-protection).
It implements Spring Security's PasswordEncoder interface for direct standard-provider
integration, plus explicit dummy verification and AutoCloseable ownership. It does not
replace shared admission, identify callers or implement HTTP authentication. The current
standard upgradeEncoding result remains false: no automatic rehash policy is introduced.

## One shared owner and explicit limits

Construct one owner for each application instance, share it across password creation,
matching and unknown-subject dummy verification, and close it on application shutdown.
Do not construct it per request or create separate pools for each identity route: that
would multiply the intended instance-wide allowance. The raw CredentialPasswords API
remains the low-level unbounded primitive for compatibility; the future standard SaaS
assembly must explicitly use the bounded owner. Nothing is installed automatically.

| Configuration | Initial default | Supported configuration |
| --- | ---: | --- |
| Parallel password work | 4 | 1–64 workers |
| Queued password work | 16 | 0–1,024 jobs; zero means immediate handoff only |
| Maximum admission wait | 250 ms | Positive finite Duration, at most 5 seconds |

These limits are explicit construction policy, not new DSL/public property names.
Operational changes require an appropriately managed replacement owner; creating an
additional live pool is not a safe way to resize. Benchmark the chosen password costs
and limits on deployment hardware. Saturation never lowers Argon2 costs or switches to
a weaker encoder. The wrapper delegates actual hashing/comparison to CredentialPasswords
and Spring Security; there is no new crypto, authentication protocol or public encoder SPI.

A fixed JDK ThreadPoolExecutor uses a bounded ArrayBlockingQueue (or SynchronousQueue
for zero queued jobs) and AbortPolicy. It never runs expensive work on a submitting
request thread to bypass the cap and never creates overflow/cached workers. The
250 ms value is a start-admission budget, **not** a password-hash execution timeout
or end-to-end HTTP latency guarantee. OS scheduling and thread creation are not hard
real-time services. Already admitted computation remains bounded by real worker count.

## Input, queue and cancellation semantics

Cheap bounded input normalization/format checks run before enqueue. Password creation
and matching preserve the existing normalization, blocklist and stored-format policies;
unsupported encodings fail technically. Creation-policy failures are not converted to
incorrect-password results. Oversized/invalid matching input still fails cheaply as in
the underlying primitive. Strings captured for work are immutable bounded snapshots,
not caller-owned mutable CharSequences. The JVM cannot promise complete secret erasure.

The monotonic start deadline is sampled on submission and checked by the worker before
execution, so expired queued work is rejected even if its waiting caller has not yet
processed the timeout. A request whose queue wait expires removes its pending job and
clears the queued operation reference; that job must never run later. Queue overflow
fails immediately without allocating unbounded waiting threads inside this owner.
The request transport/server must still bound its own threads/connections/body sizes.

Interrupting a waiting request restores its interrupt status and returns a safe technical
failure. A queued operation is cancelled/removed; an already running hash may finish.
Request cancellation does not free a permit while the real computation is still running,
start a replacement worker for it or imply that an encoder honors interruption.

close rejects new work, drains/cancels queued jobs and requests interruption of workers.
Already dispatched work may finish and return its result; close does not promise forced
termination or synchronous waiting for all running hashes. Shutdown is idempotent.
Worker threads are daemon threads with fixed non-user names and do not inherit
InheritableThreadLocal request context. Submitted work carries no security/tenant/transaction
context or arbitrary application callback; it contains only the explicit credential inputs.
Unknown worker errors expose safe reason codes without raw causes or secret-bearing logs.

## Transaction and admission boundaries

Call the shared/global and applicable keyed admission gates before expensive work, then
invoke this owner **outside** any Spring transaction, synchronization or bound resource
scope. Such calls are rejected with WORK_CONTEXT_INVALID; the wrapper does not clear,
suspend or move a business transaction onto another thread. Nested submissions from a
credential worker are rejected too, preventing self-deadlock. The optional security
artifact now includes spring-tx as a compile/runtime dependency for this guard rather
than only for tests. General compiler/runtime dependency boundaries are unchanged.

The intended private credential handoff is: obtain a current snapshot in a short writer,
end that transaction, run Spring matching here, then recheck version/epoch in a new writer
before completing the trusted authentication workflow. The
[credential persistence contract](saas-identity-credentials-jdbc.md) still applies.
Do not move hashing inside a challenge/credential callback while holding database locks.

New CredentialConfigurationException codes distinguish WORK_CAPACITY, WORK_CLOSED,
WORK_INTERRUPTED, WORK_CONTEXT_INVALID and WORK_FAILED from input or matching failures.
They are technical outcomes, never an authentication grant or a false password mismatch.
The later HTTP adapter maps saturation/infrastructure failure to sanitized temporary
unavailability with bounded retry guidance. This wrapper owns no database admission
counter and does not refund attempts when local work is rejected, interrupted or fails.

## Evidence and remaining integration

Deterministic blocking/latch tests verify worker/queue bounds, rejection without caller
runs, caller- and worker-side expiry, queue reclamation, interruption while a real slot
is occupied, shutdown, safe errors, non-inherited context and nested/transaction guards.
Real Spring tests exercise encoding, matching and dummy work. A restricted PostgreSQL
credential test rejects hashing in a real writer and exercises snapshot/verification/
confirmation/change outside it. Tests do not claim production throughput or load immunity.

Source/identifier/locator admission, key retention/cleanup, public ingress wiring, login-key
ownership, protected delivery, provisioning and the actual browser authentication/session
flows remain required. This local pool alone does not implement public registration
protection. A01/A02 and complete SaaS/final manual acceptance remain OPEN.
