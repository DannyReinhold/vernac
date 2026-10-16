# Getting Started with the Vernac IntelliJ Plugin

Create a working Vernac project, explore your domain model with language-aware editor support, and use the generated
types from Java.

By the end of this tutorial, you will have a Maven project with your own coordinates and package, a small task model,
and a passing Java test. No database, Docker, or running Spring application is required for this example.

## What the plugin provides

The plugin combines project creation with editor support powered by the bundled Vernac Language Server Protocol (LSP)
implementation.

| Feature                          | What it helps you do                                                                                        |
|----------------------------------|-------------------------------------------------------------------------------------------------------------|
| New Project wizard               | Create a Maven project with a model, Java test, Maven Wrapper, and README.                                  |
| Project settings                 | Choose a project name, Maven coordinates, namespace, and registered JDK.                                 |
| Automatic Maven import           | Let IntelliJ discover the project's dependencies and build configuration.                                   |
| Vernac file type and icon        | Recognize `.vernac` files without manually configuring a file type.                                         |
| Syntax and semantic highlighting | Read models with language-aware coloring supplied through the LSP integration.                              |
| Syntax diagnostics               | Find malformed Vernac declarations while editing.                                                           |
| Semantic analysis                | Receive diagnostics for model problems detected by the language server, such as unresolved type references. |
| Context-aware code completion    | See suggestions appropriate to supported positions in the model.                                            |
| Completion snippets              | Insert available language constructs with less typing.                                                      |
| Hover documentation              | Inspect information provided by the language server for supported symbols.                                  |
| Go to Declaration                | Navigate between definitions and references inside Vernac files.                                            |
| File templates                   | Add a blank Vernac model or a getting-started example to an existing project.                               |

The plugin and the Maven compiler have different responsibilities: the plugin helps you edit models; the Maven build
generates and compiles Java sources. Editor diagnostics do not replace a successful build or application tests.

## 1. Prepare your environment

This guide describes the **0.4.0 plugin**. Match its compiler/runtime version, and
check the packaged descriptor for IDE compatibility.

- Use a compatible IntelliJ IDEA installation. The current plugin descriptor targets build `262.10968.63` through the
  `262.*` series.
- Keep IntelliJ's Java and Maven plugins enabled. The plugin also requires IntelliJ's LSP support.
- Register a JDK with Java 25 in **Project Structure → SDKs**. The wizard currently selects registered JDKs; it
  does not download a new JDK for you.
- Allow internet access for the initial Maven Wrapper and dependency downloads.

### Released plugin versus a source build

For the published 0.4.0 plugin, the wizard selects matching 0.4.0 Maven artifacts.
They are downloaded from Central; building the Vernac repository is unnecessary.
Before that release is published, use the source-build route below instead.

A development plugin built from this checkout uses **0.5.0-SNAPSHOT**. Install its
matching artifacts from the repository root first:

```shell
mvn clean install
```

Use JDK 25 and check `mvn -version`. The generated application still targets Java
21. Installing the IDE plugin bundles its language server, not Maven artifacts.

## 2. Install the plugin

For a published version, open **Settings → Plugins → Marketplace**, search for
**Vernac**, install it and restart if requested. Confirm the displayed version.
A plugin pending Marketplace review is not yet available through this route.

For a local development build or a verified release ZIP:

1. Open **Settings → Plugins** and the gear menu.
2. Choose **Install Plugin from Disk…**.
3. Select the `vernac-intellij/target/*-plugin.zip` produced by the build, not a JAR.
4. Restart if requested and confirm Vernac is enabled.

No separate language-server installation or LSP4IJ configuration is required. If
an older LSP4IJ setup launches Vernac separately, disable that server configuration
to avoid duplicate integrations. Other LSP4IJ languages can remain enabled.

## 3. Create a project

Open **New Project** and choose **Vernac**. Enter, for example:

| Field        | Example                                |
|--------------|----------------------------------------|
| Project kind | `Spring Boot Web Application` (0.5.0 development) |
| Name         | `TaskManager`                          |
| Location     | A directory for your new project       |
| GroupId      | `com.example`                          |
| ArtifactId   | `task-manager`                         |
| Namespace    | `com.example.taskmanager`              |
| JDK          | A registered JDK with Java 25 |

The artifact ID is suggested from the project name: `TaskManager` becomes `task-manager`. You can edit it independently.
GroupId and namespace are separate settings; set both explicitly as needed.

In the 0.5.0 development wizard, **Spring Boot Web Application** is the default:
a complete Thymeleaf/PostgreSQL application with local Compose and Flyway history.
Follow [From New Project to a working application](spring-boot-starter.md) to run it.
Choose **Minimal Domain Project** for the database-free exercise described below,
or **Module / Type Library** to publish reusable types.

Choose **Create**. The wizard writes the project, sets its project JDK, configures
Maven executions to use that JDK and enables **Delegate IDE build/run actions to
Maven**. Vernac generation then participates in normal Build, Run and Debug. Wait
for the initial Maven import to finish; keep the run configuration’s Build step
enabled. This is a new-project default, not a setting forced whenever a project opens.
After the project opens, it starts the Maven import and opens `src/main/vernac/com/example/taskmanager/tasks.vernac`.

The generated POM contains your coordinates and display name:

```xml

<groupId>com.example</groupId>
<artifactId>task-manager</artifactId>
<version>0.1.0-SNAPSHOT</version>

<name>TaskManager</name>
```

The application's own `0.1.0-SNAPSHOT` version is independent of its Vernac dependency.
The POM's Java target remains 21, even if you select a newer JDK.

## 4. Explore the generated files

| File                                                        | Purpose                                                  |
|-------------------------------------------------------------|----------------------------------------------------------|
| `pom.xml`                                                   | Dependencies and Vernac source-generation configuration. |
| `src/main/vernac/com/example/taskmanager/tasks.vernac`                              | Your initial domain model.                               |
| `src/test/java/com/example/taskmanager/TaskTest.java`       | An example of using the generated model from Java.       |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | Maven Wrapper files.                                     |
| `README.md`                                                 | Project-specific build instructions and pointers.        |
| `.gitignore`, `.gitattributes`                              | Initial Git configuration files.                         |

Both source paths follow the namespace you selected. The Vernac file declares
`namespace com.example.taskmanager;`; Java test imports refer to its `.domain` package.

## 5. Build and run the example

In IntelliJ's Maven tool window, run **Lifecycle → test**. Alternatively, open a terminal in the generated project
directory.

Windows PowerShell:

```powershell
.\mvnw.cmd clean test
```

Linux or macOS:

```shell
./mvnw clean test
```

The build processes the model, generates Java sources, compiles them, and runs `TaskTest`. Generated sources are placed
under `target/generated-sources/vernac/`.

A newly opened Java test may contain unresolved references until the first generation step finishes. Run the Maven build
before diagnosing those references as model errors.

The sample test creates a task using `Task.create(...)`, completes it, and checks its resulting state. Keep your changes
in the Vernac definition and handwritten tests; generated Java files are regenerated by the build.

## 6. Try the language-server features

Open `tasks.vernac`. The language server starts automatically when the plugin opens a Vernac file. Allow a moment for
initialization.

### Highlighting

Inspect declarations such as `id`, `value`, and `aggregate`, along with their names and references. The integration
supplies language-aware highlighting; exact colors depend on your IntelliJ theme and color settings.

### Syntax diagnostics

Temporarily remove the closing parenthesis in `value Title(String)`. Inspect the
syntax diagnostic, then restore it. Removing the optional terminator from an ID
declaration is not a reliable syntax-error exercise.

### Semantic diagnostics

Temporarily change the aggregate's ID reference from `TaskId` to `MissingTaskId`, leaving the original ID declaration
unchanged. This introduces an unresolved reference rather than merely broken punctuation. Inspect the diagnostic, then
restore `TaskId`.

These checks concern the language server's supported analysis. They do not prove arbitrary embedded Java business logic
correct, nor do they replace runtime invariant checks.

### Context-aware completion

At an empty top-level position, invoke IntelliJ's **Basic Completion** action and inspect the available declarations or
snippets. Compare this with completion while entering a type reference inside a model declaration.

Suggestions depend on the cursor position and the language server's supported contexts. Invoke completion explicitly if
automatic suggestions do not appear. Use IntelliJ's action search if your keymap differs.

For namespace-based ID/VO models, completion includes visible types from other
`.vernac` files in the same source root. Types in the same namespace need no
import; other namespaces use explicit imports, wildcard imports, or qualified
references. Import completion also suggests available namespaces and types.
Suggestions track unsaved edits. Ambiguous names are not resolved arbitrarily.

### Hover information and navigation

Hover over a reference such as `TaskId` to inspect its LSP information and vocabulary
entry. With the **0.5.0 development plugin**, hold **Ctrl** and move the mouse over
that type reference: native PSI references supply the type-name range and resolved
declaration for IntelliJ's navigable-link highlighting. Click to navigate, or use
**Ctrl+B** / **Go to Declaration**. The exact color and shortcut follow your IDE theme
and keymap; the visual mouse-hover check requires the installed plugin.

This covers declared Vernac types in signatures, fields, explicit imports, repository
aggregate references and dependencies, including cross-file/namespace references and
exported module types. For a qualified reference, the type-name segment is the link;
namespace prefixes and wildcard imports are not fabricated type targets. Unknown or
ambiguous names do not get an arbitrarily chosen target. Declarations, comments,
strings, enum constants and embedded Java are not treated as these DSL type references.
Approved Java reference types such as `String`, `UUID` and `Instant` link to their
actual Java classes, including occurrences in behavior return types and parameters.
For example, all three `String` tokens in
`value Label(String) behavior { public String decorate(String suffix) { ... } };`
have Java class targets. Primitive names such as `int` have no class declaration.
An explicit `implemented by example.debug.handwritten.LabelFormatting` links on
`LabelFormatting` to that Java class as well. IntelliJ resolves these targets in the
Vernac file's module/JDK/dependency scope. Import the Maven project and configure
its JDK; a missing implementation or unavailable library does not get a guessed
replacement. These links do not permit additional Java types in Vernac signatures.

Navigation uses the current project model and unsaved source snapshots. Module
visibility comes from the build-written editor index: attach sources for original
producer navigation, or inspect the read-only exported contract fallback. Rebuild
producer/consumer artifacts and refresh the Maven project after dependency changes.
The new references do not introduce a separate completion engine or promise complete
cross-language Rename/Find Usages support. Native rename/rebinding through these
navigation references is deliberately rejected until a complete refactoring contract
is implemented. Mapping-specific DTO/result/path navigation remains LSP-owned where
it is not a global declared Vernac type.

Inline `behavior` bodies retain native injected-Java completion and navigation; see
the [behavior tutorial](behavior.md). Holding Ctrl there must continue to use Java
references, rather than the surrounding DSL host's type references.

For acceptance, try `TaskId` and a VO from another namespace, a fully qualified type,
a Collection, a module-exported type and a reference beside a `term` definition.
Confirm that only the type name is highlighted, Ctrl+click/Ctrl+B agree, and comments
or unresolved/ambiguous names get no false link. Compare an embedded Java type too.

After generating the Java sources, open a Java test and use **Go to Declaration**
on a model type reference such as `TaskId` or `Title`. For compiler-owned IDs,
value objects, enums and their collections, this opens the original Vernac
declaration. Normal Java types retain normal Java navigation. The generated Java
file remains accessible in the project tree and through Navigate to Class.
Try the same action on a generated field getter or a public behavior method:
it opens the field or method declaration in Vernac. This includes methods using
`implemented by`; direct calls to your external Java implementation still open Java.
See [Java-to-Vernac navigation](../development/java-to-vernac-navigation.md) for
prerequisites and boundaries.

Mouse shortcuts depend on your keymap; use the named action when testing navigation.

## 7. Add another Vernac file

In the Project tool window, select `src/main/vernac/` and choose
**New → Vernac Namespace**. Enter a fully qualified namespace, for example
`org.example.tasks`. The action creates `org/example/tasks` below the source root
and selects that directory. Existing directories are reused.

When invoked inside an existing namespace, the dialog suggests that namespace as
a prefix. The entered name is always fully qualified, relative to the source root.
Vernac keywords such as `custom` are allowed as segments; Java keywords such as
`class`, empty segments, and trailing dots are rejected. Namespace segments support
the Java 21 Unicode identifier alphabet, including names such as `aufträge` and
`注文`. Control/format characters are rejected, and spelling is not normalized or
transliterated. See [the naming contract](../contracts/names-and-unicode.md).

Select the new namespace directory and choose **New → Vernac File**. Enter a file
name, with or without `.vernac`, and choose:

- **Empty Model** for a minimal file.
- **Getting Started** for a small ID/value-object example.

Both choices automatically insert:

```vernac
namespace org.example.tasks;
```

No manual namespace or base-package input is required. The namespace comes from
the directory, not the file name. Create the namespace first: the file action is
available only inside a valid namespace below `src/main/vernac`, not directly at
the source root. Directory paths are not accepted as file names.

Use **Empty Model** for additional definitions in the same namespace. Repeating the
getting-started sample there would introduce duplicate type declarations.

These actions currently use the conventional `src/main/vernac` layout. Custom
source roots and automatic declaration updates after moving a file are separate
follow-ups. The project wizard also creates namespace-based models, with paths
and Java test imports customized together.

## 8. Make your first change

Inside the existing `Task` aggregate's `behavior` block, add:

```vernac
modify void start() {
    self.status(Status.IN_PROGRESS);
}
```

Add a test method to the existing `TaskTest` class, using its existing imports:

```java

@Test
void startsATask() {
    Task task = Task.create(
            Title.of("Try the IntelliJ plugin"),
            Status.PENDING
    );

    task.start();

    assertThat(task.status()).isEqualTo(Status.IN_PROGRESS);
}
```

Run Maven `test` again. This exercises the complete workflow: edit the model, generate Java, and test its behavior.

## Using the plugin in an existing project

Open the project and a `.vernac` file to activate editor support. The New Project wizard is not required for editing
existing models.

For Java generation, the project still needs the Vernac Maven plugin, appropriate runtime dependencies, and model files
in its configured source directory. Installing the IntelliJ plugin does not modify an existing POM automatically.

## Troubleshooting

| Symptom                               | What to check                                                                                                                                                                                 |
|---------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Vernac is missing from New Project    | Confirm that the plugin is enabled and compatible with your IDE build; check required plugins and restart after installation.                                                                 |
| No JDK is available in the wizard     | Register a JDK with Java 25 under Project Structure → SDKs, then reopen the wizard.                                                                                                  |
| No highlighting or completion         | Confirm the file ends in `.vernac`, is associated with the Vernac file type, and the plugin is enabled. Reopen the file and inspect the IDE log for language-server startup errors if needed. |
| Maven cannot resolve Vernac artifacts | Match the generated dependency version: released artifacts come from Central; development SNAPSHOT artifacts require local install. Check Maven settings/repository.                              |
| Java references are unresolved        | Run Maven `test` to generate sources, then reload Maven if needed.                                                                                                                            |
| “Load Maven Project” appears          | A new wizard-created project should import automatically. If it stays unlinked, you can load its POM manually to continue and report the automatic-import failure.                            |
| Terminal build uses the wrong JDK     | Check `JAVA_HOME` and `java -version`. The wizard configures the IDE project and Maven runner, not your terminal environment.                                                                 |
| A chosen package fails Vernac parsing | Check the namespace against its directory. Vernac keywords are allowed where Java permits them; Java keywords remain invalid.                                                    |

When reporting a problem, include the plugin version, IntelliJ build number, selected JDK, and relevant error message.

## Continue learning

Follow [Your First Domain Model](your-first-domain-model.md) for more exercises on model behavior and validation. If you
already created a project here, adapt that tutorial's package-specific examples to your selected package.

[All tutorials](index.md) · [Documentation home](../index.md)

## Choose an application or a reusable type library

In the 0.5.0 New Project → Vernac wizard, select **Spring Boot Web Application**
for a runnable UI/PostgreSQL application. With Docker running, open its
`TasksApplication` and click Run; `local` is the default profile. Explicitly select
`production` for deployment (the Dockerfile does this already). Choose **Minimal Domain Project** for a local
model and test, or **Module / Type Library** for reusable IDs, values, enums and
value collections. The published 0.4.0 wizard calls the minimal option **Application**.
The type library generates explicit exports and source-JAR packaging; it does not
generate aggregates or an application definition. Application projects receive an
empty `vernac-application.vapplication` beside their POM. Use it to activate
functional modules and select adapter bindings. **New → Vernac Application Definition** adds this
file to an existing Maven project without overwriting an existing definition.
Continue with the [module lifecycle tutorial](type-modules.md).

Application definitions now receive shared syntax and identity diagnostics. Completion
after `bind` proposes available ports; after `to` it proposes adapters for that port.
Go To Declaration follows those type references. Adapter bindings now generate Spring registration and profile-driven selection.
Functional modules require explicit activation; type-only dependencies do not need `activate`.


## Development preview: debug inline behavior

With the matching **0.5.0-SNAPSHOT** compiler and plugin, you can set breakpoints
inside supported Vernac behavior bodies and inspect the live JVM state. Follow the
[debugging tutorial](debugging.md) for a database-free example, expression evaluation,
conditional breakpoints and logpoints. The published 0.4.0 plugin does not include
this integration; follow the tutorial's source-build instructions.

### Vocabulary terms and embedded Java

A term whose decoded name exactly matches a type or named collection in the same
namespace also links to that definition. For example, Ctrl+click on `Label` in
`term Label { description "A parcel label."; }` opens `value Label(String);`.
The quoted spelling `term "Label"` has the same association. Standalone concepts
such as `term "Outstanding balance"` do not acquire a guessed type target.

Terms may appear before or between model declarations without disabling the Java
editor inside behaviors or validations. In a behavior, navigation to Java methods
such as `strip()` and local helpers such as `marker()` uses the injected Java
context; these expressions are not Vernac type references.

### Server-rendered MVC adapters (0.5.0 development)

The web starter now declares its routes in `adapter TaskWeb inbound mvc`; its
controller and scoped advice are generated. The LSP recognizes MVC keywords,
checks the same route/usecase/conversion rules as the compiler, completes form
binding paths and navigates `redisplay` references to their GET page loaders.
Hover on a loader reference explains that the POST is not retried. Approved Java
types, named mappings and module-owned usecase references keep native navigation.
Mapping assignment breakpoints map to the generated MVC conversion helpers.
Thymeleaf expression editing stays with IntelliJ's framework support.
See [the MVC walkthrough](inbound-mvc.md) and its explicit initial scope.

## Generated Java visibility after the first build

Embedded Java resolves generated classes through IntelliJ's Java source roots.
The plugin recognizes `target/generated-sources/vernac` in a Maven project with
`src/main/vernac`, marks it as generated production Java source, and repeats this
check after file-system and project-root changes. This covers the first Maven
build, project reopening and recreation after `clean`. Existing source-root
classifications are preserved. No unrelated module dependencies are added.

An initial Maven generation build is still necessary for newly declared classes:

```text
mvn compile
```

After the IDE refreshes and indexes the generated root, factories such as
`SubTaskCount.of(0)` and `FeatureTask.create(...)` should resolve in embedded Java.
An unresolved call can also prevent correct inference of a `var` local; a resulting
“always null” inspection must not be repaired by replacing that local with `null`.
Compare the generated companion and check the generated source root instead.
The plugin does not suppress Java nullability inspections.

This automatic repair covers the conventional Maven output directory. If the
project customizes `vernac.outputDirectory` or its build directory, configure or
import that directory as a generated Java source root through Maven/IntelliJ.
Completion before any Java generation remains a separate tooling improvement.

## Editing an incomplete model

A syntax error in one declaration no longer discards all complete declarations
in the same file. For example, `value Count(int);` remains known when a later
usecase is missing a closing brace. Java injection and type navigation continue
for independently complete declarations. Diagnostics still identify the error,
and Maven compilation still rejects the damaged source.

Recovery is conservative: an incomplete declaration is not published with guessed
parameter types or names. When an unclosed block makes later boundaries ambiguous,
the editor may withhold that region until the block is repaired. Invalid namespace
or import syntax prevents publishing a guessed file context. No last-successful
snapshot is silently substituted after an edit or rename.

This preserves existing editor assistance; it does not yet supply Java classes
for newly declared types before their first generation build. That is the next
stage of the editor integration.

## Try a value object before generation (0.5.0 development)

Add an ordinary VO to a namespace already used by your project. For example:

```vernac
namespace example.live;
value DraftCount(int) validates {
    require(self.intValue() >= 0, "Count must not be negative");
};
```

Use your actual namespace and matching directory. In an existing VO's `behavior`
block in that namespace, add:

```java
public int liveValueDemo() {
    var count = DraftCount.of(7);
    return count.intValue();
}
```

Before running Maven, try completion on `DraftCount`, `of` and `intValue`, inspect
`count`'s type, and navigate to the VO/getter. The current unsaved document is used.
The `validates` block also gets its Read-view getters without generation.

After a successful build, change the field to `int amount` without rebuilding.
The current API offers `amount()`; the older generated `intValue()` must not remain
as an alternative in this Vernac Java context. Rebuild and confirm that completion
still offers one API. An unrelated malformed declaration must not erase this
otherwise complete VO.

The live editor APIs cover local IDs, VOs, enums, entities, aggregates and their
explicit collections. Validation Read views and entity/aggregate Read/Write/Access
interfaces are included. Services, events, repositories, ports, usecases and their
application access interfaces still need generation. Handwritten `.java` files retain their
normal Java resolution. No source file is generated on a keystroke, and a running
application does not acquire these new methods until built/restarted or updated
through a separate supported debugger mechanism.

### Completing a newly declared VO inside Java

The live Java path covers local domain classes and their explicit lists/sets.
The installed-IDE retest confirms new class-name suggestions and short-name insertion.
Factory completion for ordinary VOs is also confirmed after preserving the copied editor context:
select `EditorTest`, then try `EditorTest.o` and Ctrl+Space for `of`.
The projected Java already imports visible VOs; you should not need a technical package
name or a new `java imports` entry for a local VO. The extension to IDs, enums and
collections still needs its own installed-IDE acceptance.
Vernac field-type completion is a separate feature.

For troubleshooting, place the caret inside the Java body and run **Tools → Vernac →
Diagnose Embedded Java Context**. It opens a read-only report without saving source
or running Maven. See the [diagnostic guide](../development/live-value-object-java-apis.md#installed-ide-completion-failure-and-diagnostics).

### Try IDs, enums and collections before generation

Add these declarations to your existing namespace:

```vernac
id DraftId list DraftIds;
value DraftState = OPEN | DONE set DraftStates;
value DraftLabel(String) list DraftLabels, set SelectedDraftLabels;
```

Inside an existing Java behavior, try completing `DraftId.cre`, `DraftState.O`,
`DraftLabels.em` and `SelectedDraftLabels.em`. They correspond to `create()`,
`OPEN` and each collection's `empty()` factory. After declaring
`var labels = DraftLabels.empty();`, instance members such as `plus`, `get` and
`duplicates` follow the actual generated list API. The set has `asSet()` and
has no indexed `get` or `duplicates` operation. Each explicitly declared collection
has its own type and optional public behaviors.

You still need a build to run these new types. Editing and completion create no
Java output files and perform no Hot Swap. Services, events and application-level
contracts continue using their generated APIs for now. A malformed declaration is withheld;
independent valid declarations remain available.

### Try entities, aggregates and the right `self` view

With `DraftLabel` from the preceding section, add:

```vernac
id DraftLineId;
id DraftBoardId;
entity DraftLine[DraftLineId](mut DraftLabel) behavior {
    read DraftLabel display() { return self.draftLabel(); }
    modify void rename(DraftLabel label) { self.draftLabel(label); }
} list DraftLines, set SelectedDraftLines;

aggregate DraftBoard[DraftBoardId](mut DraftLines) behavior {
    read DraftLines lines() { return self.draftLines(); }
    modify void add(DraftLine line) {
        self.draftLines(self.draftLines().plus(line));
    }
} list DraftBoards;
```

Before generating Java, try `DraftLine.cre` inside an existing usecase and
`self.dr` inside either entity behavior. In `display`, `self` provides the getter;
in `rename`, it also provides the setter overload. Read contexts do not offer
`rename` or `emit`. A validation block receives the same Read contract. You do not
need to import the technical view types: the Java projection supplies the correct
`self` type.

For a variable declared as `DraftLines`, completion includes `by(DraftLineId)`.
The selected result is an `Optional<DraftLine>`; it refers to an entity, not a copy
or a readonly facade. This preserves the existing domain semantics. Aggregate read
views also provide their timestamps. No public setter is added to the actual entity
or aggregate class.

The new kinds still require installed-IDE acceptance independently of the confirmed
ordinary-VO path. Build the project to execute the new classes and behavior.

Local services, events, outbound port contracts and usecases also expose current
Java APIs to embedded Java before generation. This includes dependency getters on
`self`, event factories and nested usecase `Result` records. Local repository contracts, event listeners and outbound adapters also expose live
APIs, including query handles, Access dependencies and outbound DTO/mapping holders.
Inbound HTTP/MVC controllers, scoped error handlers, DTOs and mapping holders are
also projected from the resolved transport contract. This editor projection
never writes generated output and does not replace the normal build.

### Try application APIs before generating Java

In a correctly located file in namespace `example.live`, add:

```vernac
namespace example.live;

value Caption(String);
event CaptionSelected(Caption);
service CaptionPolicy behavior {
    public Caption choose(Caption caption) { return caption; }
}
usecase SelectCaption(Caption) returns (Caption) uses CaptionPolicy behavior {
    execute {
        var chosen = self.captionPolicy().choose(caption);
        self.emit(CaptionSelected.create(chosen));
        return Result.of(chosen);
    }
}
```

Before running Maven, try completion after `self.`, `self.captionPolicy().`,
`CaptionSelected.` and `Result.` inside `execute`. The available signatures come
from the current model, including unsaved edits. A build is still required to run
the application. An incomplete Java body does not itself invalidate the declared
API; an unresolved Vernac signature is withheld until it can be resolved reliably.

### Try repository and listener dependencies

Add these declarations to the same `example.live` model:

```vernac
id NoteId;
aggregate Note[NoteId](Caption) list Notes;
repository NoteRepository for Note {
    find Notes withCaption(Caption caption)
        where caption = :caption order by createdAt;
}
synchronous listener RememberSelection on CaptionSelected uses NoteRepository behavior {
    execute {
        self.noteRepository().save(Note.create(event.caption()));
    }
}
```

Before generation, try completion after `self.noteRepository().` in the listener:
`byId`, `save`, `delete` and `withCaption` belong to its current repository contract.
The editor does not need a database to display these APIs. Running this example
still requires the normal persistence, event and transaction setup described in
the corresponding tutorials.

### Try an inbound Java mapping before generation

Using the earlier `Caption` value, add:

```vernac
usecase ReadCaption() returns Caption behavior {
    execute { return Caption.of("Live editor APIs"); }
}
adapter CaptionApi inbound http uses ReadCaption read {
    base "/api/caption";
    get {
        GET "/";
        execute read {}
        respond 200 Reply using output;
    }
    schema Reply(String caption);
    mapping output: Reply <- Caption {
        java { return Reply.of(source.string()); }
    }
}
```

Inside the Java mapping, try completion after `Reply.` and navigation to its schema.
The record is nested in the generated `CaptionApiDtos` holder, but its mapping-local
name remains `Reply`. Changing the schema updates the live record API; no generated
file needs to exist first. MVC adapters use the same live DTO/mapping mechanism.
The endpoint and its runtime error handling still require building and starting the
application normally.
