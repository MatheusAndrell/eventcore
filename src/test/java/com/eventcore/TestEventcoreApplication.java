package com.eventcore;

import com.eventcore.support.TestcontainersConfiguration;
import org.springframework.boot.SpringApplication;

public class TestEventcoreApplication {

    public static void main(String[] args) {
        SpringApplication.from(EventcoreApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
