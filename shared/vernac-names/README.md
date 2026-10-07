# Shared name policy

These small, dependency-free Java sources are compiled into both `vernac-compiler`
and `vernac-intellij` using build-helper-maven-plugin. The language server obtains
them through the compiler dependency. The plugin does not load the compiler or
Spring runtime into the IDE process just to validate a namespace. No new published
artifact or consumer dependency is required.

The checked-in identifier ranges follow Java 21 (Unicode 15.0) and are independent
of the JVM running the tools. Regenerate with Python 3.12:

```text
python shared/vernac-names/tools/generate_unicode_tables.py
```

The generator checks the Unicode data version and does not run during a normal
Maven build. The Java category rules are documented at:
https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Character.html#isJavaIdentifierStart(int)
and `isJavaIdentifierPart(int)` on that page. Vernac excludes identifier-ignorable,
control and format characters. See `docs/contracts/names-and-unicode.md`.
