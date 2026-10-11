# Adapter configuration

Status: implemented for outbound Java-backed and declarative REST adapters.

## Declaration and scope

An adapter MAY declare one `configuration` block, immediately after its opening
brace and before its behavior, REST, schemas or mappings. The block MUST contain
at least one entry. Each entry MUST specify a type, an explicit member name, and
an explicit property key:

```vernac
configuration {
    URI baseUri from "carrier.base-url";
    Duration timeout from "carrier.timeout";
    Path directory from "archive.directory";
    int batchSize from "archive.batch-size";
    String apiKey from "carrier.api-key";
}
```

This is technical adapter configuration, not a domain type definition or an
injection of domain services. `uses` and `java uses` continue to declare object
collaborators. Configuration does not change port signatures. A value is available
as `self.memberName()` in inline behavior and in `implemented by` implementations,
with the same type and value in both cases. REST error hooks receive the same access
view. Values are immutable references captured when the adapter is constructed.

Member names MUST be valid Vernac/Java identifiers and MUST NOT conflict with
other adapter dependencies, configuration entries, reserved members or zero-argument
operations. Normal Unicode naming rules apply. Keys MUST match
`[a-zA-Z0-9][a-zA-Z0-9_.-]*`; they are literal keys, not placeholder expressions.
There are no inferred property keys, optional properties, DSL defaults or reload
semantics. Application configuration MAY supply defaults and environment substitution.

## Types and conversion

Only these technical types are allowed; they do not expand the domain type vocabulary:

| Type | Java type | Required property representation |
| --- | --- | --- |
| `String` | `String` | Nonblank text; whitespace is preserved |
| `URI` | `java.net.URI` | Java URI syntax |
| `Duration` | `java.time.Duration` | ISO-8601 duration, e.g. `PT3S` or `PT0.25S` |
| `Path` | `java.nio.file.Path` | Path accepted by the host filesystem provider |
| `int` | `int` | Signed decimal integer within Java int bounds |
| `long` | `long` | Signed decimal integer within Java long bounds |
| `boolean` | `boolean` | Exactly `true` or `false` |

Conversion uses these explicit rules, not application-registered Spring converters.
Values are not trimmed or normalized. A path is not created or checked for existence.
Relative URIs/paths and negative durations/numbers are not generically prohibited:
their validity depends on the adapter's purpose. Operational constraints belong to
the consuming client or adapter logic.

For a declarative REST adapter, a configured `base` MUST have type `URI`. It MUST
be an absolute HTTP(S) URI with a host and no user information, query or fragment.
Ports above 65535 are rejected. A base path is allowed. This check runs during
construction, before any request. A configuration entry does not automatically
configure HTTP timeouts or authentication; behavior must explicitly use such values.

## Spring and direct construction

Generated beans use an explicitly `@Autowired` constructor that resolves keys
through Spring's `Environment`. Spring Boot's normal property-source precedence,
YAML/properties files, profiles and environment integration apply. Values are read
once when the bean is instantiated (normally application startup; lazy beans later).
No separate scalar `URI`, `String` or `Duration` bean is required for declared values.

A second public constructor accepts ordinary collaborators followed by typed
configuration values in declaration order. This supports explicit Java wiring and
tests. Both paths enforce non-null/nonblank values and the REST base constraint.
Typed direct construction does not read external properties.

Without a configuration entry, a REST `base` remains a named URI bean dependency,
allowing an endpoint supplied programmatically. Do not declare the same member in
both `configuration` and `java uses`.

## Errors and sensitive values

Missing, blank, malformed or unresolved properties MUST fail bean construction with
`AdapterConfigurationException`, a `VernacTechnicalException` (Spring may wrap it).
Its message identifies the adapter member, property key and expected representation.
It MUST NOT include the value or retain a conversion exception as cause. This applies
to all configuration values, including credentials; no separate secret annotation
is needed. Generated code MUST NOT log configuration values.

Application code and custom property sources remain responsible for their own logging
and secret handling. Never commit real credentials. A String property can carry a
secret, but it is not a secret-store implementation or a credential-rotation mechanism.
