---
title: Identity mail provider bridge
---
# Identity mail provider bridge

This slice connects the private durable Identity mail worker to the ordinary
application-bound [MailProvider](saas-mail-providers.md). It adds no second provider
selector and makes no provider call inside a database transaction. Concrete
application/scheduler assembly remains separate.

Fixed plain text and HTML templates preserve the original delivery address, use a
stable job ID as correlation and render verification/recovery links from an explicit
trusted origin. The bearer token appears only in the URL fragment; future public
GET pages must read and erase the fragment, then require an explicit CSRF-protected
POST. Merely opening a link must never activate/reset anything. The fixed routes
are /auth/verify and /auth/recover. Endpoints are not installed by this slice.

Origins require HTTPS, no user information, query, fragment or path prefix.
Explicit local development also permits HTTP on localhost or literal loopback.
SIMULATED providers require an explicit local-simulation flag AND a loopback origin;
a production origin cannot silently accept Noop delivery. No default bean is
registered, so the eventual application factory must supply the reviewed settings.

Provider ACCEPTED settles/deletes/refunds the charged envelope. REJECTED also
settles terminally without retry. RETRY schedules the greater of the worker's
exponential backoff and the provider hint, bounded to thirty minutes. Exceptions
retain the safe retry path. Retries reuse ciphertext and never recharge encryption.
Existing lease fencing, eight-attempt cap, expiry, cancellation and cleanup remain.

Two unit proofs cover purpose-specific fragments, correlation and origin/simulation
validation. A real PostgreSQL proof runs the bridge outside a transaction, retains
one encrypted job during a 1200-second hint and prevents another submission after
permanent rejection. Existing worker crash/restart/cancellation proofs also run.

Still open: public HTTP journeys, fragment handling/CSRF pages, worker operational
assembly and preview authorization. Provider acceptance is not recipient delivery;
duplicate external delivery after an acceptance/commit crash remains possible.
