package org.example.delivery.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties={"spring.docker.compose.enabled=false","demo.run=false",
        "spring.datasource.url=${vernac.test.url:jdbc:postgresql://localhost:55434/delivery_test}",
        "spring.datasource.username=${VERNAC_TEST_USER:delivery}","spring.datasource.password=${VERNAC_TEST_PASSWORD:local-demo-only}"})
@EnabledIfSystemProperty(named="vernac.test.database",matches="true")
class EventDemoTest {
    @Autowired EventDemoWalkthrough walkthrough;
    @Test void synchronousDeferredRetryAndPublisherRollback() throws InterruptedException { walkthrough.run(); }
}
