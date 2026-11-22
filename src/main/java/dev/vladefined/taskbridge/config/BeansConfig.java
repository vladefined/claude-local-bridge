package dev.vladefined.taskbridge.config;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.vladefined.taskbridge.core.HmacVerifier;
import dev.vladefined.taskbridge.core.UiText;

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

    /** Language of the texts the owner sees (Telegram, toasts, local page): en (default) or ru. */
    @Bean
    UiText uiText(@Value("${bridge.ui.language:en}") String language) {
        return UiText.of(language);
    }
}
