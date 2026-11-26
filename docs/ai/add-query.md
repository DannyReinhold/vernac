# Add a repository query

The repository already loads tasks by ID. Add a search for a state, ordered by title. `Tasks`
is the collection declared on the aggregate. The parameter `:state` binds the typed method
argument. The two added lines belong inside the repository declaration.

`src/main/vernac/guide/search/tasks.vernac`

```diff
 namespace guide.search;
 id TaskId;
 value Title(String);
 value TaskState = OPEN | DONE;
 aggregate Task[TaskId](Title, TaskState) list Tasks;
 repository TaskRepository for Task {
+    find Tasks withState(TaskState state)
+        where taskState = :state order by title;
 }
```

A Java caller can use the generated signature directly. In a configured project with the Vernac
Maven plugin and the matching JDBC runtime, `./mvnw compile` checks the model and this caller.

`src/main/java/guide/search/OpenTasks.java`

```java
package guide.search;

import guide.search.domain.TaskRepository;
import guide.search.domain.Tasks;
import guide.search.domain.TaskState;

public final class OpenTasks {
    public static Tasks load(TaskRepository tasks) {
        return tasks.withState(TaskState.OPEN);
    }
}
```

For a persistence acceptance case, save two open tasks with different titles and one done task
in a disposable PostgreSQL database. The query must return the two open tasks in title order.
[Repository query rules](../contracts/repository-queries.md)
