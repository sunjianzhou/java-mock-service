package com.mock.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.mock")
public class TestMockApplication {
    public static void main(String[] args) {
        SpringApplication.run(TestMockApplication.class, args);
    }
}
