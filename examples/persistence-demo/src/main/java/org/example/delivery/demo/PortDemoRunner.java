package org.example.delivery.demo;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
@ConditionalOnProperty(name="demo.run", havingValue="true", matchIfMissing=true)
public final class PortDemoRunner implements ApplicationRunner {
    private final PortDemoWalkthrough walkthrough;
    public PortDemoRunner(PortDemoWalkthrough walkthrough) { this.walkthrough = walkthrough; }
    @Override public void run(ApplicationArguments args) throws Exception { walkthrough.run(); }
}
