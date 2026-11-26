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

Scenario predicates currently support fixture paths, enum members, scalar literals, integer and decimal arithmetic
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
Native state rules currently support field paths, integer and decimal arithmetic, equality and Boolean
operators.

Entity and aggregate methods can opt into native execution with a `vernac` body:

```vernac
modify void renameTo(DocumentTitle title) vernac {
    self documentTitle = title.
}
```

Native bodies currently support `require … else …`, assignments to mutable `self` fields and a
final value return. They use the same expression evaluator as state rules. The existing JVM
method signatures and modification boundary still apply. Native bodies and state rules can match
optional field values and call declared read methods on `self` using keyword messages. The
present branch can read fields from a bound value object. A native modification can send
a keyword message to another modify method on `self` as a statement. It joins the enclosing
modification and validates at the outer boundary.

A use case can use the same native syntax for an operation with declared ports and event effects:

```vernac
execute vernac {
    documentStore delete: documentId.
    events emit: (DocumentDeleted documentId: documentId).
}
```

Port selectors are resolved against the declared dependency. An event constructor names its fields
in declaration order. The existing generated Use Case entry point still establishes authorization,
the transaction and event delivery. Use `let` to evaluate an expression once and keep its
typed result for later statements. A declared repository supports `byId:`, `save:` and
`delete:`; a loaded aggregate can receive its declared read and modify messages.

```vernac
execute vernac {
    let booking = bookings byId: bookingId.
    let oldStart = booking start.
    booking moveFrom: previous to: destination.
    bookings save: booking.
    return Result oldStart: oldStart newStart: booking start.
}
```

A compound result uses the generated `Result` contract. Its keyword parts must name the
declared fields in order. Native execute currently requires non-optional inputs and accepts
void, a non-optional single result, or a compound result with supplied field values.
Custom repository queries are outside the initial native repository interface.

## Calculation coverage

Use decimal literals such as `0.19` for `BigDecimal` arithmetic in behaviors, validations and scenario
predicates. Use the same numeric type for both operands. Decimal operators include negation, `+`, `-`,
`*`, `/`, `%` and ordering. Decimal `==` compares numeric values, so `0.30 == 0.3` is true. Division
is exact. A zero divisor or a non-terminating decimal quotient raises an arithmetic error. A scenario
can use decimals in fixture constructors and compare a computed result using `expect (…)`.

```vernac
read BigDecimal totalFor(BigDecimal quantity, BigDecimal discount) vernac {
    let net = self price amount * quantity - discount.
    require net >= 0.0 && quantity > 0.0 else InvalidTotal.
    return net + net * 0.19.
}
```

An `int` can enter a decimal calculation explicitly with `asDecimal`. Use
`roundedTo:mode:` with an integer scale and a `RoundingMode` constant to round a result. Use
`dividedBy:scale:mode:` when the quotient needs rounding. These selectors are available
in behavior expressions, validations and scenario predicates.

```vernac
read BigDecimal roundedTotalFor(int quantity, TaxRate taxRate) vernac {
    let net = self price amount * quantity asDecimal.
    let rate = (taxRate == TaxRate REDUCED) ifTrue: 0.07 ifFalse: 0.19.
    return (net + net * rate) roundedTo: 2 mode: RoundingMode HALF_UP.
}

expect ((1.0 dividedBy: 3.0 scale: 2 mode: RoundingMode HALF_UP) == 0.33);
```

`ifTrue:ifFalse:` is a Boolean expression message. The runtime evaluates the condition once
and evaluates only the selected alternative. Both alternatives must have the same type and
optionality. A comparison against an enum member can supply the condition. The branch result
can be used as a local value, a message argument, a validation result or a return value.

Use `LocalDate fromIso:` to parse a calendar date, `plusDays:` to move a calendar deadline, and
`daysUntil:` to calculate the signed distance in days as an `int`. For elapsed time, construct a
`Duration` with `ofDays:` and combine durations with `plus:` or `minus:`. A day in a `Duration`
is 24 hours. A `LocalDate` carries no time zone.

```vernac
let issued = LocalDate fromIso: "2024-02-28".
let due = issued plusDays: 30.
require (issued daysUntil: due) == 30 else InvalidTerms.
```

Declared domain lists and sets support `select:`, `sumBy:` and `anySatisfy:` with a typed expression
block. The binding before `|` names the current element and is scoped to the block. `select:` keeps
the declared collection type. `sumBy:` accepts an `int` or `BigDecimal` result and uses zero for an
empty collection. `anySatisfy:` stops at the first matching element. The filtering and matching blocks
require a Boolean result. Blocks in these queries are read-only expressions.

```vernac
let taxable = lines select: [line | line taxable && line amount >= threshold].
return taxable sumBy: [line | line amount].
```

Use `if` in a native body to choose a block of statements. The condition must be a non-optional
Boolean. The runtime evaluates it once and runs only the selected block. Locals declared in a
branch end at the closing brace. Read behaviors keep their existing effect restrictions, and
assignments and modify messages in a branch participate in the enclosing modification boundary.
For a value-returning behavior, every path must return a value. An optional `else` supplies the
statements for the false branch.

```vernac
if approved {
    self replaceWith: requestedSeats.
} else {
    require self seats count > 0 else ApprovalRequired.
}
```

Use `collect:into:` to map a domain list or set into a declared target collection. The block must
return a non-optional value of the target element type. A target list keeps encounter order and
duplicates. A target set applies its usual equality and deduplication rules.

```vernac
return lines collect: [line | line unitPrice] into: Prices.
```

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
