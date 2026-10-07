# Unicode implementation

The contract is [Names, Unicode and source encoding](../contracts/names-and-unicode.md).

## Implementation

- `shared/vernac-names` contains the dependency-free name policy. Compiler and plugin
  compile the same sources; the LSP uses them through the compiler. This avoids new
  published modules and avoids loading the compiler into the IDE process.
- Identifier ranges are checked-in Unicode 15.0 ranges, matching the Java 21 target.
  They do not change when the host JVM changes. Python is needed only to regenerate
  the tables, not to build or use Vernac.
- The lexer accepts code-point-based identifiers. It captures identifier-ignorable
  and format characters so the parser can issue diagnostics containing `U+XXXX`.
  Normal whitespace remains a separator. Embedded Java bodies are not subjected
  to Vernac's extra name restrictions.
- Namespace/type/member checks use the shared policy. Contextual Vernac keywords
  remain usable as Java-compatible namespace segments.
- Name casing operates on code points. Existing naming conventions are preserved;
  alphabetic case boundaries and identifier extraction now recognize Unicode.
- Generated sources are written as UTF-8. A temporary staging tree on the output
  filesystem detects duplicate, case-insensitive or normalization-equivalent paths
  before existing sources are replaced. Existing paths with a different spelling
  are rejected too. Temporary files are removed. This is not a transaction across
  all final writes and does not implement stale-file ownership or cleanup.
- LSP word boundaries handle supplementary characters. Completion text edits and
  definition ranges preserve spelling. Diagnostic and semantic-token positions use
  UTF-16; multiline comment tokens are split into single-line ranges. Parser type
  positions identify non-Latin type names without requiring an uppercase letter.
- New Namespace uses the same alphabet and validates invisible characters.
  The plugin distribution test requires both shared policy classes inside its JAR.

No case folding, transliteration or Unicode normalization is applied to identity.
The filesystem collision check does not prohibit distinct spellings on a filesystem
that can represent them distinctly.

## Verification

New tests cover shared name policy, cross-file and cross-namespace Unicode IDs/VOs,
compilation of generated Java, enum constants, member names, supplementary case
conversion, normalization identity, targeted invisible-character diagnostics,
namespace paths, completion edits, definition ranges and semantic-token positions.
Existing tests remain. The in-memory compiler now explicitly targets Java 21/UTF-8.

Run from the repository root with Maven running on JDK 25 for IntelliJ SDK support:

```text
mvn -pl vernac-intellij,vernac-maven-plugin -am clean verify
```

An additional compiler run on JDK 21 checks every code point against Java 21's
`Character.isJavaIdentifierStart/Part`, excluding the forbidden categories:

```text
mvn -pl vernac-compiler -am test
```

During preparation, the shared name classes and output writer were compiled with
the available Java 17 compiler module. Executable checks passed for Unicode names,
supplementary case conversion, UTF-8 output and rejecting duplicate outputs without
overwriting existing content. These checks do not replace the Maven test suite.

The temporary environment no longer had JDK 21/25 or Maven, and the JDK download
was blocked. Full reactor tests, regenerated ANTLR code and IntelliJ UI verification
have therefore **not yet been run for this patch**.

## Manual IntelliJ check

1. Create namespace `de.beispiel.aufträge` using New Namespace.
2. Create two files in it:

```vernac
namespace de.beispiel.aufträge;
id AuftragId;
value Größe(String text);
```

```vernac
namespace de.beispiel.aufträge;
value Auftrag(AuftragId id, Größe größe);
```

3. Check completion and Go to Declaration for `Größe` across the two files.
4. Try a supplementary-letter type such as `𐐀name` and an explicit field `𐐨name`.
5. Put `/* 😀 */` before a declaration and confirm highlighting and navigation remain
   aligned. An emoji is valid comment text, not a Java identifier letter.
6. Generate Java and compile it. Inspect namespace paths and spellings.

## Scope and follow-ups

The ID/VO slice has the new end-to-end regression coverage. Other generators receive
Unicode-safe replacements for existing case conversion and identifier extraction,
but their broader language contracts remain deferred. In particular, SQL quoting,
database identifier limits, schema migration policy, port mappings and use-case
semantics are not resolved by allowing Unicode names.

Next: generated-file ownership and removal of obsolete generated classes. Later:
optional confusable-name diagnostics and Ctrl-hover visual navigation feedback.
