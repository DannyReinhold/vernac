package org.example.delivery.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import static org.junit.jupiter.api.Assertions.*;

/** Runs generated code through real local HTTP; requires neither Docker nor PostgreSQL. */
class PortDemoTest {
    @Test void completeWalkthroughCanRunTwiceWithoutLeakingState() throws Exception {
        var walkthrough = new PortDemoWalkthrough();
        var first = walkthrough.run();
        var second = walkthrough.run();
        assertEquals(14, first.httpRequests());
        assertEquals(16, first.verified().size());
        assertEquals(first, second);
    }
    @Test void applicationRunnerUsesTheSameVerifiedWalkthrough() throws Exception {
        new PortDemoRunner(new PortDemoWalkthrough()).run(new DefaultApplicationArguments());
    }
}
