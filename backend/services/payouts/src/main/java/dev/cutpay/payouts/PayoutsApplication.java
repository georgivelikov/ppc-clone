package dev.cutpay.payouts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PayoutsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayoutsApplication.class, args);
    }
}
