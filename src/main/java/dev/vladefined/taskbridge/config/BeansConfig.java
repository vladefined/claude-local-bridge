package dev.vladefined.taskbridge.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.vladefined.taskbridge.core.HmacVerifier;

@Configuration
public class BeansConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    HmacVerifier hmacVerifier(BridgeProperties props, Clock clock) {
        return new HmacVerifier(props.hmacSecret(), props.maxClockSkew(), clock);
    }
}
