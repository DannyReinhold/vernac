# Run the complete ports and ACL walkthrough

This tutorial uses `examples/persistence-demo`, the standalone application already
used for persistence and events. Its third runner demonstrates a complete outbound
integration with generated Java and a local HTTP carrier. The assertions are the
same in the application and its database-free automated test.

## 1. Install the current Vernac snapshot

From the repository root:

```text
mvn -pl vernac-maven-plugin,vernac-jdbc-spring-boot-starter,vernac-runtime-http -am clean install
```

Use the JDK required by your checkout. These commands are shell commands; omit the
leading `mvn` when entering goals in IntelliJ's Maven command window.

## 2. Start with the database-free test

```text
mvn -f examples/persistence-demo/pom.xml clean test -Dtest=PortDemoTest
```

No database variables, Docker or external carrier credentials are needed. Expect:

```text
Ports/ACL walkthrough passed: 16 checks, 14 HTTP requests
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

The success message appears three times: two walkthrough executions in one test and
one invocation through PortDemoRunner in the other. No test is conditionally skipped.
A failed expectation fails the build. The local fixture uses a fresh ephemeral HTTP
port each time and is closed after the run.

## 3. Read the model from domain to boundary

Open `src/main/vernac/org/example/carrier/carrier.vernac` in the example:

- Shipment holds Destination and ParcelWeight; ShippingQuote contains a reference
  and a ShippingPrice. The weight and price validate their domain constraints.
- ShippingCarrier declares quote, lookup and cancel operations in domain types.
- CarrierHttp binds those operations to POST, GET and DELETE.
- `toRequest` unwraps single-value objects into wire fields.
- `toPrice` and `toQuote` map a nested response through domain factories.
- The `errors` block separates the external error string from the declared
  ShippingRejection enum. Explicit technical responsibility remains separate from
  an observed HTTP status.

Look at generated files below `target/generated-sources` to follow the delegations
and mappings. Handwritten code only lives under `src/main/java`; do not edit outputs.

## 4. Follow the verified scenarios

| Response or operation | Verified behavior |
| --- | --- |
| Successful POST | Request DTO uses destination/weightKg; nested quote maps to domain values |
| Extra response fields | Ignored, including inside the nested price |
| GET Q-42 | Optional containing the mapped quote |
| GET missing reference | Explicit 404 policy returns empty |
| DELETE with Unicode/reserved path characters | Reference remains one encoded segment; 204 succeeds |
| 422 UNSUPPORTED_DESTINATION | Typed business rejection |
| 403 ACCOUNT_BLOCKED | Typed business rejection |
| 401 with unreadable body | Explicit local AUTHENTICATION; suppressed decoding diagnostic |
| Unrecognized 403 | Technical AUTHORIZATION with unresolved responsibility |
| 503 | Remote failure, Retry-After retained, no automatic retry |
| Invalid successful JSON / missing price | Remote INVALID_RESPONSE |
| Negative remote price | Domain factory rejects it; boundary reports INVALID_RESPONSE |
| Invalid error JSON | Original status preserved, with decoding diagnostic |
| Missing optional error code | No fabricated business match; default technical failure |

The example logs selected structured metadata. It does not dump payloads, credentials
or full exception graphs. A new invocation ID is attached to each failed call.
Responsibility assignments are example-specific policies, not universal HTTP rules.

## 5. Run alongside persistence and events

With the example's usual Docker/PostgreSQL setup:

```text
mvn -f examples/persistence-demo/pom.xml clean spring-boot:run
```

Watch for the persistence completion message, then the event completion message,
then `Ports/ACL walkthrough passed`. The existing `demo.run=false` switch disables
all three application runners. This ports addition needs no migration or database
reset. Existing migration experiments must still be reconciled independently.

The carrier namespace deliberately lies outside the application's component scan.
The walkthrough creates its adapter with a locally scoped endpoint; normal production
Spring wiring is covered by the [HTTP runtime tutorial](http-adapter-runtime.md).
LocalCarrierServer is a demonstration fixture, not a generated inbound adapter.
External effects cannot be rolled back through a database transaction.

## Suggested experiments

1. Inspect the returned ShippingQuote and generated mapping code; notice the preserved
   decimal scale and the absence of wire DTOs in the port interface.
2. Change a mock 422 code in LocalCarrierServer: the business-rejection assertion must
   fail, demonstrating that an unfamiliar response is not silently treated as known.
3. Add another unknown field to a valid mock response: the success assertion still passes.
4. Remove a required field: INVALID_RESPONSE is expected, never Optional.empty.

Restore experiments before committing. Continue with the
[declarative error contract](../contracts/declarative-rest.md) for full condition and
absence semantics; the outbound review and broader language roadmap follow this demo.
