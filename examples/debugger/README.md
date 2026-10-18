# Debug a Vernac behavior

This database-free example exercises the 0.5.0 development debugger integration.
For the guided explanation, expected values and breakpoint exercises, follow the
[debugging tutorial](../../docs/tutorials/debugging.md).
Use JDK 25 to build Vernac and install the matching plugin ZIP. The application
itself targets Java 21. From the repository root:

```text
mvn clean install
mvn -f examples/debugger/pom.xml clean test
```

Import this example's POM into IntelliJ and allow Maven indexing to complete. Build
it with Maven before placing breakpoints; the plugin currently discovers standard
`src/main/vernac` and `target/generated-sources/vernac` locations.

1. Open `src/main/vernac/example/debug/label.vernac`.
2. Set a gutter breakpoint on `return result + suffix;` inside `decorate`.
3. Debug the ordinary Java main class `example.debug.demo.DebugWalkthrough`.
4. The breakpoint should bind when the companion class loads and the editor should
   stop on the Vernac line. Inspect `self`, `suffix`, `normalized` and `result`.
   At this point they represent Label, `!`, `parcel` and `PARCEL` respectively.
5. Try another breakpoint in the private `marker` helper, and a regular Java
   breakpoint in `handwritten/LabelFormatting.java`. Use Continue between the stops.
6. Try `Parcel.changeUnits` and `Parcel.display`. Their `self` values are actual
   access-view objects; polished domain renderers are follow-up work.

Under **Tools → Vernac → Show Generated Java in Debugger**, enable contributor mode
before starting another debug run to inspect generated Java source positions. Disable
it to prefer mapped Vernac positions again. Ordinary Java breakpoints remain useful.
The switch affects source presentation; it does not change the application or compilation.

## Build and source consistency

The compiler writes `.vernac-behavior-debug.json` beside its generated Java sources.
Mappings use relative source paths and hashes, including a private fingerprint in
behavior companion classes. Unsaved changes or stale generated/loaded code are not
silently treated as the current model. Rebuild and restart the debug session after
edits. Source line endings are normalized for Windows CRLF/editor compatibility.

A breakpoint needs an actual bytecode location: comments, declarations and empty
lines are not executable merely because they appear inside a behavior. Build debug
information with `lines,vars,source`; this POM explicitly enables it. Do not hand-edit
maps or generated classes to make a breakpoint bind.

## Prototype boundaries

This first increment maps inline VO/entity/aggregate/collection behavior bodies and
private helpers. `implemented by` code stays ordinary Java. Adapter bodies, lambda/local-class mapping, polished stepping filters,
custom frame labels and rich domain renderers are not included. Expression evaluation
uses the mapped Java method scope; the acceptance checks below cover this integration. A wrapper or unmapped position may still open generated
Java. Hot swap, remote source attachment and external-module behavior debugging are
not established by this example. Exceptions remain normal JVM exceptions.

The automated JVM test compiles this model, launches a real debugger connection,
hits the mapped Java location and checks the runtime fingerprint and live variables.
The example's tests also verify its normal behavior. Neither replaces the installed
IntelliJ walkthrough above: editor registration, gutter behavior and stepping need
that separate acceptance check.

## Evaluate expressions and use logpoints

At the `return result + suffix;` breakpoint, open **Evaluate Expression** and evaluate
`result + suffix`. The expected result is `PARCEL!`. Also try `self.string()` and add
`suffix` to Watches. These are Java expressions, with the real method parameters and
locals; no additional Vernac expression syntax is involved.

Right-click the breakpoint to open IntelliJ's standard breakpoint properties:

| Setting | Meaning | Example |
| --- | --- | --- |
| Condition | Stop only when this Java expression evaluates to true. | `suffix.equals("!")` |
| Log message | Write a standard breakpoint-hit message to the debug console. | No expression needed. |
| Evaluate and log / Expression to log | Evaluate a Java expression and write its value to the debug console. | `result + suffix` |
| Suspend | Pause execution at the breakpoint. Disable it for a logpoint that continues automatically. | Disable while testing logging. |

Labels and layout vary between IntelliJ versions. A yellow Log indicator belongs to
IntelliJ's breakpoint UI, not to a Vernac warning or application log level. Inspect
the breakpoint properties to see which logging options are enabled. Plainly enabling
and disabling a breakpoint does not itself require enabling expression logging.

Check conditions and logged expressions on the same executable line as the example.
Then use **View Source** in the Breakpoints dialog: it should still navigate to Vernac.
Restart debugging after installing the updated plugin. Rebuild first if sources changed.
Expressions may call methods and have side effects, just as in ordinary Java debugging.

The plugin supplies Java scope separately from Vernac navigation. It does not invent
missing locals or evaluate against another method when a source mapping is stale.
The new scope-routing test and SDK compilation check this separation; the installed
IDE check above is required to confirm expression editing, evaluation and logpoints.

Breakpoint positions always retain the Vernac file and line. The Java context is
supplied separately for runtime evaluation. The expression-editor increment also
routes source-file and explicit Java requests through the verified Java scope for
the requested Vernac line. In `FormatLabel`, stop at `String suffixText = ...`,
check that `normalized` is not red, and try `norm` + Ctrl+Space and `self.`.
Repeat after stepping and in a helper. These are installed-IDE acceptance checks;
passing runtime evaluation alone does not prove editor completion works.


## Usecase execute and helper probe

Open `src/main/vernac/example/debug/format-label.vernac`. Set a breakpoint at
`return result;` in `uppercase` and another at `return Label.of(result + suffixText);`
in `execute`. Debug `example.debug.demo.UsecaseMappingProbe`.

The helper exposes `normalized = "parcel"` and `result = "PARCEL"`; it has no implicit
self. At execute's return, the same strings plus `suffixText = "!"`, the two Label
arguments and the usecase Access self are visible. Evaluate `result + suffixText`.
It should produce `PARCEL!`.

The probe intentionally constructs the generated usecase directly without a Spring
context or transaction. It isolates debugger mapping and must not be copied as an
application transaction setup. Real applications inject the Spring usecase bean so
REQUIRED interception applies. No persistence, emits or transaction behavior are
exercised by this probe. Externally implemented execute bodies stay in Java.


## Domain service operation and helper probe

Open `src/main/vernac/example/debug/label-preparation.vernac`. Break on `return result;`
in `uppercase` and `return Label.of(result + suffixText);` in `prepare`. Debug
`example.debug.demo.ServiceMappingProbe`. The helper has normalized `parcel` and
result `PARCEL`, without an implicit self. The public operation additionally exposes
its service Access self, Label arguments and suffixText `!`. Evaluating
`result + suffixText` should produce `PARCEL!`.

This dependency-free domain service starts no transaction; an application usecase
normally supplies that boundary. External implementations remain Java; validation
blocks have separate rule-entry mappings; see the validation exercise below.


## Validation rule probe

Set a breakpoint on a `require` opening line and debug
`example.debug.demo.ValidationMappingProbe` with one of `value`, `entity`,
`aggregate`, `usecase`, `service` as program arguments. Append `invalid` to exercise
the failing case. Default arguments select a valid value object.

- `value`: Units rule in parcel.vernac; inspect `self.intValue()` (2 or 0).
- `entity`: Parcel rule in parcel.vernac; inspect `self.units().intValue()` (2 or 11).
- `aggregate`: Batch rule in batch.vernac; the same quantity getter shows 2 or 11.
- `usecase`: FormatLabel rule; `self.label().string()` is parcel or a blank string.
- `service`: LabelPreparation rule; the same label getter is available on its input view.

The probe catches expected DomainValidationException failures and exits normally.
A rule maps once, before condition evaluation; generated throws do not create a second
breakpoint. For multiline require, place the breakpoint on the opening require line.
Generated null checks, event-payload/adapter validation and constant expressions optimized
out of bytecode are outside the supported rule-entry locations. The usecase probe
still intentionally starts no Spring context/transaction. See the tutorial for details.


## Listener execute and helper probes

Open `src/main/vernac/example/debug/label-events.vernac`. Break on
`System.out.println(upper);` or `return upper;` in the listener of interest.
Debug `example.debug.demo.ListenerMappingProbe` without arguments for ObserveLabel,
or with `background` for DeliverLabel. The latter runs on the named thread
vernac-listener-mapping-probe. At execute, inspect event, self, text parcel and
upper PARCEL; the private helper has only text and upper.

This is a direct invocation/thread mapping probe. It does not emit, persist, dispatch
through an outbox, start JdbcEventWorker or establish a database transaction. The
JDBC runtime and Spring JDBC dependencies allow generated deferred-event storage classes to
compile; the probe opens no connection. For the real synchronous publication and
deferred processing/transaction lifecycle, debug the
[persistence demo](../persistence-demo/README.md) and its events.vernac receivers.
The [tutorial](../../docs/tutorials/debugging.md) explains both exercises and their
scope. Deferred processing always retains its own actual stack.

## Adapter, mapping and event-validation probe

Rebuild and debug `example.debug.demo.AdapterMappingProbe`. Open `label-adapters.vernac`
and `label-api.vernac` in `src/main/vernac/example/debug`. Suggested breakpoints:

- Adapter helper `return normalized;`, then the `load` method's mapping call.
- Java mapping `return Label.of(text);` or inbound `return Reply.of(text);`.
- Declarative `text <- source.string;` in each adapter's mapping.
- Inbound `label <- body.text;` and event `require(...)`.

Mappings expose `source`; inline public adapter methods expose `self`; private
helpers expose their parameters and locals. The inbound controller has `this` and
`__body`, with the actual generated Java argument names. Inspect the source before
a conversion and step to inspect its result. Field/argument execution follows the
target-field/usecase-parameter order, not an extra source declaration-order contract.

The probe calls generated objects directly: no HTTP server, database, Spring
transaction interception, external service or event delivery. Argument `event-invalid`
exercises the expected event validation failure. [The tutorial](../../docs/tutorials/debugging.md)
contains both complete models and the acceptance steps. Install the matching plugin
and check Evaluate Expression/Completion in these newly mapped frames as well.

## Inspect Read/Access facades

At a Parcel behavior or require breakpoint, expand `self`. The renderer retains
its real Java facade type and adds the public contract/role. Stored properties
appear directly, while guard/enclosing-reference plumbing is omitted from this
presentation. Captured validation parameters use their accessor names. Computed
methods are not executed for display. Optional children are explicitly labeled
nullable stored-value projections; Watches use the accessor plus `.orElse(null)`.
Projected children are read-only; an Access facade still mutates only via methods.

Normal stepping skips only verified pure facade field reads. It does not hide
behavior, validators, guards, modification scopes or actual stack frames. To inspect
raw implementation details, enable **Show Generated Java in Debugger** before a
fresh debug session. This also disables the facade renderer/filter.

Acceptance: inspect ParcelRead/ParcelAccess and FormatLabel's validation input;
add `units` to Watches, try an optional property in your own model, compare normal
stepping with contributor mode, and verify explicit/exception breakpoints still
stop where requested. The real-JVM inspector test is not an automated IntelliJ UI test.
