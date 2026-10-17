package dev.cutpay.acquirer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AcquirerSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(AcquirerSimApplication.class, args);
    }
}
