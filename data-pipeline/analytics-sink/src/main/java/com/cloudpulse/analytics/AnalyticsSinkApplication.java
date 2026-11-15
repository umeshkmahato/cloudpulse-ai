package com.cloudpulse.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AnalyticsSinkApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnalyticsSinkApplication.class, args);
    }
}
