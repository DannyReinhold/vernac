---
title: Bounded Identity forms and local return paths
---
# Identity HTTP input

`IdentityFormFilter` is an explicitly installed filter for a finite set of exact
servlet paths. Install it inside Spring Security's firewall, before CSRF and every
filter that reads request parameters. Do not register it a second time as a container
filter. No public route, authentication provider or application chain is installed.

Identity POSTs accept only UTF-8 `application/x-www-form-urlencoded`, no query string,
at most 16,384 raw body bytes, 16 uniquely named fields and 4,096 UTF-16 units per value.
The bounded stream read also covers unknown Content-Length/chunked bodies. It does not
call container parameter parsing. The wrapped request supplies defensive parameter
copies to CSRF and downstream handlers. Duplicate fields, malformed escapes/UTF-8
and ambiguous names fail with a fixed no-store 400; oversized bodies return
413, unsupported media/charset 415. Passwords are never included in these responses.
Valid Unicode scalar values, including controls and an intentional replacement
character, are preserved exactly for the existing password policy; no trimming,
normalization or silent malformed-input replacement happens in the form parser.

The application must also configure connector/proxy header limits, finite read/idle
and total request timeouts, request concurrency and transport-source admission. A byte
cap does not bound a slow client's connection lifetime or aggregate request memory.
These primitives alone do not establish full public ingress acceptance.

`IdentityLocalReturnTarget` validates a startup fallback and accepts only bounded,
unencoded ASCII absolute paths. It rejects authorities, schemes, backslashes, controls,
percent encoding, dot segments, query/fragment and URL session parameters. Invalid or
missing visitor input resolves to the configured fallback. This intentionally narrow
policy does not preserve query strings. Mount paths belong in the configured paths;
never prepend a request-derived Host or forwarded header. Do not use Spring's unvalidated
saved-request URL as a substitute. Applications still authorize the destination.

Eight mandatory tests cover container-free parsing, encoded credentials/CSRF values,
known/chunked limits, parameter pollution/malformed fields, route/media boundaries,
redirect ambiguities and startup fallback rejection. Full HTTP/session assembly remains
in [the review sequence](../development/identity-auth-review-series-0.6.0.md).
