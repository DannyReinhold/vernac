---
title: DSL debugger and verification lessons
---
# Debug the original source without inventing execution

[Lessons learned](index.md) · [Documentation map](../../documentation-map.md)

## Decide which source coordinates you can prove

Generation introduces a second source file and a third runtime representation.
Line-number similarity is not enough to connect them. Vernac keeps source mappings
for reviewed user-authored bodies and validation/conversion entrypoints, with
source/generated hashes and runtime companion fingerprints. Changed or ambiguous
mappings fall back to Java rather than selecting a plausible but wrong location.

Keep paths relocatable and distinguish source provenance from runtime identity.
The debugger must be able to identify the loaded class and class loader, not just a
local `.java` file with the right name. Unsaved edits after compilation do not mean
the running JVM now executes the new text.

Copied Java bodies, declarative mappings and generated wrappers need different
mapping rules. A copied statement has a user-source range. An implicit default or
infrastructure wrapper does not acquire a fictitious DSL statement just to make
stepping look smooth. Constant-folded expressions can have no executable bytecode
location; breakpoint placement and binding must reflect that.

Vernac's bridge uses Java debugger integration, including a position manager and a
Java-backed line breakpoint type. This reuses JVM execution rather than building a
second evaluator. See the [debugger implementation notes](../vernac-debugger-prototype.md)
for the concrete mapping format and supported contexts.

## Navigation and evaluation need different contexts

A debugger can stop on the correct DSL line, show locals and evaluate expressions,
yet display those same locals in red in the expression editor. Runtime evaluation
and editor resolution are different paths.

The visible source position belongs in the DSL. Java evaluation and its fragment
editor need the mapped Java method's PSI scope, including parameters, locals and
the actual receiver type. Vernac supplies an evaluation position and code-fragment
factories for both explicit Java and implicit host-file requests. It preserves an
already valid Java context rather than replacing it indiscriminately.

Context must come from the requested source location, not whichever stack frame
happens to be selected globally. Separate methods can contain identical statements;
body-text matching alone cannot identify a scope. Host PSI granularity matters too:
a single undifferentiated leaf can collapse position lookup to the start of a file.

Test Evaluate Expression, Watches, completion, conditional breakpoints and logged
expressions individually. A successful evaluation does not establish editor
highlighting or Ctrl+Space behavior. Logpoints and breakpoint conditions are
standard IntelliJ features; the plugin's job is to give their Java expressions the
correct source/runtime context.

## Breakpoint callbacks can recurse

One Vernac regression caused a `StackOverflowError` when setting a breakpoint.
The breakpoint type's source-position callback called the breakpoint's own
source-position accessor, which called the callback again.

The remedy was to preserve the normal DSL source position without requesting it
recursively in that callback. Evaluation obtains Java context through its separate
mapping path. A regression test uses an accessor that fails immediately if called.

General lesson: when implementing an extension callback, establish whether the
accessor you are about to call is implemented *through that same extension point*.
This applies to references, scopes and renderers as well as breakpoints.

## Make generated code less distracting without hiding behavior

Read/Write/Access views are real access boundaries. A debugger may label them more
clearly and expose useful state, but must not pretend the facade is the domain
object or give a read-only projection a mutation operation.

Vernac's view inspection accepts a conservative subset of verified getter bytecode
and reads backing fields through JDI. It does not invoke arbitrary getter methods
while expanding a tree. Computed getters and unsupported shapes fall back to
normal presentation. Optional storage projections are labelled as such. This avoids
executing business behavior merely because a user opens `self`.

Similarly, filter only verified trivial facade accessors when stepping. A blanket
filter for the generated package can skip validation, event callbacks or other
business-relevant execution. Retain explicit/exception breakpoints and actual
thread/stack structure. Deferred delivery really executes later, on a worker: do
not manufacture a continuous emitter stack.

A contributor mode that shows generated Java remains useful. It should disable
presentation conveniences without changing application behavior. Test both modes.
Missing Set Value on a final VO field is not automatically a plugin defect; preserve
actual JVM/type constraints. Editing ordinary mutable locals is a separate case.

## Use a verification ladder

| Evidence | Establishes | Does not establish |
| --- | --- | --- |
| Compiler/model tests | Signatures, source ranges, visibility, recovery | Native editor behavior |
| Generated-Java compilation | The projection/API is legal Java | IDE indexing or lookup insertion |
| Exact target-SDK compilation | APIs and overrides are available | Correct extension registration at runtime |
| Plugin component tests | Registration, predicates and isolated policies | A running IDE's completion/evaluator |
| Real-JVM/JDI tests | Executable locations, loaded fingerprints, locals, field reads | IntelliJ gutter, popup or step UI |
| Packaged installed-IDE test | The exercised user gesture and distribution work together | Every IDE version or unrelated context |

Use each layer for the failure it can detect. Do not call a skipped PostgreSQL test
a pass, a JDI probe a Spring transaction test, or SDK compilation an end-to-end IDE
test. Preserve a small runnable example with observable behavior for manual
acceptance. The [debugging tutorial](../../tutorials/debugging.md) is both a learning
path and such an acceptance guide.

For a release, test the packaged plugin after restart, not only classes loaded in
a test harness. Check compiler/runtime dependencies in `plugin/lib`, keep the LSP
server distribution/classloader separate, and avoid bundling platform libraries.
Recheck plugin unload/disposal and target-IDE compatibility. Deprecated API warnings
are upgrade signals, not something a passing compiler build disproves.

## A compact acceptance matrix

For each supported body kind, exercise the following across LF/CRLF and an unsaved
edit followed by rebuild:

1. Create, remove, disable and re-enable a breakpoint; distinguish unbound locations.
2. Stop, step into/out/over and navigate back to source.
3. Inspect locals and `self`; expand views without executing hidden behavior.
4. Evaluate an expression, complete a local name, add a Watch and set a condition.
5. Use a non-suspending logpoint, then resume through normal failure handling.
6. Modify source after compilation and check conservative stale-map behavior.
7. Enter handwritten Java and return; compare contributor mode.

Test concurrent/deferred contexts with their genuine thread and transaction
boundaries. Remote attach, module source attachment and Hot Swap need additional
acceptance scenarios; source mapping alone is not evidence for them.

## Forked JVMs and Windows command length

A native PSI test may need its own JVM to isolate IntelliJ's application singleton.
Do not pass the entire SDK classpath directly on its process command line: Windows
can reject it with error 206 ("The filename or extension is too long") even when
all individual files have short names. Renaming test methods does not fix that.

Use the Java launcher's `@argument-file` support, quote each argument and escape
backslashes and quotes according to Java's argument-file grammar. Pass the short
argument-file path directly to `ProcessBuilder`, without invoking a shell. Keep the
file and probe logs within the test's temporary directory. Test paths containing
spaces and launch with a classpath exceeding Windows' command-line limit; Linux
alone will otherwise hide the original failure. See the
[Java launcher reference](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html).
