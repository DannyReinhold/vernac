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
| Project settings                 | Choose a project name, Maven coordinates, Java package, and registered JDK.                                 |
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

This guide describes the development plugin version `0.1.0-dev.3`.

- Use a compatible IntelliJ IDEA installation. The current plugin descriptor targets build `262.10968.63` through the
  `262.*` series.
- Keep IntelliJ's Java and Maven plugins enabled. The plugin also requires IntelliJ's LSP support.
- Register a JDK with Java 21 or newer in **Project Structure → SDKs**. The wizard currently selects registered JDKs; it
  does not download a new JDK for you.
- Allow internet access for the initial Maven Wrapper and dependency downloads.

### Current development-version requirement

The generated project uses Vernac `0.1.0-SNAPSHOT` artifacts. These must currently be installed in your local Maven
repository.

Build the Vernac source repository with JDK 25, as required by its IntelliJ module:

```shell
mvn clean install
```

Run this command from the repository's root POM directory. Check `mvn -version` if you are unsure which JDK Maven uses.

This repository build requirement differs from the generated project's Java 21 target. Installing the IDE plugin bundles
the language server, but does not install the compiler and runtime artifacts into your Maven repository.

## 2. Install the plugin

For the current development workflow, use the plugin ZIP produced under `vernac-intellij/target/` by the repository
build.

1. Open IntelliJ **Settings → Plugins**.
2. Open the gear menu and choose **Install Plugin from Disk…**.
3. Select the plugin ZIP, rather than an individual JAR.
4. Restart IntelliJ if requested.
5. Confirm that **Vernac** appears and is enabled in the installed plugins list.

No separate language-server installation or LSP4IJ configuration is required. If you previously configured Vernac
through LSP4IJ, disable that Vernac server configuration to avoid running two integrations for the same files. You can
keep LSP4IJ for other languages.

## 3. Create a project

Open **New Project** and choose **Vernac**. Enter, for example:

| Field        | Example                                |
|--------------|----------------------------------------|
| Name         | `TaskManager`                          |
| Location     | A directory for your new project       |
| GroupId      | `com.example`                          |
| ArtifactId   | `task-manager`                         |
| Java package | `com.example.taskmanager`              |
| JDK          | A registered JDK with Java 21 or newer |

The artifact ID is suggested from the project name: `TaskManager` becomes `task-manager`. You can edit it independently.
GroupId and Java package are separate settings; set both explicitly as needed.

Choose **Create**. The wizard writes the project, sets its project JDK, and configures Maven executions to use that JDK.
After the project opens, it starts the Maven import and opens `src/main/vernac/tasks.vernac`.

The generated POM contains your coordinates and display name:

```xml

<groupId>com.example</groupId>
<artifactId>task-manager</artifactId>
<version>0.1.0-SNAPSHOT</version>

<name>TaskManager</name>
```

The POM's Java target remains 21, even if you select a newer JDK.

## 4. Explore the generated files

| File                                                        | Purpose                                                  |
|-------------------------------------------------------------|----------------------------------------------------------|
| `pom.xml`                                                   | Dependencies and Vernac source-generation configuration. |
| `src/main/vernac/tasks.vernac`                              | Your initial domain model.                               |
| `src/test/java/com/example/taskmanager/TaskTest.java`       | An example of using the generated model from Java.       |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | Maven Wrapper files.                                     |
| `README.md`                                                 | Project-specific build instructions and pointers.        |
| `.gitignore`, `.gitattributes`                              | Initial Git configuration files.                         |

The Java test's path, package declaration, and domain imports follow the package you selected. The Vernac file stays
under `src/main/vernac/`; its package declaration is updated accordingly.

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

Temporarily remove the semicolon from the `id TaskId;` declaration. Look for a diagnostic near the invalid declaration.
Restore the semicolon before continuing.

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

### Hover information and navigation

Hover over a reference such as `TaskId` to inspect any information supplied by the server. Then place the cursor on the
reference and use **Go to Declaration** from the context menu.

Navigation inside Vernac is supported. Navigation from a generated Java class back to the original Vernac declaration is
not part of this tutorial's current feature set. Mouse shortcuts depend on your keymap; use the named action when
testing navigation.

## 7. Add another Vernac file

In the Project tool window, select `src/main/vernac/` and open the **New** menu. Choose the Vernac file template:

- **Vernac File** starts a new model file.
- **Vernac Getting Started** provides the task example.

Enter the requested filename and base package. Check the resulting package declaration before building.

Use a different package or rename the sample types when inserting another getting-started model into the same project.
Two copies defining the same types in the same package will conflict.

Editor support recognizes `.vernac` files independently of their directory. The standard Maven setup, however, reads
model files from `src/main/vernac/`; keep build inputs there.

## 8. Make your first change

Inside the existing `Task` aggregate, add:

```java
public void start() {
    status(Status.IN_PROGRESS);
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
| No JDK is available in the wizard     | Register a JDK with Java 21 or newer under Project Structure → SDKs, then reopen the wizard.                                                                                                  |
| No highlighting or completion         | Confirm the file ends in `.vernac`, is associated with the Vernac file type, and the plugin is enabled. Reopen the file and inspect the IDE log for language-server startup errors if needed. |
| Maven cannot resolve Vernac artifacts | Install the current SNAPSHOT artifacts from the Vernac repository, and check that the IDE and command line use the intended Maven settings and local repository.                              |
| Java references are unresolved        | Run Maven `test` to generate sources, then reload Maven if needed.                                                                                                                            |
| “Load Maven Project” appears          | A new wizard-created project should import automatically. If it stays unlinked, you can load its POM manually to continue and report the automatic-import failure.                            |
| Terminal build uses the wrong JDK     | Check `JAVA_HOME` and `java -version`. The wizard configures the IDE project and Maven runner, not your terminal environment.                                                                 |
| A chosen package fails Vernac parsing | Java package validation does not yet cover every Vernac keyword interaction. Use another segment temporarily and report the rejected name.                                                    |

When reporting a problem, include the plugin version, IntelliJ build number, selected JDK, and relevant error message.

## Continue learning

Follow [Your First Domain Model](your-first-domain-model.md) for more exercises on model behavior and validation. If you
already created a project here, adapt that tutorial's package-specific examples to your selected package.

[All tutorials](index.md) · [Documentation home](../index.md)
