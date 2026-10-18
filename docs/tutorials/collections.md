# Lists, Sets, and Repeated Values

Use a Vernac 0.4.0 project and matching plugin; source checkouts use 0.5.0-SNAPSHOT.
The multiple-collection exercise requires matching compiler/runtime/tooling. This database-free exercise
compares membership, order and equality; no repository is involved.
Create `src/main/vernac/example/names/model.vernac`:

```vernac
namespace example.names;

value Name(String value) list Names behavior {
    public boolean hasDuplicates() {
        return !self.duplicates().isEmpty();
    }
}, set SelectedNames;
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
Place `java imports { qualified.Type; }` first inside the collection behavior
block. File-level Vernac imports resolve Vernac types, not arbitrary Java classes.

**Are all owner types already available in multi-file generation?**
Yes. IDs, VOs, enums, entities and aggregates support explicit collections through
the multi-file project pipeline. Field eligibility still depends on the owner: a
VO cannot hold mutable entity elements.

[Collection contract](../contracts/collections.md) · [All tutorials](index.md)

## Give the same element different collection roles

Replace the initial Name declaration with this extended suffix (keep TaskId and
Selection unchanged). Names remains the list already used by Selection:

```vernac
value Name(String value) list Names behavior {
    public boolean hasDuplicates() { return !self.duplicates().isEmpty(); }
}, set SelectedNames behavior {
    public boolean includes(Name name) { return self.contains(name); }
}, list ArchivedNames;
```

In Java, `Names.of(a, b, a)` keeps all three entries. `SelectedNames.of(a, b, a)`
contains two values. `ArchivedNames` is another list type with its own API; it does
not inherit hasDuplicates from Names or includes from SelectedNames. Use
`ArchivedNames.of(names)` for an explicit structural copy sharing the same elements.

An existing default name can stay implicit while another collection is added:
`value Wert(String) list, set Werte;` generates **Werts** and **Werte**. Omitting both
names would generate Werts twice and produces a diagnostic. Names are optional
independently, not mandatory merely because a second collection exists.

The same suffix works on IDs, enums, entities and aggregates. Each behavior block
owns its imports/helpers and receives its own collection as self. For repositories,
return the desired aggregate collection by name. Only a list result can preserve
order by; a set result with ordering is rejected regardless of its suffix position.
