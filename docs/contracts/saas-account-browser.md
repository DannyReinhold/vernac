---
title: Protected account screens and fresh product scope selection
---
# Account browser adapter

The full browser builder overload additionally accepts IdentityAccountActions and the
invitation action port. IdentityAccounts implements this narrow metadata port directly.
It installs protected workspace listing, owner-scoped member/invitation management,
role assignment, removal, explicit ownership transfer, workspace closure and own-identity
disable/closure. Every mutation is a bounded CSRF-protected POST after authorization;
GET never changes account/owner/member state. Last-owner conflicts return safe 409;
missing authorization is 403, invalid input 400 and backend/configuration failure 503.
Safe failure retains the current login/selection. Identity disable/closure invalidates
the current session only after the metadata transaction commits.

Owner pages may display the original verified delivery address of their scoped members
and the recipients they invited. There is no unrestricted user lookup. Member DTOs
redact toString and all HTML attributes/text are escaped. Role keys remain explicit;
a comma-separated form value maps to the same finite 32-role canonical assignment set,
never ownership. The roles field alone allows at most 8,224 UTF-16 units (32 qualified
256-character keys plus separators); other field limits and the total 16-KiB body cap
remain unchanged. At most 32 exact form routes can be configured. Unknown/duplicate
roles still fail policy validation. No submitted owner flag becomes an owner fact.

POST `/auth/account/select` validates AccountId, current identity/membership and immutable
mapping, then stores only the approved AccountId string in the server session. A caller
TenantId field is not consulted. Product requests reopen a fresh verified TenantContext
scope through the port and close it in finally, including on handler failure. Role,
identity and product object policy still execute through the ordinary invocation guard.
Revoked membership denies with no automatic account fallback; an unselected GET goes to
the fixed workspace picker and an unselected unsafe request denies. Identity library
routes remain deliberate global flows and never inherit the selected product scope.
This adapter covers synchronous servlet dispatch; async/deferred work must use the
existing explicit trusted context protocol and does not inherit a browser thread scope.

The selection is not a cached role/tenant grant. Two instances share only the ordinary
Session repository's AccountId string, then independently revalidate current metadata.
The application must register the actual Session filter before Security once, use the
default `/` servlet mapping and configure connector/proxy/time/concurrency limits.

Five mandatory configured-chain tests prove authentication/CSRF ordering, verified
selection and per-request mapping/revocation without fallback, cleanup on product errors,
escaped owner data and long finite role sets, and safe last-owner/backend failure with
session retention. Their metadata port is explicitly synthetic; real account policy,
foreign keys, concurrency and public credential journeys have separate PostgreSQL tests.
Full sample/real connector/browser composition follows in final acceptance.
