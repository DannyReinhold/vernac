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

This guide describes the **0.3.0 plugin**. Match its compiler/runtime version, and
check the packaged descriptor for IDE compatibility.

- Use a compatible IntelliJ IDEA installation. The current plugin descriptor targets build `262.10968.63` through the
  `262.*` series.
- Keep IntelliJ's Java and Maven plugins enabled. The plugin also requires IntelliJ's LSP support.
- Register a JDK with Java 25 in **Project Structure → SDKs**. The wizard currently selects registered JDKs; it
  does not download a new JDK for you.
- Allow internet access for the initial Maven Wrapper and dependency downloads.

### Released plugin versus a source build

For the published 0.3.0 plugin, the wizard selects matching 0.3.0 Maven artifacts.
They are downloaded from Central; building the Vernac repository is unnecessary.
Before that release is published, use the source-build route below instead.

A development plugin built from this checkout uses **0.3.0-SNAPSHOT**. Install its
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
| Name         | `TaskManager`                          |
| Location     | A directory for your new project       |
| GroupId      | `com.example`                          |
| ArtifactId   | `task-manager`                         |
| Namespace    | `com.example.taskmanager`              |
| JDK          | A registered JDK with Java 25 |

The artifact ID is suggested from the project name: `TaskManager` becomes `task-manager`. You can edit it independently.
GroupId and namespace are separate settings; set both explicitly as needed.

Choose **Create**. The wizard writes the project, sets its project JDK, and configures Maven executions to use that JDK.
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

Hover over a reference such as `TaskId` to inspect any information supplied by the server. Then place the cursor on the
reference and use **Go to Declaration** from the context menu.

Type navigation works across files and namespaces, including explicit imports.
Inline `behavior` bodies also support Java completion and navigation; see the
[behavior tutorial](behavior.md).

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
