# Domain behavior contract

Status: implemented for ordinary value objects, enum value objects and explicit
list/set types. Entity/aggregate behavior migration is a separate step.

## Purpose and syntax

Vernac owns the public domain contract. Java supplies its implementation, either
inline or through an explicitly named external static method. No Spring bean,
wrapper instance, reflection-based dispatch or interface default implementation
is involved.

```vernac
namespace org.example.people;

value PersonName(String) validates {
    require(string.startsWith("Hans"), "Name must start with Hans");
} behavior {
    java imports {
        java.util.Locale;
    }

    public String upper() {
        return self.string().toUpperCase(Locale.ROOT) + suffix(1);
    }

    private String suffix(int count) {
        return "!".repeat(count);
    }

    public String? alternative() {
        return Optional.empty();
    }

    public int length() implemented by org.example.people.PersonNameBehavior;
} list PersonNames behavior {
    public boolean hasDuplicates() {
        return !self.duplicates().isEmpty();
    }
};
```

A behavior block follows the value's optional validation block. The collection
clause follows the value's behavior; the collection has its own optional behavior
block. The Java import block, when present, precedes all methods.

Every method explicitly declares `public` or `private`. `protected`, `internal`,
implicit visibility, user-declared fields, constructors and method annotations
are not part of this syntax. Existing bare method blocks must be migrated.

## Signatures and receiver

Public methods are instance methods of the generated domain type. Their resolved
parameter and result types belong to the Vernac type system, not the Java import
scope. Existing signature and generated-API collision rules continue to apply.

Inline public implementations receive an implicit, non-null `self` parameter of
the containing domain type. A public parameter cannot be named `self`. Access the
public API through `self`, including another public behavior method:

```java
return self.string();
// self.anotherPublicMethod()
```

Method bodies are Java and are not rewritten. `this` is unavailable. Unqualified
field access and unqualified calls to domain getters do not become implicit
receiver access. Enum constants use their declaring type: `State.OPEN`.

Private methods belong only to the implementation class. They are static helpers,
not members of the domain API, and have no implicit receiver. Pass an object
explicitly when needed:

```vernac
private boolean hasText(PersonName name) {
    return !name.string().isBlank();
}
```

Public inline code may call `hasText(self)` directly. Public behavior methods
should be called through `self`, so boundary checks remain active.

## Generated implementation boundary

Each behavior-bearing type receives a separate package-private, final top-level
implementation class in its domain package. Its current compiler-owned name is
`__VernacBehavior_<Type>`. A colliding model declaration is rejected. This name is
an implementation detail, not an API for applications.

Public domain methods delegate to package-private static implementation methods.
Private helpers remain private static methods. There is no enclosing/nested-class
relationship and no extra state on the domain object. The implementation cannot
access the domain object's private fields or private constructor. It uses the
same accessible API as hand-written external code.

This boundary catches accidental coupling to representation. It is not a security
sandbox and does not attempt to prevent deliberate reflection or other bypasses.

Generated implementation files participate in the existing generated-source
ownership/cleanup mechanism. Hand-written implementation files are never created,
overwritten or removed by the compiler.

## External implementation

`implemented by` names a fully qualified Java type. No inferred implementation
class names are used. It is supported for public methods only.

```java
package org.example.people;

import org.example.people.domain.PersonName;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class PersonNameBehavior {
    private PersonNameBehavior() {}

    public static int length(PersonName self) {
        return self.string().length();
    }
}
```

The target supplies an accessible static method with the same name. Its first
argument receives `self`; remaining arguments preserve declaration order. Normal
Java overload selection and assignment compatibility apply. The generated bridge
pins the declared parameter/result types; imports never redirect this call.
Missing classes/methods, non-static methods and incompatible results are Java
compilation errors. These checks are not performed while the LSP edits a model.

The class need not implement an interface. A static method declared by an
interface can be called using the same Java rule; default-method dispatch is not
supported. No class instance is constructed or injected.

## Nullness

Generated domain and implementation classes are `@NullMarked`.

- Required reference parameters are non-null. Public domain methods guard these
  parameters before invoking behavior code.
- Optional parameters (`Type?`) become `@Nullable Type` parameters, as in the
  existing factory/input convention. Code must check them before dereferencing.
- Required reference results are non-null.
- Optional results (`Type?`) become non-null `Optional<Type>`, for both public and
  private behavior methods. Absence is `Optional.empty()`, never `null`.
- No nullable method result annotation can be declared in Vernac.
- Primitive optional types remain invalid. `void` has no result check.

The public delegation boundary rejects a null reference result, including a null
Optional, with `BehaviorContractException`, a `VernacTechnicalException`. Its
message identifies the domain method and Vernac source location. This is an
implementation defect, not a business validation error. Results are never
silently converted with `Optional.ofNullable`.

Annotations and guards do not replace static analysis. The standard project
POM and reviewed namespace example configure Error Prone/NullAway, using
OnlyNullMarked and JSpecifyMode. Annotate hand-written implementation classes or
packages with `@NullMarked` too. Unmarked third-party implementations may not be
fully analyzed; boundary guards remain in place. Deliberate suppressions remain
the author's responsibility.

Full Java analysis runs during Java compilation, not Vernac's generate-sources
phase. An existing application's POM must adopt the checker configuration; a
compiler dependency alone does not activate it. See the
[behavior tutorial](../tutorials/behavior.md).

## Java imports and architecture checks

`java imports` is local to one behavior implementation. It accepts explicit,
fully qualified type names. Wildcards and static imports are not supported.
External implementations use imports in their own Java files.

Visible Vernac domain types and approved built-ins keep their established
bindings in inline Java. Java imports may add names but must not replace those
bindings. Two different Java imports with the same simple name are rejected.
Repeating the same target is harmless. The generated Optional return contract
also requires the normal Java Optional type where no model type occupies that
name. Qualify Java references when necessary.

Vernac's resolved signature types are never inferred again from Java imports.
Java imports do not permit arbitrary Java fields or method-signature types in
Vernac. Names declared locally inside a Java body follow normal Java scope rules.

Explicit imports/delegation targets referencing repositories or adapters whose
Java identities are known from this project are rejected. Arbitrary external
packages are not classified by substrings such as `infrastructure`. This is a
limited architectural check, not a whole-program dependency analysis. Fully
qualified references in method bodies and indirect calls are not inspected.

## Diagnostics, tooling and verification

Vernac diagnoses syntax, signatures, reserved receiver names, conflicting imports
and known forbidden import/delegation targets. Behavior keywords are highlighted;
Vernac types in signatures support completion and navigation.

The IntelliJ plugin supplies a first Java-injection integration for inline
behavior bodies: Java highlighting, completion and navigation use IntelliJ's Java
support, while the LSP retains Vernac analysis. Generated domain types must be
available in the imported Maven project. See the
[scope and limitations](../development/intellij-java-behavior.md).
Java class lookup for `implemented by` declarations and live NullAway analysis
are not implemented by this integration. Java compiler
errors currently refer to the generated companion file; each implementation
method documents its originating Vernac source location. Automatic remapping of
javac diagnostics to the DSL is a follow-up, not a guarantee of this version.

Tests cover parsed syntax, actual generated Java compilation/execution, private
access rejection, external static calls, optional results, null-result guards,
import collisions, and editor diagnostics. Existing behavior tests are migrated,
not removed.

### Portable source locations

Behavior comments and null-result exception messages identify the source relative
to the Vernac source root (normally `src/main/vernac`), followed by one-based
line and column numbers, for example `org/example/tasks/product-name.vernac:10:5`.
Path separators are always `/`. Absolute checkout paths are not embedded in these
generated locations, so moving a project does not change the generated Java.
Synthetic names such as `<memory>` are preserved. Standalone sources outside the
compilation root use their file name only. These display locations do not replace
absolute source identities used internally for compiler diagnostics and the LSP.
