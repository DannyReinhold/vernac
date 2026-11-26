# Start a model in an empty application

In an existing Maven application, keep its POM. For an empty directory, use the 0.6.0 POM at the
end of this page. Put this model at `src/main/vernac/ai/tasks/tasks.vernac`.

```vernac
namespace ai.tasks;

id TaskId;
value Title(String) validates {
    require(!self.string().isBlank(), "Title must not be blank");
};
value TaskState = OPEN | COMPLETED;
event TaskCompleted(TaskId);

aggregate Task[TaskId](Title, mut TaskState) behavior {
    modify void complete() {
        self.taskState(TaskState.COMPLETED);
    }
} list Tasks;

repository TaskRepository for Task {
    find Tasks withState(TaskState state)
        where taskState = :state order by title;
    find Tasks search(String text)
        where title contains :text order by title;
}

usecase CreateTask(Title) returns TaskId uses TaskRepository tasks behavior {
    execute {
        var task = Task.create(title, TaskState.OPEN);
        self.tasks().save(task);
        return task.id();
    }
}

usecase CompleteTask(TaskId) uses TaskRepository tasks behavior {
    execute {
        var task = self.tasks().byId(taskId);
        if (task.taskState() != TaskState.COMPLETED) {
            task.complete();
            self.tasks().save(task);
            self.emit(TaskCompleted.create(task.id()));
        }
    }
}

synchronous listener ObserveCompletion on TaskCompleted behavior {
    execute { System.out.println("Task completed: " + event.taskId().asString()); }
}
```

`CreateTask.execute` returns the new ID. `CompleteTask` loads the same aggregate, changes its state
and explicitly saves it. Calling it again after completion emits no further event. The listener logs
the occurrence. For an existing model, reuse its namespace, repository and operation names.

Save this domain test as `src/test/vernac/ai/tasks/completion.vernac`.

```vernac-test
namespace ai.tasks;
suite Completion {
    scenario completesAnOpenTask {
        given task = Task(Title("First task"), TaskState.OPEN);
        when task.complete();
        expect task.taskState equals TaskState.COMPLETED;
    }
}
```

Run `mvn test` (or `./mvnw test` with the project's wrapper). Maven compiles the model and runs
`Completion.completesAnOpenTask` in memory. Generated Java appears under
`target/generated-sources/vernac/`. To verify persistence, apply the reviewed migration to a
disposable PostgreSQL database and run the application's integration test.
[Build and migration commands](../ai-tools-reference.md)

## Minimal POM for the model and test

Save this as `pom.xml` alongside `src/`. The plugin and JDBC starter share one version. For a
development checkout, use its locally installed artifacts, for example
`mvn -Dvernac.version=0.6.0-SNAPSHOT test` before the 0.6.0 release.

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.2</version>
        <relativePath/>
    </parent>
    <groupId>example</groupId>
    <artifactId>task-probe</artifactId>
    <version>1.0.0</version>
    <properties>
        <java.version>21</java.version>
        <vernac.version>0.6.0</vernac.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.vernac</groupId>
            <artifactId>vernac-jdbc-spring-boot-starter</artifactId>
            <version>${vernac.version}</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.vernac</groupId>
                <artifactId>vernac-maven-plugin</artifactId>
                <version>${vernac.version}</version>
                <executions>
                    <execution>
                        <phase>generate-sources</phase>
                        <goals><goal>compile</goal></goals>
                    </execution>
                    <execution>
                        <id>vernac-scenarios</id>
                        <goals><goal>test-compile</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```
