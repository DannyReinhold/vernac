# A domain port with a Java-backed adapter

This tutorial introduces port interfaces and Java-backed adapters. Continue with
DTO schemas, mappings and the [HTTP runtime](http-adapter-runtime.md) for the
implemented integration foundations. [REST declarations](declarative-rest.md) now cover request/response bindings;
[declarative error translation](declarative-errors.md) covers typed business rejection and technical classification.

## Declare the capability

Put this in an existing Vernac project's source root under example/clock. As with
other declarations, the namespace must match the directory.

```vernac
namespace example.clock;

value ReadingTime(Instant);

port ObservationClock {
    ReadingTime now();
}

adapter SystemObservationClock implements ObservationClock {
    behavior {
        java uses {
            java.time.Clock clock;
        }

        public ReadingTime now() {
            return ReadingTime.of(self.clock().instant());
        }
    }
}
```

Declare a java.time.Clock Spring bean in application configuration, for example
with Clock.systemUTC(). Generated constructor injection obtains that bean. Inject
ObservationClock wherever the application needs this capability. A usecase or
service can declare uses ObservationClock and call self.observationClock().now().

No transaction starts merely because the adapter is called. Domain port parameters
and results use Vernac IDs, values, enums or collections of those types. Wrap raw
Java values at the domain boundary. Entities and aggregates are not port payloads.

## Generated structure

- example.clock.domain.ObservationClock: public interface.
- example.clock.adapter.outbound.SystemObservationClock: Spring component
  implementing every port operation.
- example.clock.adapter.outbound.access.SystemObservationClockAccess: behavior
  receiver, exposing dependencies, port operations and invocation().
- A package-private behavior companion isolates the method bodies.

The bean does not implement its Access interface. A private inner view does.
Each public operation constructs a new immutable AdapterInvocation. There is no
shared mutable current-request field or ThreadLocal. An explicitly nested port
call starts its own invocation; distributed tracing propagation is later work.

self.invocation() exposes a local invocation UUID, port, adapter and operation.
withDetail(name, value) returns a new context with a deliberately selected string
value; it neither changes the original nor logs anything. Never add credentials.
No automatic technical exception wrapping or HTTP failure mapping is introduced
by this slice. Business exceptions propagate unchanged.

## Move a body to Java

Replace the body with:

```vernac
public ReadingTime now()
    implemented by example.clock.SystemClockBehavior;
```

Provide this handwritten class outside generated sources:

```java
package example.clock;

import example.clock.domain.ReadingTime;
import example.clock.adapter.outbound.access.SystemObservationClockAccess;

public final class SystemClockBehavior {
    private SystemClockBehavior() {}

    public static ReadingTime now(SystemObservationClockAccess self) {
        return ReadingTime.of(self.clock().instant());
    }
}
```

The implementation is a static Java method, not another Spring bean. Injection
remains owned by the generated adapter. Both paths use the generated behavior
companion, so the same self contract, argument checks and result checks apply.

## Dependencies, optional values and helpers

Declare domain services, ports and repository interfaces with uses after the
implements clause. Declare technical Java dependencies with java uses inside
behavior. Technical dependency types are fully qualified and non-optional; generic
Java dependency syntax is not yet supported. java imports, when needed, precedes
java uses. Both blocks are optional.

Public methods exactly implement the port; extra public methods, missing methods
and mismatched optionality are compiler errors. Names of implementation parameters
may differ from port parameter names. Private inline helpers are supported and
receive their dependencies explicitly; they do not receive an implicit self.
Adapter validates blocks are not supported in this slice.

An optional port input uses a nullable parameter at the public Java boundary and
an Optional inside behavior, matching the existing service convention. Optional
results use Optional on both sides. Required null inputs and null reference
results fail at the generated wrapper.

Multiple adapter beans for one port require explicit Spring selection, such as
application configuration or profiles. Vernac does not silently choose a bean.
All injected clients must be appropriate for the application's concurrency model;
generated per-call context isolation does not make a mutable vendor client safe.

## Verification

PortExecutionTest compiles generated Java and runs it in a Spring context. It
checks inline/external behavior, domain-service injection, optional inputs/results,
null checks, unchanged business exception propagation, and concurrent invocation
isolation using a barrier. No PostgreSQL server is needed for that test.

## Next: transport DTOs

Continue with [adapter DTOs and PATCH](adapter-dtos.md) for local records, strict
JSON conversion and explicit update intent.

## Configure a non-HTTP adapter

Technical configuration is equally useful for a filesystem archive. Inside an
adapter, before `behavior`, declare:

```vernac
configuration {
    Path directory from "archive.directory";
    int batchSize from "archive.batch-size";
}
```

Behavior can use `self.directory().resolve(...)` and `self.batchSize()`; an external
`implemented by` class receives exactly the same accessors. No directory is created
by configuration binding, and a positive batch-size constraint must be enforced by
the consumer. The domain port still speaks in domain values, not paths or settings.
See the [configuration contract](../contracts/adapter-configuration.md).
