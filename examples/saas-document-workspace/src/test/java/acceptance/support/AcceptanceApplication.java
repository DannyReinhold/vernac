// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package acceptance.support;

import example.documentworkspace.DemoApplication;
import org.springframework.boot.SpringApplication;

/** Test-classpath launcher; never included in the production application JAR. */
public final class AcceptanceApplication {
    public static void main(String[] args) {
        if(!"true".equals(System.getenv("VERNAC_ACCEPTANCE_FIXTURE")))throw new IllegalStateException("Acceptance fixture requires explicit test launch");
        new SpringApplication(DemoApplication.class,AcceptanceConfiguration.class).run(args);
    }
}
