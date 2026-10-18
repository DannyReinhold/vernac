# Vernac Basic Example

A small domain model and a Java test to help you get started with Vernac.

No database, Docker, or running Spring application is required.

## Requirements

- JDK 25 (the generated application targets Java 21).
- Internet access for the initial download of Maven and dependencies.

<!-- vernac-dependency-note:start -->
This example uses Vernac `0.5.0-SNAPSHOT`. Install the matching artifacts
in your local Maven repository first by running `mvn clean install`
from the Vernac repository root with the JDK required by that build.
<!-- vernac-dependency-note:end -->

A separate Maven installation is not required to build this example:
the Maven Wrapper is included.

## Run the Example

Open a terminal in this directory.

Windows PowerShell:

```powershell
.\mvnw.cmd clean test
```

Linux or macOS:

```shell
./mvnw clean test
```

The build generates Java classes from `src/main/vernac/org/example/tasks/tasks.vernac`
and runs `TaskTest`.

## Explore the Model

Start with:

- `src/main/vernac/org/example/tasks/tasks.vernac` — the domain model.
- `src/test/java/org/example/tasks/TaskTest.java` — its use from Java.

Try adding a `modify void start()` method in the `behavior` block that changes the task status to
`IN_PROGRESS` using `self.status(Status.IN_PROGRESS)`, then add a test for it.

Change the Vernac source and rebuild. Do not edit generated Java files.

## IntelliJ IDEA

Open this directory as a Maven project.

Install the Vernac IntelliJ plugin for highlighting, completion,
diagnostics, and file templates. Run the Maven build once to generate
the Java classes.

## Tutorial

Follow [Your First Domain Model](https://vernac.org/tutorials/your-first-domain-model.html)
for more exercises.