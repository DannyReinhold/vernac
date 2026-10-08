# Domain Collection Contract

Status: Accepted design; implementation supplied with this contract. Full Maven
and installed-plugin verification remain required. MUST denotes a requirement.

## Purpose

A domain collection is a named, structurally immutable list or set of one Vernac
domain type. It has no independent domain identity. It supplies useful collection
operations, not a transaction boundary or aggregate ownership policy.

Collections are never generated implicitly. They may be requested for IDs, ordinary
value objects, enum value objects, entities, and aggregates using the same syntax.
A collection does not turn mutable element objects into immutable snapshots.

Related: [Value objects](value-objects.md), [Enums](enum-value-objects.md),
[Names and Unicode](names-and-unicode.md).

## Syntax, names, and packages

```vernac
namespace example.tasks;

id TaskId list;
value Name(String value) set Names;
value Status = PENDING | COMPLETED list StatusHistory;
entity Task[TaskId](Name) list Tasks;
aggregate Work[TaskId](Name) set Works;
```

The list/set clause follows the element definition, including its optional
validation and method blocks. Its own optional body declares COLLECTION methods:

```vernac
value Name(String value) behavior {
    public boolean isBlank() { return self.value().isBlank(); }
} list Names behavior {
    public boolean hasRepeatedNames() { return !self.duplicates().isEmpty(); }
};
```

One list or set declaration may be attached to each element definition in this
version. Defining multiple collection types for one element is a possible future
extension, not implicit behavior.

The default collection name is exactly the element type name plus `s`, with no
linguistic inflection. TaskId becomes TaskIds; Category becomes Categorys. Authors
can explicitly choose Categories. No numbering or automatic collision repair is
permitted. Collections are namespace-level types and participate in ordinary type
collision checks, reserved built-in type names, imports, and Unicode rules.

Each collection MUST generate one public final class implementing Iterable<T>,
annotated @NullMarked, in `<namespace>.domain`. It has private construction and
private final storage. No collection-specific or inherited package override is
permitted. The old `collection` keyword is replaced by `list`/`set`.

## Structural guarantees and element identity

All creation paths MUST take an independent structural snapshot of the input.
Subsequent changes to an input list, set, or array MUST NOT change the collection.
Exported views and iterators MUST NOT permit structural mutation.

Element references are retained. No automatic deep copying, replacement, merging,
or choice of a newer Entity/Aggregate state is performed. Custom Java methods and
callbacks must respect the element model; an immutable container cannot prevent a
callback from mutating an Entity supplied to it.

Null input containers, null elements, null candidates/IDs, and null predicates are
invalid. Generated factories and operations reject them with
DomainValidationException. An empty collection is valid. Java-inherited operations
such as Iterable.forEach retain their Java exception contracts.

All transformations return the same generated collection type. The source remains
unchanged, including on failure. Implementations may return the original instance
when the result is unchanged; a fresh Java identity is not guaranteed.

Additional business invariants belong in a containing VO or domain object, not in
this structural collection definition. Such wrappers validate their own creation
paths. Returning a derived collection does not mutate or revalidate its owner.

## List and set semantics

Lists preserve encounter order and repetitions. Sets identify membership using
equals/hashCode and contain at most one representative of each equality group.

For set creation, the first encountered equal instance wins. For plus/plusAll,
existing instances win; among newly supplied equal elements, the first encountered
wins. Duplicate insertion is a no-op in content, not an exception. The incoming
instance MUST NOT replace the existing instance, even if their other state differs.

Sets iterate in first-insertion order for predictable processing, but their equality
and hashCode MUST NOT depend on that order. Input with unspecified encounter order
cannot guarantee which equal instance is encountered first across executions.

Entity and Aggregate equality follows their ID contract. Two equal elements may
represent different states of the same business object. Lists can retain all those
instances; sets deliberately keep one. There is no implicit version reconciliation.

## Factories and Java interoperability

For a generated type C containing T:

| API | Meaning |
| --- | --- |
| `C.empty()` | Empty collection |
| `C.of(T... items)` | Snapshot of an array or individual arguments |
| `C.of(Iterable<? extends T> items)` | Snapshot of a Java or generated collection |
| `iterator()` / enhanced for loop | Read-only traversal |
| `stream()` | Sequential stream of contained instances |
| `asList()` (lists only) | Unmodifiable List<T> view |
| `asSet()` (sets only) | Unmodifiable Set<T> view |

The class MUST NOT implement List or Set and MUST NOT expose mutation methods that
merely fail at runtime. No redundant elements()/toList()/toSet() aliases are generated.
A separate collection map operation is not provided; use stream mapping and an
explicit destination factory. Optional.map remains available on single-result APIs.

## Queries

| API | Return | Meaning |
| --- | --- | --- |
| `size()` | int | Number of elements |
| `isEmpty()` | boolean | Whether size is zero |
| `contains(T element)` | boolean | Membership by equality; may stop at first match |
| `count(T element)` | int | Number of equal elements; 0 or 1 for sets |
| `find(T element)` | Optional<T> | First contained equal instance, not the query object |
| `get(int index)` (list) | T | Zero-based access; invalid indices throw IndexOutOfBoundsException |
| `first()` / `last()` (list) | Optional<T> | First/last element, or empty |

`contains(x)` is equivalent to `count(x) > 0`; `!contains(x)` to `count(x) == 0`.
No missing result is represented as null.

## Transformations

| API | Meaning |
| --- | --- |
| `plus(T element)` | Append to a list; insert if absent into a set |
| `plusAll(Iterable<? extends T> elements)` | Include all supplied elements in encounter order |
| `minus(T element)` | Remove the first equal occurrence, if present |
| `minusAll(T element)` | Remove every equal occurrence |
| `minusAll(Iterable<? extends T> elements)` | Remove all source elements equal to any candidate |
| `matching(T element)` | Retain every equal source occurrence |
| `matching(Iterable<? extends T> elements)` | Retain all source elements equal to any candidate |
| `filter(Predicate<? super T> predicate)` | Retain elements satisfying the predicate |
| `distinct()` (list only) | Keep the first instance of each equality group |
| `duplicates()` (list only) | Keep ALL occurrences of groups appearing at least twice |

Missing removal candidates do not cause errors. Candidate repetitions do not
multiply results and do not represent counts to subtract. Selection preserves
source order and source instances, not candidate instances:

```text
[A, B, A, C].minus(A)          -> [B, A, C]
[A, B, A, C].minusAll(A)       -> [B, C]
[A, B, A, C].minusAll([A, A])  -> [B, C]
[A, B, A, C].matching([A, C])  -> [A, A, C]
[A, B, A, C].distinct()        -> [A, B, C]
```

### Duplicates: every occurrence, including the first

**duplicates() does not return only the extra occurrences and does not deduplicate
its result.** It selects equality groups whose frequency is at least two and returns
ALL original members of those groups, preserving order and exact instances.

```text
[A, B, A, C, B].duplicates()            -> [A, B, A, B]
[A, B, A, C, B].duplicates().distinct() -> [A, B]
[A, A, A].duplicates()                 -> [A, A, A]
[A, B, C].duplicates()                 -> []
[].duplicates()                        -> []
```

Use **duplicates().distinct()** for one representative of each repeated group.
The first original instance is retained by distinct().

Example: Account(id=42, balance=100) and Account(id=42, balance=200) compare equal
by identity. duplicates() retains BOTH instances for investigation. Calling
distinct() afterward deliberately selects the first and discards the other state.
Neither operation decides which balance is correct or more recent.

## Identity helpers (Entity/Aggregate elements only)

The ID parameter uses the element's resolved Vernac ID type:

| API | Return | Meaning |
| --- | --- | --- |
| `by(Id id)` | Optional<T> | First element with the ID |
| `contains(Id id)` | boolean | Whether that ID is present |
| `minusId(Id id)` | C | Remove the first matching identity |
| `minusAllId(Id id)` | C | Remove all matching identities |

contains(T) and contains(Id) are overloads. ID collections themselves need only
find(id), contains(id), and the ordinary element operations; no redundant ID helpers.
The collection is an in-memory subset, not a repository. A valid ID may be absent.

## Equality, hashing, and rendering

Collections have no identity of their own. equals requires the same generated class
and equal contents under its list/set semantics. Lists include order and frequency;
sets compare membership irrespective of iteration order. hashCode is consistent
with equals and computed from current elements, not cached. toString identifies
the collection type and displays contents, without promising a serialization format.

The generated equals parameter MUST be @Nullable Object. Elements use their own
contracts. Entity collection equality is ID-based membership equality, not equality
of all Entity state. Domain IDs must remain stable while objects are stored in sets.

## Collections in ordinary value objects

Collections of IDs, enums, and immutable VOs MAY be used as ordinary VO fields,
including optional fields. Collections of Entities or Aggregates MUST be rejected
as VO fields. Wrapping mutable objects in a collection does not make them values.

```vernac
id TaskId list;
value Name(String);
value Selection(Name, TaskIds);
value OptionalSelection(TaskIds? taskIds);
```

Required collection fields follow VO null rejection. Optional collection fields
use nullable inputs and Optional<C> getters. Absence and an existing empty collection
are distinct. Same-file, same-namespace, imported, and qualified collection types
use the shared namespace resolver.

## Custom methods, diagnostics, and tooling

Collection `behavior` blocks MAY contain public and private methods. Their signatures
use resolved Vernac/approved scalar types and normal optional/nullness rules. They
cannot define mutable storage, custom constructors, packages, or collection invariants.
Implementation code lives in a separate top-level class and cannot access private collection storage. Public implementations receive `self`; private helpers use explicit parameters.

Actual generated/inherited method signature conflicts and invalid member/parameter
names MUST produce source-located compiler and LSP diagnostics. Legal overloads
remain permitted. Type names are resolved semantically, never guessed from strings.
See [Behavior](behavior.md) for scoped Java imports, external delegation and null checking.

Compiler and LSP share collection naming and semantic rules. Tests MUST cover
completion/navigation across namespaces, field eligibility, and generated symbols.

## Required verification

- Explicit declarations across IDs, VOs, enums, entities, and aggregates; no accidental generation.
- List/set distinction, suffix-only names, explicit names, Unicode, reserved names,
  type collisions, and rejection of legacy package/collection syntax.
- Compilable generated APIs, private storage/construction, @NullMarked/@Nullable,
  custom methods, and signature conflict diagnostics.
- Input snapshotting, protected views/iterators, null rejection, atomic failures,
  unchanged sources, empty collections, missing candidates, and bounds errors.
- Ordering, equality/hashCode, identity helpers, original-instance preservation,
  list multiplicities, and set first-instance semantics.
- **duplicates() including first occurrences, all-equal/unique/empty cases, distinct
  Entity states with the same ID, and duplicates().distinct().**
- count and contains consistency for lists and sets.
- Required/optional nested VO fields, cross-file/namespace resolution, and rejection
  of Entity/Aggregate collections in VOs.

This collection contract does not migrate all Entity/Aggregate generators to the
multi-file pipeline. Their existing single-source generation can emit these
collections; the strict project pipeline still reports those not-yet-reviewed owner
kinds as deferred. Full persistence/ACL collection mapping is likewise a later review.
