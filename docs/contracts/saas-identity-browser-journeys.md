---
title: CSRF-protected Identity registration verification and recovery pages
---
# Browser journeys

The full opt-in `IdentityBrowserSecurity.build` overload requires IdentityBrowserActions
in addition to the existing actual manager/trust/admission objects. It explicitly adds
GET/POST `/auth/register`, `/auth/recovery-request`, `/auth/verify`, `/auth/recover` and
protected `/auth/password`. The minimal login-only overload remains usable separately;
it does not claim the full journey. Enabled login pages link to registration/recovery.

The chain bounds form bodies before CSRF and runs journey handlers after authorization.
Every POST requires a current Spring CSRF token; public does not mean CSRF-exempt. GET
renders forms and calls no action. Password changes require a current nominal login.
Transport source uses the same numeric servlet-peer parser as login, never caller form
or forwarded headers. Registration/recovery requests return the same fixed neutral 202.
Known input/policy, source/global throttle and technical failures remain fixed 400/429/503
with bounded Retry-After; bearer/current-credential failures use a neutral action result.
No password/token/provider exception value is reflected into output.

Security links retain the existing trusted `/auth/verify#token` and `/auth/recover#token`
format. The HTML contains an empty hidden token and disabled confirmation button. A
small static hash-authorized script immediately removes the fragment with replaceState,
then populates the DOM field/enables explicit confirmation only for canonical token
syntax. It sends no request, uses no storage and loads no external resources. There is
no automatic submit or GET consumption; no-JavaScript mode cannot redeem the action.
Token pages reject query strings, set no-store/no-referrer/nosniff and restrictive CSP.
Credentials and tokens are never placed in query/path or server-rendered HTML.

Successful verification/reset/change returns a fixed local login redirect after the
application writer has committed, clears request authentication and invalidates an
existing visitor session. It never authenticates the verified/recovered identity. Safe
failures retain the existing session. Old sessions of the target identity are denied at
later epoch checks; no instantaneous distributed cancellation guarantee is made.

Five mandatory configured Spring-chain tests cover no-action GET and CSP/script hash,
CSRF/body/query ordering, neutral requests/trusted source/no auto-login, safe error and
session retention, and authenticated password-change protection. These use an identified
synthetic action port; actual Argon2/provisioning/queue/recovery behavior has the separate
PostgreSQL proofs. The independent HTTPS sample now executes these scripts in Chromium; see
[final Identity/Auth acceptance](../development/identity-auth-acceptance-0.6.0.md).
Source inspection alone is not a JavaScript browser-execution proof.
