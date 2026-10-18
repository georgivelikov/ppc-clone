package dev.cutpay.bnplsim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Four pretend buy-now-pay-later providers in one process. Each has its own path, its own API shape and its own
 * rules, so that the adapters in the bnpl service have something real to translate.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BnplSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(BnplSimApplication.class, args);
    }
}
