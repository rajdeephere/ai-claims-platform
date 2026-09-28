package com.claimsai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ClaimsAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaimsAiApplication.class, args);
    }
}
