# Test an action on a rendered row

Start with a project generated from the **Spring Boot task-board template** on the 0.6 development
line or a later compatible build. It supplies `TaskWeb`, `CreateTask` and the test-scoped
`TaskScenarioFixtures` with a memory repository, MockMvc, security and real Thymeleaf templates.
Add this suite to the generated project. If the wizard used a custom namespace, substitute it for
`org.example.tasks` in the file path, namespace and fixture name.

`src/test/vernac/org/example/tasks/row-actions.vernac`

```vernac-test
namespace org.example.tasks;
suite RowActions profile mvc using org.example.tasks.TaskScenarioFixtures {
    scenario completesOnlyTheSelectedRow {
        when target = invoke CreateTask(TaskTitle("First"));
        when other = invoke CreateTask(TaskTitle("Second"));
        when request TaskWeb.board();
        expect action TaskWeb.complete(id = target) is present;
        when request TaskWeb.complete(id = target) csrf;
        when request TaskWeb.board();
        expect status 200;
        expect action TaskWeb.reopen(id = target) is present;
        expect action TaskWeb.complete(id = target) is absent;
        expect action TaskWeb.complete(id = other) is present;
        expect action TaskWeb.reopen(id = other) is absent;
    }
}
```

Run `./mvnw "-Dvernac.testProfile=mvc" -Dtest=VernacScenariosTest test` from that project
(Windows uses `./mvnw.cmd`). An action assertion matches the rendered form's HTTP method and
expanded path, including its row ID. The two IDs distinguish a row-specific regression from a
button that happens to exist elsewhere on the page. [Scenario and fixture setup](../tutorials/domain-scenarios.md#exercise-application-entry-points-and-rendered-pages)
