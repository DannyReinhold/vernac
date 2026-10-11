package showcase;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@org.springframework.core.annotation.Order(100)
@ConditionalOnProperty(name = "dispatch.walkthrough", havingValue = "true")
public class WalkthroughRunner implements CommandLineRunner {
  private final DispatchWalkthrough walkthrough;

  public WalkthroughRunner(DispatchWalkthrough walkthrough) {
    this.walkthrough = walkthrough;
  }

  public void run(String... args) throws Exception {
    walkthrough.run();
  }
}
