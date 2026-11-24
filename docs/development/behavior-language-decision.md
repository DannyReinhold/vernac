---
title: Choosing a surface syntax for interpreted Vernac behaviors
---

# Choosing a surface syntax for interpreted behaviors

**The syntax decision is open.** The proposed starting point is typed receiver calls with dot notation,
named arguments where they clarify domain intent, and the existing Vernac vocabulary for operations and
effects. Smalltalk-style keyword messages are a second prototype worth trying with real domain sentences.
Both candidates also support a Smalltalk-like editing experience in the daemon. Change an operation, send
it to a fixture object and immediately inspect the outcome. The implementation target in both cases is a
Vernac interpreter with an explicit JVM boundary. [Milestone B1](release-0.7.0.md#implementation-sequence)

## Compare the same operation

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

## B1 decision procedure

1. Freeze a small common corpus with independent expected values from 0.6. Cover the document move, task
   completion/reopen, document access and deletion, a read projection, nested invariant rejection and one
   failed port operation. Annotate the expected writes, effects and source locations. Include one illegal
   use of each capability. Implement a bounded parser/lowering prototype for each leading spelling against
   the same small evaluator.
2. Have reviewers read equivalent diffs and describe the intended domain action, order of effects and
   failure outcome. Try a parameter rename, a swapped folder argument and an incomplete call in IntelliJ.
   Have an agent repair the same incorrect call using only the diagnostic and its source. Capture
   examples, diagnostic ranges, completion results and changes to the 0.6 corpus. A positional call with
   swapped same-typed arguments is a useful review probe even when it type-checks.
3. Compare domain readability, learnability from existing Vernac, parser ambiguity/precedence, error
   recovery, JVM bridge stability, compatibility and impact on tooling. Verify both leading candidates
   lower equivalent programs to the same typed operations. Measure parse/check latency on the same edits.
   Revisit bytecode generation only in the performance workstream if a measured execution bottleneck
   warrants it.
4. Record the chosen spelling, opt-in and migration rules, worked use-case and test examples, precedence
   rules and rejected alternatives in the language decision record. Prefer the dot/named form if both read
   equally well and its tooling/interop costs are lower. A demonstrated readability advantage with
   predictable tooling is a reason to select keyword messages. Only the selected grammar advances to B2.

The 0.6.0 release can settle fixture, assertion and observation semantics while this spelling decision
stays open. The 0.7.0 specification should link the final choice to conformance cases shared by the CLI,
IDE and production interpreter.
