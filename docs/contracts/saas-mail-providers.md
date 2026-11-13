---
title: Shared mail providers
---
# Shared mail providers

Status: shared provider contracts and adapters implemented; Identity worker and
application assembly integration remain separate. This is not a completed
Identity/Auth journey. GitHub Actions executes the provider and consumer proofs.

The ordinary Vernac MailProvider port carries redacted bounded OutboundMail values,
a LIVE/SIMULATED mode and ACCEPTED/RETRY/REJECTED submissions with finite retry hints.
Normal application/profile bindings select NoopMailProvider or BrevoMailProvider.
Technical helpers are lazy Spring beans. Unselected Brevo needs no credentials;
selected Brevo requires vernac.mail.brevo.api-key and vernac.mail.sender-address.
There is no silent simulation fallback or startup provider request.

Noop means no external send. Its bounded local inbox retains rendered previews,
supports deterministic retry/rejection/failure sequences and expires messages.
Default limits are 100 messages, 1 MiB and ten minutes. It exposes no HTTP UI by itself;
the eventual local-only authorized preview UI remains separate.

Brevo uses its fixed HTTPS API endpoint and OkHttp 5.5.1 with a ten-second full-call
timeout, finite stage timeouts, four concurrent permits and no redirect following.
Only local loopback servers are available through the package-private test seam.
No provider response body or credentials are returned/logged. Acceptance means HTTP
201, not recipient delivery; duplicates remain possible. Retry hints are bounded
to 30 minutes. Permanent rejection and retry hints must still be bridged into the
identity worker before this is accepted as integrated delivery.

The compiled-module consumer test compiles and boots an ordinary application binding
without regenerating provider implementations. Fourteen provider tests cover parsing,
bounds, local expiry/faults, selected-only configuration, HTTP outcomes and call limits.
A regression test forbids automatic HTTP 408/503 resubmission, including
Retry-After: 0; the durable worker must own retries.

Remaining: worker/template bridge, local preview authorization, production simulation
guard, public Auth flows and D05 provisioning. A11 remains OPEN.

Primary API references:
- [Brevo transactional endpoint](https://developers.brevo.com/reference/send-transac-email).
- [OkHttp changelog](https://github.com/lysine-dev/okhttp/blob/main/CHANGELOG.md).
- [OkHttp timeout example](https://github.com/lysine-dev/okhttp/blob/main/samples/guide/src/main/java/okhttp3/recipes/ConfigureTimeouts.java).
