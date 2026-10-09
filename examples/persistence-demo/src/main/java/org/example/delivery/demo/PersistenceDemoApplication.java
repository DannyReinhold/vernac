package org.example.delivery.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "org.example.delivery")
public class PersistenceDemoApplication {
    public static void main(String[] args) { SpringApplication.run(PersistenceDemoApplication.class,args); }
}
