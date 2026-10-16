# Security policy

## Reporting a vulnerability

Please do not disclose exploitable vulnerabilities, credentials or sensitive
reproduction details in public issues or pull requests.

If GitHub offers **Security → Report a vulnerability**, use that private channel:
[private vulnerability report](https://github.com/DannyReinhold/vernac/security/advisories/new).
Otherwise email **danny@danny-reinhold.de** with the subject `Vernac security report`.
Do not assume ordinary email provides end-to-end encryption; arrange a suitable
transfer channel before sharing sensitive material.

Include affected versions/components, a minimal reproduction, prerequisites and
expected impact. Use synthetic data and never include live secrets or customer data.
The maintainer will assess the report and coordinate fixes and disclosure with
the reporter. There is no guaranteed response time or paid bounty program.

## Scope and supported versions

Reports about the compiler, generated code, runtime libraries, IntelliJ plugin,
build tooling and repository workflows are welcome. Security fixes normally target
the latest stable release and ongoing development. Older releases do not have a
guaranteed backport or long-term support commitment; upgrade to the latest release.

Application owners remain responsible for deployment configuration, authentication,
authorization, dependency updates and reviewing generated migration candidates.
For ordinary defects and feature requests, use the public issue templates.
