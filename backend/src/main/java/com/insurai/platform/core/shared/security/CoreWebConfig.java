package com.insurai.platform.core.shared.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.insurai.platform.security.PlatformSecurityProperties;

/**
 * Registers the {@link Caller} argument resolver.
 *
 * <p>Declared as a {@code @Bean} method rather than as a class implementing {@code WebMvcConfigurer}.
 * {@code @WebMvcTest} slices include every scanned {@code WebMvcConfigurer} class but skip other
 * {@code @Configuration} classes, so the fraud engine's controller tests do not try to build core beans
 * (a {@link RoleLookup}) they know nothing about.
 */
@Configuration(proxyBeanMethods = false)
class CoreWebConfig {

    @Bean
    WebMvcConfigurer callerArgumentResolver(RoleLookup roles, PlatformSecurityProperties security) {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CallerResolver(roles, security.jwt().issuer()));
            }
        };
    }
}
