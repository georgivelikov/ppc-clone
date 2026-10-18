package dev.cutpay.bnpl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BnplApplication {

    public static void main(String[] args) {
        SpringApplication.run(BnplApplication.class, args);
    }
}
