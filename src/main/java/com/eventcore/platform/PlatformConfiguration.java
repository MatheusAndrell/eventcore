package com.eventcore.platform;

import com.eventcore.platform.identity.UuidV7Generator;
import com.eventcore.platform.json.JsonCanonicalizer;
import com.eventcore.platform.json.StrictJsonReader;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PlatformConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    UuidV7Generator uuidV7Generator(Clock clock) {
        return new UuidV7Generator(clock, new SecureRandom());
    }

    @Bean
    StrictJsonReader strictJsonReader() {
        return new StrictJsonReader();
    }

    @Bean
    JsonCanonicalizer jsonCanonicalizer() {
        return new JsonCanonicalizer();
    }
}
