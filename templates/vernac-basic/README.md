# Vernac Basic Example

A small domain model and a Java test to help you get started with Vernac.

No database, Docker, or running Spring application is required.

## Requirements

- JDK 21 or a compatible newer JDK.
- Internet access for the initial download of Maven and dependencies.
- Vernac `0.1.0-SNAPSHOT` artifacts installed in your local Maven repository.

For the current development version, first run `mvn clean install`
from the Vernac repository root using the JDK required by that build.
Afterward, this example can be built independently.

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

The build generates Java classes from `src/main/vernac/tasks.vernac`
and runs `TaskTest`.

## Explore the Model

Start with:

- `src/main/vernac/tasks.vernac` — the domain model.
- `src/test/java/org/example/tasks/TaskTest.java` — its use from Java.

Try adding a `start()` method that changes the task status to
`IN_PROGRESS`, then add a test for it.

Change the Vernac source and rebuild. Do not edit generated Java files.

## IntelliJ IDEA

Open this directory as a Maven project.

Install the Vernac IntelliJ plugin for highlighting, completion,
diagnostics, and file templates. Run the Maven build once to generate
the Java classes.

## Tutorial

Follow [Your First Domain Model](https://vernac.org/tutorials/your-first-domain-model.html)
for more exercises.