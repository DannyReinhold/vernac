# Debug your business behavior where you wrote it

Your breakpoint belongs in the business rule, not in a generated wrapper. Vernac's
IntelliJ integration lets you stop inside an inline `behavior`, inspect its arguments
and locals, step through the operation and evaluate ordinary Java expressions while
working in the `.vernac` source.

**Availability:** this chapter describes the **0.5.0-SNAPSHOT** development compiler
and plugin from the source checkout. The published **0.4.0** plugin does not provide
this debugger integration. Use matching development artifacts throughout this chapter.

## A small example, with a real JVM behind it

A parcel label is a value object. Its behavior trims the text, converts it to upper
case and adds a suffix. Those separate steps give us a useful place to pause and
inspect what happens. No Spring context, database, Docker or credentials are needed.

Use the existing standalone `examples/debugger` project. Its first model file is
`src/main/vernac/example/debug/label.vernac`:

```vernac
namespace example.debug;

term Label {description "A parcel's human-readable label.";}
value Label(String) behavior {
    public String decorate(String suffix) {
        String normalized = self.string().strip();
        String result = normalized.toUpperCase(java.util.Locale.ROOT);
        return result + suffix;
    }

    public String withMarker() {
        return self.string() + marker();
    }

    private String marker() {
        return "!";
    }

    public String externallyDecorated()
        implemented by example.debug.handwritten.LabelFormatting;
};
```

The external implementation at the end is intentional: later we will step from a
Vernac operation into handwritten Java. The example already contains that class.
Do not paste another copy of the model into the same namespace.

## Build once, then start an ordinary Java debug session

From the **Vernac repository root**, using JDK 25, run:

```text
mvn clean install
mvn -f examples/debugger/pom.xml clean test
```

Install the freshly built plugin ZIP from `vernac-intellij/target`, restart IntelliJ
and import `examples/debugger/pom.xml` as a Maven project. Wait for Maven import and
indexing. The application targets Java 21; the Vernac build needs JDK 25.

The example POM enables debug information for source lines and local variables.
Maven generation also writes the source mappings used by the plugin. Build before
placing breakpoints, and rebuild and restart debugging after source changes.

Open `label.vernac` and click the gutter beside:

```java
return result + suffix;
```

Debug the Java main class **`example.debug.demo.DebugWalkthrough`** using IntelliJ's
normal Debug action. No separate Vernac run configuration is required. When the
companion class loads and execution reaches the line, the editor should stop in
your Vernac file.

## Read the state of the operation

At this breakpoint the two preceding assignments have executed; the return has not.
Inspect the Variables panel:

| Name | Value at this stop | Meaning |
| --- | --- | --- |
| `self` | A `Label` instance | The value object whose behavior is running. |
| `suffix` | `"!"` | The method argument supplied by the Java caller. |
| `normalized` | `"parcel"` | Text after removing surrounding whitespace. |
| `result` | `"PARCEL"` | Text after uppercase conversion. |

`self` is the same access mechanism you use in the behavior. For a value object it
is the value object; entity and aggregate behaviors receive their generated Read or
Access view. The debugger currently displays those real runtime objects. A polished
Vernac-specific presentation of these views is future work.

Open **Evaluate Expression** and evaluate:

```java
result + suffix
```

The result should be `"PARCEL!"`. Try `self.string()` to see the original text,
including its spaces. These are Java expressions: method calls can execute code and
have side effects, exactly as in ordinary Java debugging.

You can also try adding `suffix` to Watches. Watches and more complex expressions
use the same Java context; the supported range of expressions remains subject to
IntelliJ's Java evaluator and the currently visible variables.

## Follow private helpers and handwritten Java

Use the normal Step Over, Step Into and Resume actions. Shortcut bindings depend on
your keymap. Source mapping covers the supported inline method bodies; generated
wrappers and unmapped positions can still appear while stepping. There is no promise
that every step skips all generated infrastructure.

Set a second breakpoint on `return "!";` inside `marker`. Resume until `withMarker`
calls it: private helpers are part of the same debugging workflow.

Next set an ordinary Java breakpoint in
`src/main/java/example/debug/handwritten/LabelFormatting.java`. The main calls
`externallyDecorated`, whose implementation lives there. The debugger should show
Java for that implementation. Vernac does not pretend that handwritten code lives
inside the DSL.

## Stop only when it matters, or log without stopping

Right-click the breakpoint on `return result + suffix;`. IntelliJ exposes its usual
breakpoint properties. To test a condition, use:

```java
suffix.equals("!")
```

Keep suspension enabled. Execution should stop because this call supplies `"!"`.
Change the condition to `suffix.equals("?")` and restart the run: that breakpoint
should no longer stop on this call. Restore or remove the condition afterwards.

A **logpoint** records information while the program continues. Remove the condition,
choose **Evaluate and log** / **Expression to log**, enter `result + suffix`, and
disable **Suspend**. Run again and look for `PARCEL!` in the debug console.

| Option | What it does |
| --- | --- |
| Log message | Prints IntelliJ's standard breakpoint-hit message. |
| Expression to log | Evaluates your Java expression and prints its value. |
| Suspend | Controls whether the debugger pauses at the breakpoint. |
| Condition | Controls which hits satisfy the breakpoint condition. |

A yellow Log indicator is an IntelliJ breakpoint setting, not a Vernac compiler
warning or an application logging level. Labels and layout vary across IntelliJ
versions. Restore Suspend and disable logging when you want an ordinary stopping
breakpoint again.

### Check expression editing separately

The expression editor and the runtime evaluator are separate IntelliJ mechanisms.
The 0.5.0 development plugin now maps the requested Vernac source line to its
verified generated Java scope for both Java and source-file expression requests.
This addresses the reported red variables and missing completion; installed-IDE
acceptance is still required and must not be inferred from successful evaluation.

In `UsecaseMappingProbe`, stop at `String suffixText = ...` in `FormatLabel`:

1. Open **Evaluate Expression** and enter `normalized`. Check that it resolves
   without a red unresolved-variable marker, then evaluate it.
2. Enter `norm` and press **Ctrl+Space**. Check that `normalized` is offered.
3. Try `self.` to inspect the generated input/dependency accessors.
4. Step past the assignment and check `suffixText` too. Before its declaration,
   it must not be offered as an already available local.
5. Repeat in a private helper and in a condition or **Expression to log** editor.
   Each editor must use its own requested method and line, not another selected
   frame's variables.

If resolution remains red, report it separately from runtime evaluation. Rebuild
the example and restart the debug session after installing the updated plugin.

### Read and Access views are real objects

At `Parcel` behavior and validation breakpoints, `self` is a generated Read or
Access facade, not the Parcel itself. The debugger keeps its actual Java type and
adds the contract name and role. Expand it to inspect stored properties directly,
without first following the technical enclosing-object reference. Usecase and
service validation inputs similarly expose their captured parameters under their
API names. Dependencies/configuration with simple stored accessors are included too.

This is an inspection presentation. The plugin reads fields over JDI and checks a
small supported subset of the loaded getter bytecode; it does not call getters,
behaviors or toString to assemble the view. Computed methods are not shown as stored
properties. If it cannot recognize the facade or validate its current source/runtime
mapping, normal Java presentation remains available. Empty stored-property views
explain that methods can be called explicitly through Evaluate Expression.

Optional properties are labeled **optional stored value**: the displayed child is
the actual nullable backing value, not a fabricated Optional object. A Watch created
from that child uses the accessor and `.orElse(null)` so its value matches this
projection. Your ordinary `self.note()` expression still returns Optional as before.
These projected children offer no Set Value action, even through an Access facade.
An Access view exposes mutation through its methods, not direct field writes.

Try this at `Parcel.changeUnits`: expand self, inspect id/label/units, and add units
to Watches. Its expression should address the facade's `units()` accessor. In a
validation, only the Read/input contract's stored properties should be offered.
Ordinary Java local variables keep their normal debugger editing behavior.

### Fewer stops in pure facade reads

Normal stepping can now step out of a verified Read/Access accessor that only
forwards to a stored field. It does not skip behavior bodies, computed read methods,
setters with guards, modification scopes, validation rules or callback-bearing
runtime helpers. Such frames can therefore still appear when you deliberately
step into an operation. The actual stack is not rewritten or shortened.

This rule participates in IntelliJ's stepping decisions, not breakpoint lookup.
It does not remove explicit breakpoints or exception requests. Use **Tools → Vernac
→ Show Generated Java in Debugger** and start a fresh session to inspect the raw
facades and disable this filter alongside Vernac source presentation. The filter
is deliberately narrow; it is not a package-wide skip rule.

### Changing values during a debug session

At a suspended frame, IntelliJ normally offers **Set Value** for a writable local
variable in **Threads & Variables**. For example, after `normalized` has been
assigned in `FormatLabel`, try changing that local string and evaluating it again.
This changes the paused execution for your experiment; it is not a Vernac model
operation or an application feature.

Do not expect the same action on every expanded object field. Value objects store
immutable `final` fields, and a validation `self` is a read-only view. The plugin
does not add setters or override Java's debugger write restrictions. Missing
**Set Value** on such a value is different from a missing writable local-variable
action. Report the latter separately, including the paused line and variable.

## Inspect a changing entity

The second model file, `src/main/vernac/example/debug/parcel.vernac`, adds a parcel
with a mutable quantity:

```vernac
namespace example.debug;

id ParcelId;
value Units(int) validates {
    require(self.intValue() > 0, "Units must be positive");
};
entity Parcel[ParcelId](Label, mut Units) validates {
    require(self.units().intValue() <= 10, "A parcel supports at most ten units");
} behavior {
    read String display() {
        String decorated = self.label().decorate("!");
        return decorated;
    }

    modify void changeUnits(Units units) {
        self.units(units);
    }
};
```

Set a breakpoint at `self.units(units);`. The main creates the parcel with one unit
and calls `changeUnits(Units.of(2))`. At the stop, inspect the `units` argument and
evaluate `self.units().intValue()`. Before the setter the current quantity is `1`;
after stepping over it the quantity is `2`.

This is the actual mutation, not a simulated debugger state or a transaction
rollback preview. The debugger does not change Vernac's mutation or validation
contracts. The `Units` validation block also supports a breakpoint on its `require` line;
the validation exercise below explores passing and failing rules.

## Follow a usecase into its private helper

Inline usecase `execute` blocks and private helpers now have source mappings too.
Open `src/main/vernac/example/debug/format-label.vernac`:

```vernac
namespace example.debug;

usecase FormatLabel(Label label, Label suffix) returns Label
validates {
    require(
        !self.label().string().isBlank(),
        "A label is required"
    );
} behavior {
    execute {
        String normalized = label.string().strip();
        String suffixText = suffix.string();
        String result = uppercase(normalized);
        return Label.of(result + suffixText);
    }

    private String uppercase(String normalized) {
        String result = normalized.toUpperCase(java.util.Locale.ROOT);
        return result;
    }
}
```

Set breakpoints on `return Label.of(result + suffixText);` and on `return result;`
in `uppercase`. Debug **`example.debug.demo.UsecaseMappingProbe`**. At the helper's
return you should see `normalized = "parcel"` and `result = "PARCEL"`. The private
helper has only its explicit arguments and locals, with no implicit `self`.

Resume to `execute`'s return. You should now see the `label` and `suffix` domain
arguments, `normalized = "parcel"`, `suffixText = "!"` and `result = "PARCEL"`.
The `self` object is the generated usecase Access view. Evaluate `result + suffixText`
to obtain `"PARCEL!"`. In a usecase declaring `uses`, its declared dependencies are
available through that Access view, as in the source code.

This small **mapping probe** deliberately constructs the generated object directly
and starts no Spring context or transaction. It tests source navigation and live
variables, not REQUIRED propagation. In a real application, inject the generated
usecase as a Spring bean and debug the call through that bean so its transaction
interceptor remains active. The existing persistence examples demonstrate that
application setup; source mapping does not change it.

A usecase using `execute implemented by ...` continues into its handwritten Java
implementation. Validates blocks have their own rule-entry mappings, described below. Generated
transaction and forwarding wrappers are still not copied inline source.

## Step through a domain service

A domain service coordinates behavior that does not belong to one model object.
Its named inline operations and private helpers now have native source mappings.
The example's `src/main/vernac/example/debug/label-preparation.vernac` contains:

```vernac
namespace example.debug;

service LabelPreparation behavior {
    public Label prepare(Label label, Label suffix)
    validates {
        require(
            !self.label().string().isBlank(),
            "A label is required"
        );
    } {
        String normalized = label.string().strip();
        String suffixText = suffix.string();
        String result = uppercase(normalized);
        return Label.of(result + suffixText);
    }

    private String uppercase(String normalized) {
        String result = normalized.toUpperCase(java.util.Locale.ROOT);
        return result;
    }
}
```

Set a breakpoint at `return result;` in `uppercase` and at
`return Label.of(result + suffixText);` in `prepare`. Debug
**`example.debug.demo.ServiceMappingProbe`**. The helper has `normalized = "parcel"`
and `result = "PARCEL"`. After resuming to `prepare`, `suffixText` is `"!"` and
`self` is the generated service Access view. Inspect the two Label arguments and
evaluate `result + suffixText` to obtain `"PARCEL!"`.

The probe constructs this dependency-free, stateless service directly. Unlike a
usecase, a domain service does not establish its own transaction. In an application,
Spring normally injects the service and its dependencies; a surrounding usecase owns
the transaction. These rules are unchanged by debugging support.

The same mapping machinery handles overloaded service operations. Service `uses`
dependencies remain available through `self`; private helpers receive only their
explicit arguments. `implemented by` implementations stay ordinary Java, and the
service's validates block has its own Read/input-view context, described below.

## Pause before an invariant is checked

A validation breakpoint belongs on the **`require` line**. Vernac maps that line to
the generated expression evaluation. The Read/input `self` is available, so you can
inspect the same values that the rule reads without gaining mutation access.

Open `parcel.vernac` and set a breakpoint on:

```text
require(self.intValue() > 0, "Units must be positive");
```

Debug **`example.debug.demo.ValidationMappingProbe`** with program arguments
`value` to exercise a valid value. Evaluate `self.intValue()` at the stop: it is `2`.
Resume and the probe reports `Validation passed: value`.

Run again with program arguments `value invalid`. The same breakpoint now exposes
`self.intValue() == 0`. Resume: the normal DomainValidationException is thrown and
the probe catches it to report the expected failure. It does not repair the value
or suppress an error in application code; catching the expected exception is part
of this controlled example.

Try the other kinds, setting only the breakpoint for the rule you want to inspect:

| Program arguments | Rule location | Value visible through self |
| --- | --- | --- |
| `entity` / `entity invalid` | Parcel's require in `parcel.vernac` | `self.units().intValue()` is 2 / 11. |
| `aggregate` / `aggregate invalid` | Batch's require in `batch.vernac` | `self.units().intValue()` is 2 / 11. |
| `usecase` / `usecase invalid` | The require in `format-label.vernac` | `self.label().string()` is `"parcel"` / `" "`. |
| `service` / `service invalid` | The require in `label-preparation.vernac` | `self.label().string()` is `"parcel"` / `" "`. |

For a multiline require, use its opening line, not an arbitrary continuation or the
message string. There is one mapped entry per rule, before its condition is evaluated.
The generated exception throw is not a second breakpoint on that rule. Compiler-
optimized constant conditions may have no executable location, just as in Java.

Step/exception handling may still expose generated throw or wrapper code. The
mapping does not hide the actual exception machinery. Default field null checks
and generated infrastructure checks are not user-authored requires and do not gain
fictional source mappings. Event-payload and adapter validation delegates are outside this rule-entry mapping
increment.

The usecase probe directly constructs an object to isolate validation; it has no
Spring transaction interception. Production usecases still run through their Spring
beans. Services still rely on their caller's transaction. Failed domain mutations
retain the ordinary Vernac state semantics; the debugger adds no rollback.

## Debug an event listener on its actual stack

Synchronous and deferred listener execute bodies and private helpers have the same
source mapping as usecases. Open `src/main/vernac/example/debug/label-events.vernac`:

```vernac
namespace example.debug;

event LabelPrepared(Label);

synchronous listener ObserveLabel on LabelPrepared behavior {
    execute {
        String text = event.label().string();
        String upper = uppercase(text);
        System.out.println(upper);
    }

    private String uppercase(String text) {
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        return upper;
    }
}

deferred listener DeliverLabel on LabelPrepared behavior {
    execute {
        String text = event.label().string();
        String upper = uppercase(text);
        System.out.println(upper);
    }

    private String uppercase(String text) {
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        return upper;
    }
}
```

Set a breakpoint at `System.out.println(upper);` in ObserveLabel or at its helper's
`return upper;`. Debug **`example.debug.demo.ListenerMappingProbe`** with no arguments.
The probe directly invokes the generated synchronous handler. Inspect `event`, its
payload and identity, `self`, `text = "parcel"` and `upper = "PARCEL"`. At the private
helper only its explicit text argument and upper local are present.

For DeliverLabel, set the equivalent breakpoint and run the probe with program
argument `background`. It directly invokes the generated deferred handler on the
thread named `vernac-listener-mapping-probe`. The stack belongs to that background
thread; the main thread waits for its result. There is no invented emitter frame.

**Probe scope:** these two runs isolate source mapping and thread/stack presentation.
They do not call emit, persist an event or a delivery, run JdbcEventWorker or establish
a database transaction. The example depends on the JDBC runtime and Spring JDBC so generated storage
classes for the deferred declaration compile; this probe never connects to a database.
Do not copy its direct handler invocation as an application event-publication pattern.

### Try the complete durable delivery path

To debug actual publication and the database-backed worker, use
[the persistence demo](../../examples/persistence-demo/README.md) with its PostgreSQL
container and normal startup configuration. Build with matching development artifacts,
then start its `org.example.delivery.demo.PersistenceDemoApplication` in Debug from
the IDE, rather than launching it as an unattached Maven process.

In `src/main/vernac/org/example/delivery/events.vernac`, set a breakpoint on the execute
line of ObserveDemoNotice and another on DeliverDemoNotice's execute line. Resume
between stops. The synchronous receiver runs inside publication's transaction. The
deferred receiver later runs from JdbcEventWorker's processing call, in its own
transaction; the original publishing call is not part of that stack. RetryDemoNotice
also demonstrates a deliberate first failure followed by another delivery attempt.

The [event tutorial](domain-events.md) explains delivery semantics. Breakpoints do
not create exactly-once processing or change retry/lease behavior. Long pauses can
allow a persisted claim's wall-clock lease to expire, particularly with other worker
processes running; use the local demo and interpret repeated delivery accordingly.
Ordinary Java breakpoints remain available in EventDemoProbe and JdbcEventWorker.

## Follow the adapter and its ACL

Business behavior does not end at the domain boundary. The same debugger mapping
now covers inline outbound adapter methods and helpers, Java mapping bodies and
explicit declarative assignments in inbound and outbound adapters.

The example adds `src/main/vernac/example/debug/label-adapters.vernac`:

```vernac
namespace example.debug;

port LabelArchive {
    Label load(Label label);
}

adapter LocalLabelArchive implements LabelArchive {
    behavior {
        java imports {
            example.debug.adapter.outbound.dto.LocalLabelArchiveDtos.Payload;
            example.debug.adapter.outbound.mapping.LocalLabelArchiveMappings;
        }
        public Label load(Label label) {
            String text = normalize(label.string());
            var payload = Payload.of(text);
            return LocalLabelArchiveMappings.toLabel(payload);
        }
        private String normalize(String text) {
            String normalized = text.strip();
            return normalized;
        }
    }

    schema Payload(String text);
    mapping toLabel: Label <- Payload {
        java {
            String text = source.text();
            return Label.of(text);
        }
    }
    mapping toPayload: Payload <- Label {
        text <- source.string;
    }
}

event LabelChecked(Label) validates {
    require(!self.label().string().isBlank(), "A checked label is required");
};
```

Rebuild the example and debug `example.debug.demo.AdapterMappingProbe`. It directly
invokes the adapter, mappings and an inbound controller. It needs no HTTP server,
Spring application context, database, credentials or remote system. Like the earlier
usecase probe, these direct calls do not establish Spring transaction interception.

Try these breakpoints, resuming between stops:

| Source line | What to inspect |
| --- | --- |
| `return normalized;` in the adapter helper | `text` and `normalized`; the private helper has no `self`. |
| `return LocalLabelArchiveMappings.toLabel(payload);` | Adapter `self`, `text` and `payload`. |
| `return Label.of(text);` in `toLabel` | Mapping parameter `source` and local `text`; a mapping has no `self`. |
| `text <- source.string;` in `toPayload` | `source.string()` evaluates to `"parcel"`. |
| Event's `require(...)` | Read-only event `self`, including payload and metadata. |

A declarative assignment maps to its generated conversion statement, before the
target local is initialized. Inspect `source` and already initialized locals; the
converted target value may only be available after stepping. Implicit defaults for
omitted optional fields do not receive invented Vernac breakpoints. Mapping helpers
may appear if you explicitly step into runtime conversion code. The current mapping
follows target-field order, so declarations written in a different order can be
visited in a different source-line order. No extra ordering guarantee is introduced.

To exercise a failing event validation, debug the same main class with argument
`event-invalid`. It catches the expected validation exception after your breakpoint.
The probe creates an event; it does not publish it or create an outbox delivery.

## Follow an inbound argument and response

The second file, `src/main/vernac/example/debug/label-api.vernac`, uses the existing
`FormatLabel` usecase:

```vernac
namespace example.debug;

adapter LabelApi inbound http uses FormatLabel formatLabel {
    base "/debug/labels";
    format {
        POST "/";
        body Request;
        execute formatLabel {
            label <- body.text;
            suffix <- body.suffix;
        }
        respond 200 Reply using output;
    }
    schema Request(String text, String suffix);
    schema Reply(String text);
    mapping output: Reply <- Label {
        text <- source.string;
    }
    mapping manual: Reply <- Label {
        java imports { example.debug.adapter.inbound.http.dto.LabelApiDtos.Reply; }
        java {
            String text = source.string();
            return Reply.of(text);
        }
    }
}
```

In the same probe, set a breakpoint on `label <- body.text;`. This stops at the
conversion to the usecase's Label argument. The actual controller frame contains
`this` and `__body`; evaluate `__body.text()` to inspect the request. Generated wire
and argument locals keep their actual Java names. The debugger does not manufacture
a domain `self` for a controller or rename its variables.

The 0.5.0 MVC adapter uses the same argument mapping mechanism. A breakpoint on
`noteText <- form.text;` in an `execute` block stops in its generated conversion
helper with the actual wire locals. A real-JVM regression test covers this path;
HTML/template expressions remain the template engine’s tooling. See the
[MVC walkthrough](inbound-mvc.md).

Set another breakpoint on `text <- source.string;` in `output`. The response mapping
sees the complete usecase result as `source`; its string is `"PARCEL!"`. The probe
also directly calls `manual`, so you can stop at `return Reply.of(text);` and compare
the Java mapping experience with the declarative version. Argument assignments are
executed in usecase-parameter order.

These are breakpoints on executable conversions. HTTP verbs, routes, status codes,
error-policy declarations and schemas are configuration, not artificial execution
steps. `implemented by` implementations continue to use their real Java files.
Adapter-method `validates` blocks are currently rejected by the language contract;
this debugger change does not introduce them. Existing VO and usecase validation
still runs normally when an adapter or mapping calls their factories or methods.

## If a breakpoint does not bind

First check that you built the example with matching compiler/runtime/plugin
artifacts and installed the new plugin. Choose an executable statement inside a
supported inline behavior; comments, empty lines and declarations alone do not
necessarily have a bytecode location.

If you edited the source, run the example's Maven build again and restart the debug
session. The plugin checks the source, generated Java and loaded companion
fingerprints. An old loaded class must not silently point to a new source line.
Unsaved source changes also affect this check. Do not edit generated files or the
source-map JSON to force a match.

The current discovery uses the standard Maven `src/main/vernac` and
`target/generated-sources/vernac` paths. Custom output directories, dependency-module
source attachment, remote debugging and hot swap are not established by this tutorial.

## When you want to inspect the generated implementation

Contributors sometimes need the opposite view. Enable **Tools → Vernac → Show
Generated Java in Debugger** before starting a new debug run. Uncheck it and start
another session to prefer Vernac source positions again. The switch changes source
presentation, not application behavior; it does not remove existing Vernac breakpoints.
You can always set normal Java breakpoints in generated or handwritten code.

## Coverage and what comes next

| Area | Current state |
| --- | --- |
| Inline VO, entity, aggregate and collection behaviors; private helpers | Source mapping is implemented. The walkthrough exercises VO and entity behavior. |
| Breakpoints, stepping, visible variables, Evaluate Expression, expression completion and conditions | Confirmed in installed IntelliJ sessions for the exercised core paths. |
| Logpoints and Watches | Java context is connected; use the exercises above to check your IDE configuration. |
| Handwritten `implemented by` implementations | Normal Java debugging. |
| Inline usecase execute and private helpers | Mapping and real-JVM checks implemented; initial installed-IDE walkthrough confirmed. |
| Inline domain service operations and private helpers | Mapping and real-JVM checks implemented; initial installed-IDE walkthrough confirmed. |
| Require rules for VOs, entities, aggregates, usecases and domain services | One rule-entry mapping per require; passing/failing real-JVM checks implemented and the initial require walkthrough confirmed in the IDE. |
| Synchronous/deferred listener execute and helpers | Source mapping and real-JVM thread/stack tests implemented; the probe isolates mapping and the persistence demo exercises real delivery. |
| Outbound adapter Java methods and private helpers | Source maps and real-JVM breakpoint/local-variable tests implemented. |
| Java mapping bodies, explicit declarative mappings and inbound execute argument assignments | Both directions mapped; real-JVM entry-point/local-variable tests implemented. |
| Event-payload require rules | Rule-entry mapping, including passing/failing real-JVM checks. |
| Adapter/mapping installed-IDE acceptance | Confirmed in the user walkthrough; JVM tests do not establish every IntelliJ UI interaction. |
| Read/Access/input view presentation and pure-facade-read stepping | Implemented with field-only JDI inspection and conservative bytecode checks; native tree/Watch/stepping acceptance is separate. |
| Broader wrapper/runtime stepping filters and cross-language refactorings | Future work; not implied by the narrow facade filter. |

Deferred listeners remain separate executions with their own transactions. Source
mapping preserves the actual call stack; it does not join the emitting usecase to a
later worker execution.

Continue with [domain behavior](behavior.md), [usecases](usecases.md) or
[the IDE setup guide](intellij-plugin.md). Contributors can inspect the
[source-mapping design and acceptance plan](../development/vernac-debugger-prototype.md).
