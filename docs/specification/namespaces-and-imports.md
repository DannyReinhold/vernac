# Namespaces and Imports

Status: Accepted design; implementation pending.

This specification defines the intended Vernac behavior. It does not claim
that the current compiler or IDE plugin already implements these rules.

## 1. Purpose

Users must be able to split a domain model across multiple files without
changing its meaning, organize types into namespaces, and reference types
unambiguously. Generated Java packages follow conventions rather than
per-definition configuration.

The terms MUST, MUST NOT and SHOULD express requirements of this specification.

## 2. Source layout and namespace declaration

A Vernac source root may contain any number of `.vernac` files in nested
directories. The default Maven source root is `src/main/vernac`.

Every file MUST declare exactly one namespace before its imports and definitions:

```vernac
namespace org.example.tasks;
```

The namespace MUST correspond to the file's directory relative to its source
root: `org.example.tasks` corresponds to `org/example/tasks/`.
A mismatch MUST produce a Vernac diagnostic stating the declared namespace and
the namespace expected from the directory. An unnamed namespace is not supported.

File names do not define type identity or visibility. Multiple definitions per
file and multiple files per namespace are supported. Renaming a file or moving
a definition between files in the same namespace MUST preserve its meaning.
Each destination file must contain any imports needed by its definitions.

Namespace declarations and imports end in semicolons. Examples that omit them
are informal shorthand, not alternative syntax defined by this specification.

A namespace is an organizational boundary. It does not automatically declare
a DDD bounded context.

## 3. Names and generated Java packages

A Vernac type's identity consists of its namespace and simple name:

```text
org.example.tasks.Task
```

Definitions declare simple names. Type references may use simple or fully
qualified names. This applies consistently to fields, parameters, return types,
aggregate/entity ID references, repository targets, listener targets, and
injected dependency types wherever those constructs accept a type reference.

Two definitions MUST NOT introduce the same fully qualified Vernac type name,
even when they occur in different files. Generated companion types exposed as
Vernac types, such as named first-class collections, participate in this check.
Identically named types in different namespaces are allowed.

Java packages are derived from the namespace and the generated artifact's role.
For example, the domain type `org.example.tasks.Task` maps to
`org.example.tasks.domain.Task`.

Vernac imports refer to Vernac identities, without generated role suffixes such
as `.domain`. The generator resolves the corresponding Java identity.
Per-definition Java package overrides are removed from the intended language.
The complete role-to-Java-package mapping is specified separately.

Distinct Vernac definitions MUST NOT silently produce the same fully qualified
Java class name. Such output collisions require a diagnostic before Java
compilation or overwriting generated files.

Vernac-only keywords MUST be allowed as namespace segments when valid for the
Java target, for example `org.example.custom`. Names invalid for their generated
Java context MUST be rejected by Vernac, with a targeted diagnostic, rather
than being left for javac. Context-sensitive Java name restrictions must not
be replaced with an indiscriminate list of all contextual keywords.
The complete identifier and automatic-name derivation rules are a separate
specification.

## 4. Import syntax and scope

Imports follow the namespace declaration and precede definitions.

Explicit type import:

```vernac
import org.example.customers.CustomerId;
```

Namespace wildcard import:

```vernac
import org.example.customers.*;
```

Wildcard imports expose types of exactly the named namespace. They do not
include nested namespaces. Imports apply only to the declaring file and are
not re-exported. Importing a type does not bring its dependencies' simple names
into the importing file.

Files cannot be imported. Import aliases are not supported in this version.
The keyword `use` retains its dependency-injection meaning; it is not an import.

An explicit import MUST resolve to a known Vernac type, even if unused.
A wildcard import MUST resolve to a known Vernac namespace. An arbitrary Java
package on the classpath does not establish a Vernac namespace.

Support for discovering Vernac symbols in external dependency artifacts is
outside this specification; these rules apply to symbols available to the
current compilation.

## 5. Own-namespace imports and redundant imports

All types in a file's namespace are available without imports.

Both forms of own-namespace import MUST be rejected as errors:

```vernac
namespace org.example.tasks;

import org.example.tasks.*;       // Error: own namespace.
import org.example.tasks.TaskId;  // Error: type in own namespace.

id TaskId;
```

This applies even if the imported type is declared in another file in the same
namespace. For an explicit import, compare the imported type's namespace with
the current namespace, not just a textual prefix. Importing from
`org.example.tasks.history` is not an own-namespace import of `org.example.tasks`.

Suggested diagnostics:

```text
Cannot import the current namespace 'org.example.tasks'.
Its types are already available. Remove this import.
```

```text
Type 'org.example.tasks.TaskId' belongs to the current namespace.
It is already available. Remove this import.
```

Repeated imports of the same external type or the same external wildcard
namespace do not introduce ambiguity. They SHOULD produce a redundancy warning
and MUST be deduplicated during resolution. An explicit import and a wildcard
that both expose the same external type are also not ambiguous; an explicit
import can deliberately disambiguate that type against other wildcards.

## 6. Name resolution

For an unqualified Vernac type reference:

1. Consider types in the current namespace and explicit imports.
2. A local type and an explicit import with the same simple name but different
   identities are an error at the import, even if the name is unused.
3. Two explicit imports with the same simple name but different identities are
   an error at the conflicting import, even if the name is unused.
4. If a local type or explicit import resolves the reference, use it. Wildcard
   candidates do not override it.
5. Otherwise, collect candidates from wildcard imports, deduplicated by fully
   qualified identity.
6. One candidate resolves the reference; multiple distinct candidates are an
   ambiguity error at the reference; no candidate is an unknown-type error.

A fully qualified reference resolves exactly that identity. It does not require
an import and does not fall back to matching simple names.

Example ambiguity:

```vernac
import org.example.billing.*;
import org.example.shipping.*;
```

If both namespaces define `Address`, an unqualified `Address` reference fails:

```text
Ambiguous type 'Address'.
Candidates:
  org.example.billing.Address
  org.example.shipping.Address
Add an explicit import or use a fully qualified type name.
```

Adding `import org.example.billing.Address;` selects that type for simple-name
references. `org.example.shipping.Address` remains usable as a qualified name.

## 7. Multi-file resolution and termination

File discovery and declaration collection MUST precede reference resolution.
Observable results MUST NOT depend on filesystem enumeration order or the order
of declarations across files. Compiler and IDE MUST apply the same rules.

Imports are symbol visibility rules, not recursive source inclusion. Resolving
an import MUST NOT recursively expand the imported file's imports into the
current file. Each declaration is registered once under its qualified identity;
resolution uses the resulting symbol index.

Own-namespace imports are rejected for clarity and redundancy. Termination
MUST NOT depend solely on that rejection: mutual imports between namespaces
must also terminate.

Mutual namespace references are not, by themselves, errors. A separate semantic
rule may reject a particular cyclic model relationship. Any analysis that
traverses dependency graphs MUST handle cycles explicitly rather than recurse
without a bound or visited-state tracking.

## 8. Boundary with Java interoperability

File-level imports in this specification import Vernac types. They MUST NOT be
blindly copied into every generated Java class.

The generator derives Java imports from resolved type identities and the needs
of each generated artifact.

The syntax and scope for additional Java dependencies remain open. A subsequent
specification must cover both Java types in Vernac signatures and Java names
used inside embedded implementation code. Ordinary Java method bodies cannot
contain Java import declarations; any block-local import facility would require
explicit Vernac syntax and generator support.

This specification does not introduce Java annotations into Vernac. Dedicated
keywords and conventions remain the intended configuration model.

## 9. Reference example

`src/main/vernac/org/example/tasks/task-types.vernac`:

```vernac
namespace org.example.tasks;

id TaskId;
value Title(String value);
```

`src/main/vernac/org/example/tasks/task.vernac`:

```vernac
namespace org.example.tasks;

import org.example.customers.CustomerId;

aggregate Task[TaskId](Title title, CustomerId owner);
```

`src/main/vernac/org/example/customers/customer-types.vernac`:

```vernac
namespace org.example.customers;

id CustomerId;
```

`TaskId` and `Title` resolve locally across files. `CustomerId` resolves through
an explicit import. Reordering the three files has no effect.

## 10. Diagnostics, IDE support and documentation

Diagnostics MUST identify the source file and relevant location. Conflict
messages SHOULD include the conflicting qualified identities and declaration
locations. Unknown-type errors SHOULD suggest plausible corrections where
available, without silently resolving a misspelled name.

The plugin must support:

- Highlighting of namespace and import syntax.
- Completion of available namespaces and types in imports and type references.
- Cross-file Go to Declaration for resolved types.
- Diagnostics consistent with command-line compilation.
- Updated resolution after files or definitions are added, changed or removed.

Quick fixes for redundant imports, explicit imports and qualified references
are desirable follow-ups, not prerequisites for the first implementation.

Before this feature is considered complete, provide a language reference,
a multi-file tutorial, and FAQ entries covering same-namespace visibility,
ambiguity, directory mismatches and the distinction between Vernac namespaces
and generated Java packages.

## 11. Acceptance checks

Implementation tests must cover:

- Same-namespace references across files, independent of processing order.
- File renaming and splitting without changes to type identities.
- Duplicate declarations and exposed companion-type collisions across files.
- Explicit and wildcard imports, including nonrecursive wildcard behavior.
- Fully qualified references in every supported type-reference position.
- Unknown explicit imports and unknown wildcard namespaces, even when unused.
- Own-namespace explicit and wildcard imports, including cross-file targets.
- Distinguishing a namespace from its nested namespaces.
- Explicit-import conflicts and wildcard ambiguity at use sites.
- Explicit/local precedence over wildcard candidates.
- Repeated external imports without false ambiguity.
- Mutual namespace references without nontermination.
- Namespace/directory mismatch and missing namespace declarations.
- Java-compatible Vernac keywords in namespace segments.
- Early diagnostics for names invalid for the Java target.
- Generated Java packages/imports and compilation with hand-written Java callers.
- IDE resolution after edits, additions and removals.

Retain existing tests unless their expected behavior is deliberately superseded
by this specification. Update those expectations explicitly. Tests of generated
Java are required alongside parser and analyzer tests.

## 12. Migration and open follow-ups

This is a language change, not a drop-in grammar patch. Migrate the previous
file-level `package` declaration to `namespace`, remove per-definition package
overrides, and replace Java-oriented file imports according to the forthcoming
Java-interoperability rules. Do not silently discard old constructs.

Update compiler, generators, plugin/LSP, examples, project templates and
documentation together. Audit the effects of changed output paths and stale
generated files before rollout.

Separate decisions still required:

- Complete mapping from definition/artifact roles to Java packages.
- Java types, built-in types and embedded-Java import scope.
- Identifier conventions and collision checks for generated members.
- External Vernac dependency metadata and symbol discovery.
- Cleanup of stale generated files after moves, renames or deletion.
- SQL identifiers, quoting and schema migrations in the persistence review.
