package com.mock.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.mock")
public class MockApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockApplication.class, args);
    }
}
