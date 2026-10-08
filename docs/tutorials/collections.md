# Lists, Sets, and Repeated Values

Use the compiler/plugin version containing the list/set collection contract.
Create `src/main/vernac/example/names/model.vernac`:

```vernac
namespace example.names;

value Name(String value) list Names behavior {
    public boolean hasDuplicates() {
        return !self.duplicates().isEmpty();
    }
};
id TaskId set TaskIds;
value Selection(Names, TaskIds? tasks);
```

In Java (import the generated types from example.names.domain):

```java
Name a = Name.of("A");
Name b = Name.of("B");
Name c = Name.of("C");
Names names = Names.of(a, b, a, c, b);

Names repeatedOccurrences = names.duplicates(); // [A, B, A, B]
Names repeatedValues = names.duplicates().distinct(); // [A, B]
int aCount = names.count(a); // 2
boolean containsA = names.contains(a); // true: count(a) > 0
Names remaining = names.minus(a); // [B, A, C, B]
Names withoutA = names.minusAll(a); // [B, C, B]
Names selected = names.matching(java.util.List.of(a, c)); // [A, A, C]
```

**duplicates() deliberately retains the first occurrence as well as later ones.**
It returns every original element belonging to a repeated equality group. This is
useful for inspecting different Entity states with one ID. For one representative
per repeated group, explicitly use **duplicates().distinct()**.

Try an all-unique list, an empty list, and Names.of(a, a, a). The results should be
[], [], and [A, A, A]. None of these operations changes names.

Use names.asList() for an unmodifiable Java list, names.stream() for processing,
and names.find(a) for an Optional containing the first stored equal instance.
first()/last() return Optional; get(index) follows ordinary zero-based list access.

## Sets retain the first instance

```java
TaskId id = TaskId.create();
TaskIds selectedIds = TaskIds.of(id, id); // one element
TaskIds again = selectedIds.plus(id); // still one element
```

No duplicate exception is thrown. An equal incoming object never replaces the
stored object. Set iteration uses first-insertion order; equality ignores order.
If your business operation must reject repeated selection, express that rule on the
responsible domain object rather than relying on set insertion to throw.

## Identity-based lookups

Entity/Aggregate collections also offer by(id), contains(id), minusId(id), and
minusAllId(id). They operate entirely in memory. by(id) returns Optional; lists
may contain multiple instances with one ID and return the first. minusId removes
one, while minusAllId removes all.

Two accounts with one ID and different balances can both appear in a list.
Their duplicates() result preserves both original instances. A set or distinct()
keeps the first, without deciding which state is newer or correct.

## Frequently asked questions

**Why not allow Entity collections in value objects?**
Their elements remain mutable. Immutable container structure does not make an
Entity into an immutable value. ID/enum/VO collections are allowed as VO fields.

**Why can I pass a Java list and another generated collection to plusAll?**
The method accepts Iterable<? extends T>. Both forms are supported without exposing
mutable List/Set interfaces on the generated class.

**What happens when I change the input list afterward?**
Nothing changes in the generated collection: its structure is a snapshot. Element
objects themselves are not deep-copied.

**Where do custom Java imports go?**
Their syntax is the next design topic. For now use public collection methods and
fully qualified Java references inside method bodies where needed. File-level
Vernac imports resolve Vernac types, not arbitrary Java classes.

**Are all owner types already available in multi-file generation?**
IDs, VOs, enums, and their collections are. Full Entity/Aggregate generation is still
awaiting its own review in that pipeline; existing single-source generation supports
their collections. See [implementation boundaries](../development/collections.md).

[Collection contract](../contracts/collections.md) · [All tutorials](index.md)
