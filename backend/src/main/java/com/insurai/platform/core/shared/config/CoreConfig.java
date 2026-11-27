package com.insurai.platform.core.shared.config;

import com.insurai.platform.core.product.domain.rating.RatingEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
class CoreConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RatingEngine ratingEngine() {
        return RatingEngine.standard();
    }
}
