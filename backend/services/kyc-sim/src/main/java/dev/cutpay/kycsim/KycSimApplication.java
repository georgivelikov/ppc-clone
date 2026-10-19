package dev.cutpay.kycsim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * A pretend identity-verification provider and company registry, in the way Onfido or Sumsub and a national trade
 * register would be one. Nothing is stored beyond memory and nothing here is real: use made-up people only.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class KycSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(KycSimApplication.class, args);
    }
}
