package showcase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Explicit profile: a missing PostgreSQL instance fails, rather than silently skipping. */
@SpringBootTest(
    properties = {
      "spring.docker.compose.enabled=false",
      "server.port=0",
      "spring.datasource.url=${DISPATCH_TEST_DB_URL:jdbc:postgresql://localhost:55436/dispatch_test}",
      "spring.datasource.username=${DISPATCH_TEST_DB_USER:dispatch}",
      "spring.datasource.password=${DISPATCH_TEST_DB_PASSWORD:local-showcase-only}"
    })
class DispatchDatabaseIT {
  @Autowired DispatchWalkthrough walkthrough;

  @Test
  void completeStoryAgainstPostgres() throws Exception {
    walkthrough.run();
  }
}
