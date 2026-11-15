---
title: Browser ingress before session and parameter access
---
# Browser request admission

The opt-in browser chain now requires an explicit `IdentityRequestAdmission`. It
calls the shared gate after secure-transport validation and before security-context
loading, request-time metadata lookup, form parsing, CSRF and lazy login-page session
creation. There is no overload with an allow-all production fallback. Supply the real
`IdentityJdbcAuthentication.requestAdmission()` from the explicit JDBC assembly.

Every initial browser dispatch through this chain spends one independently committed
coarse global attempt, including login-page GET and malformed POST. Credential processing
continues to spend its own global/source/subject admission attempt. Thus a successful
login POST can spend two global attempts; a separately fetched form page spends another.
These are deliberate stage budgets, not a count of unique login submissions. Neither
later rejection nor business rollback refunds attempts. The existing published default
is 300 coarse attempts per minute deployment-wide. The fixed-window boundary/burst and
clock-regression semantics remain those of the existing ingress helper.

Denials return fixed no-store 429 with Retry-After 1–60; backend/null decisions return
fixed no-store 503. The filter reads no visitor parameters, session or forwarded headers.
Full proxy/connector header/read/total-time/concurrency limits remain necessary; this is
not a socket limiter. Forwards/async container dispatch configuration and alternate
security chains require separate coverage review. Only the configured chain is gated.

The assembly also exposes `maintainAdmission()`, one bounded 250-row sweep using the
existing durable cursor and private admission cleanup. Schedule it explicitly on an
application-owned background executor outside all actor/tenant/transaction contexts,
for example once per second, and monitor failure/lag. It does not register its own
scheduler or silently run in a request. Existing cursor/concurrency/expiry proofs apply;
the assembly test checks the entry point without refunding current attempts. A production
application still needs explicit lifecycle/monitoring and mail-worker assembly.

Three direct tests prove parameter/session-free admission ordering, fixed failures,
valid retry bounds and no leaked backend details. An additional configured-chain test
proves denial before the login page creates a session. Real PostgreSQL assembly tests
check the public shared ingress and maintenance entry points across restart. Session-only
infrastructure fixtures use a clearly identified synthetic admission callback; they do
not claim production rate-limiter integration.
