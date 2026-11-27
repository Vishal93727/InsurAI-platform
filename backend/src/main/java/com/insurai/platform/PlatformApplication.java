package com.insurai.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The one entry point of the platform. Three bounded contexts live beneath this package:
 * <ul>
 *   <li>{@code portal}      - corporate insurance portal (employees, HR, agents, admin, chatbot)</li>
 *   <li>{@code core}        - policy administration engine (rating, bitemporal policies, claims, renewals)</li>
 *   <li>{@code fraudengine} - real-time transaction fraud pipeline</li>
 * </ul>
 * {@code security} and {@code config} are the shared kernel: identity, error format, messaging defaults.
 *
 * <p>The three code bases were written independently and each has classes with the same simple name
 * ({@code ClaimService}, {@code WebConfig}, {@code SecurityConfig}, ...). The default bean name is the simple
 * class name, so a plain {@code @SpringBootApplication} would fail on startup with a
 * {@code ConflictingBeanDefinitionException}. Scanning with {@link FullyQualifiedAnnotationBeanNameGenerator}
 * names every bean after its full class name instead, which makes collisions impossible. It is declared on an
 * explicit {@code @ComponentScan} (rather than {@code SpringApplication#setBeanNameGenerator}) so that
 * {@code @SpringBootTest} and test slices get the same behaviour as the running application.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackageClasses = PlatformApplication.class,
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,
        excludeFilters = {
                // The two filters @SpringBootApplication would normally add; test slices rely on them.
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
        })
@ConfigurationPropertiesScan
@EnableScheduling
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
