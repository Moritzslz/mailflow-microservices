package de.flowsuite.mailboxservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(
        basePackages = {
            "de.flowsuite.mailboxservice",
            "de.flowsuite.mailflow.common",
            "de.flowsuite.security"
        })
public class MailboxServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MailboxServiceApplication.class, args);
    }
}
