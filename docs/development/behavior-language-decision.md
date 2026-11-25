---
title: Choosing a surface syntax for interpreted Vernac behaviors
---

# Choosing a surface syntax for interpreted behaviors

**Selected direction.** Vernac uses Smalltalk-style keyword messages for domain calls and conventional
operators for arithmetic, comparisons and short-circuit Boolean expressions. Behaviors, validations and
scenarios share the expression grammar. `require`, `match`, `when` and `expect` express control flow and
test intent explicitly. The precise declaration syntax remains part of B1.

A keyword selector comprises all its parts. `document moveFrom: inbox to: archive` names
`moveFrom:to:`. Unary messages associate left to right, so `document owner email` first reads the owner.
Unary sends bind before prefix operators, then multiplication, addition, comparisons, equality, logical
AND and logical OR. Keyword sends bind last. Parentheses delimit nested keyword sends and allow their
results to be operands of other expressions.

The first implementation parses these expressions and exposes read-only scenario predicates:

```vernac
expect (task status == Status DONE && task title string != "");
```

Scenario predicates currently support fixture paths, enum members, scalar literals, integer arithmetic
and comparisons, value equality, Boolean operators and optional matching:

```vernac
expect (match result {
    present title => title string == "Try Vernac"
    absent => false
});
```

The match subject is evaluated once. Only the selected branch runs, the payload binding is available
only in the present branch, and both branches must agree on result type and optionality.
A scenario can also call a declared behavior with `when (document moveFrom: inbox to: archive);`.
For existing JVM-backed behaviors, the first keyword matches the method name and subsequent
keywords match parameter names in order. `expect` can call declared read behaviors, while
`when` also allows modify behaviors. The surrounding suite and fixture syntax stays compatible
with 0.6.

Values, entities, aggregates and events can declare a native state rule:

```vernac
validates {
    require self start intValue <= self finish intValue else InvertedDates.
}
```

The compiler checks that state reads use the read interface and that the predicate yields a
Boolean. The runtime evaluates the compiled predicate against the candidate state. A false
predicate raises a domain validation exception with code `InvertedDates`. A scenario can
assert that code with `when (booking moveFrom: start to: finish) rejects InvertedDates;`.
Native state rules currently support field paths, integer arithmetic, equality and Boolean
operators. Native behavior bodies, keyword calls within validation rules and optional matching
within validation rules remain open.

## Alternatives considered

A document moves between folders. Both arguments have type `FolderId`, so seeing which is the source
matters. The three **syntax specimens** illustrate proposals for B1. Give every candidate the same
receiver, domain preconditions and argument values.

| Candidate | Call site | Question for the prototype |
| --- | --- | --- |
| Java-shaped positional call | `document.move(inbox, archive)` | How clearly do the method name, completion and parameter hints expose two arguments of the same type during review? |
| Kotlin-influenced receiver call | `document.move(from = inbox, to = archive)` | Do argument labels make a diff self-explanatory? Specify label stability across module versions and detect duplicate or unknown labels. |
| Smalltalk-influenced keyword message | `document moveFrom: inbox to: archive` | Does a readable domain sentence justify the selector and precedence rules? Can completion offer the next keyword and can a rename update the whole selector? |

The named-argument candidate borrows a spelling from Kotlin. Keyword messages borrow the interleaving of
selector parts and arguments from Smalltalk. Neither spelling requires a second host-language toolchain.
Keep existing Java bodies and typed JVM delegates available during migration. An actual embedded Java or
Kotlin body remains host code whose dependencies and effects may need conservative treatment.
[Kotlin function calls](https://kotlinlang.org/docs/functions.html#named-arguments) ·
[Pharo by Example, message syntax](https://books.pharo.org/updated-pharo-by-example/pdf/2018-09-29-UpdatedPharoByExample.pdf)

Express the **same transition** in both leading prototypes. This illustrative body uses the dot-call
candidate and leaves the opt-in header spelling for B1.

```text
modify move(from: FolderId, to: FolderId) {
    require(self.folder == from, WrongSource)
    require(from != to, SameFolder)
    self.folder = to
}
```

The caller can then save the aggregate and emit a typed `DocumentMoved` event through the use case. Here
is the same application step in each candidate. The keyword prototype treats the expression in parentheses
as the event argument.

```text
document.move(from = inbox, to = archive)
store.save(document)
events.emit(DocumentMoved.of(document.id, archive))
```

```text
document moveFrom: inbox to: archive.
store save: document.
events emit: (DocumentMoved document: document id destination: archive).
```

In both prototypes also write a query with an optional result and a paged projection equivalent to the
wizard's `ReadTasks`. Try completing the event constructor in the editor after inserting another argument.
This probes readability and recovery for nested calls.

## Rules the two prototypes share

| Area | Design constraint and proof |
| --- | --- |
| Types and control | Resolve IDs, values, enums, typed collections, optionals, local bindings, conditionals, returns and short-circuit boolean expressions. Specimens cover comparisons, enum cases, missing optionals and paged read projections. A `FolderId` in place of a `DocumentId` is a source error. Specify comparisons, equality and operator precedence with executable cases. |
| Capability by context | Read operations, validation and assertions have explicit read capabilities. A `modify` operation can stage changes to its aggregate. A use case can invoke declared repositories/ports and request event emission. Reject a write in an assertion, a repository call from a view, and a host call with undeclared effects. Test fixtures observe authorized operations through the supported entry points. |
| Rejection and side effects | Declare typed rejections, outermost validation and event observation points. Exercise an interval transition with two writes, a temporary invalid state, then success or final rejection. Check held child references, timestamps and the absence of published events on rejection. Keep effectful providers outside the staged aggregate transition. A call into opaque Java that could mutate a live candidate requires a defined bridge or a located rejection. |
| JVM contracts | Map each resolved operation unambiguously to the existing JVM-facing member conventions. Verify overloads after erasure, optional/null handling and stable public names. Compile a handwritten Java caller and a delegated provider against the bridges. Document the effect of renaming a public argument label or selector. |
| Compatibility and tools | Require an explicit marker for new bodies. Existing inline Java and `implemented by` continue through their declared paths. Preserve the 0.6 assertion grammar and its meaning. Resolve before interpretation and track member and source spans for diagnostics, LSP, formatter and debugging. |

## B1 conformance work

1. Cover document move, task completion/reopening, document access and deletion, a read projection,
   nested invariant rejection and a failed port operation. Record expected writes, effects and source
   locations, including one illegal use of each capability.
2. Verify incomplete selector completion, whole-selector rename and diagnostics for a swapped folder
   argument. Keep JVM names stable through the explicit bridge and check call sites in generated and
   handwritten code.
3. Measure parse/check latency on the same edits as the baseline. Record the final opt-in and migration
   rules and link runnable examples to the conformance cases.

The 0.6.0 release can settle fixture, assertion and observation semantics while this spelling decision
stays open. The 0.7.0 specification should link the final choice to conformance cases shared by the CLI,
IDE and production interpreter.
