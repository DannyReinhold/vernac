# Give your model domain behavior

Use the reviewed `examples/namespace-values` project. Its `PersonName` example
includes inline code, a private helper, an optional result, an external Java
implementation and collection behavior. Run:

```text
mvn -pl examples/namespace-values -am clean verify
```

The Maven build needs a full JDK 21.0.8 or newer; a current JDK 25 is suitable.
The compiler still targets Java 21. Error Prone and NullAway are configured in
the example POM and the new-project template POM.

## 1. Read the contract in Vernac

```vernac
value PersonName(String) behavior {
    java imports { java.util.Locale; }

    public String upper() {
        return self.string().toUpperCase(Locale.ROOT);
    }

    public String? alternative() {
        return Optional.empty();
    }
};
```

From Java, use `PersonName.of("Hans").upper()`. `self` exists only in the
implementation and does not appear in the public method signature.

Change `self.string()` to `self.string` and build. Java rejects access to the
private field: inline code lives in a separate top-level class. Restore the getter.

## 2. Introduce a private helper

```vernac
private String suffix(int count) {
    return "!".repeat(count);
}
```

Call `suffix(2)` from `upper()`. It is a normal static helper in the implementation,
not an extra method on PersonName. Helpers have no implicit `self`; pass any
required domain object explicitly.

## 3. Move an implementation into Java

Declare the public signature and target in Vernac:

```vernac
public int length() implemented by org.example.tasks.PersonNameBehavior;
```

The example's Java class implements `public static int length(PersonName self)`.
Edit that method normally in IntelliJ. No bean or dependency injection is needed.
Rename the Java method without changing the Vernac signature and build: javac
reports the missing implementation. Restore the method name afterward.

## 4. See null checking fail early

Temporarily change an inline String return to `return null;` and run the example
build. NullAway must fail the compilation. Change an optional result to return
`null` as well: an Optional itself cannot be null either.

Use `Optional.empty()` for absence. For an optional input, explicitly handle null
before dereferencing it. The input convention differs from the Optional result
convention deliberately.

Existing projects must copy the maven-compiler-plugin configuration from the
example or template POM. Keep the forked compiler flags and both annotation
processor dependencies. Add any other annotation processors your project uses to
the same processor path; do not silently replace them. Mark external Java
implementation packages/classes `@NullMarked`. Merely installing the Vernac
plugin does not activate NullAway in an existing POM.

If static analysis is absent or deliberately suppressed, generated public methods
still reject null results with BehaviorContractException. That exception indicates
a programming defect, not a rejected business operation.

## 5. Explore imports

Try adding `some.library.String` to `java imports`: Vernac rejects the collision
with its String built-in. Try importing a Java class with the same name as a
visible Vernac value object: this also fails. Qualify the external Java type in
the implementation when necessary.

Imports apply only to this behavior block. They do not change modeled field types
or public method signatures. Do not add Java imports at the file's Vernac import
level.

## FAQ

**Can inline code use this?** No. Use `self` for public domain behavior. Private
helpers use explicit parameters.

**Can behavior introduce mutable fields?** No. It supplies methods, not additional
object state.

**Why is there an extra generated Java file?** It gives inline code the same
private-access boundary as external code. Never edit that generated file.

**Why does a Java error mention that file?** Java compilation follows generation.
The method's Javadoc records the Vernac source location. Automatic diagnostic
remapping and Java-aware inline editing are future work.

**Is this an architecture firewall?** No. Vernac catches known import mistakes;
it does not inspect every Java dependency or prevent deliberate bypasses.

See the [behavior contract](../contracts/behavior.md) for the complete rules.

## IntelliJ assistance inside inline behavior

After importing and building the Maven project, the plugin's Java-injection pilot
provides Java assistance inside inline behavior bodies. Try completion after
`self.string().`, navigation on a Java method such as `toUpperCase`, and navigation
from a call to a private helper to its declaration in the same behavior block.
Body edits are reflected without rebuilding. Changes to the generated model API
still require regeneration. Add Java imports explicitly in `java imports`.

This is separate from the Vernac language server. Java type references can now
navigate back to their Vernac definitions. Field getters and public behavior
methods also navigate to their Vernac declarations, including `implemented by`
contracts. Overloaded methods are matched by their resolved parameter types; see
[Java-to-Vernac navigation](../development/java-to-vernac-navigation.md).
The `implemented by` target in the Vernac declaration does not yet have Java
navigation. See [details and limitations](../development/intellij-java-behavior.md).
