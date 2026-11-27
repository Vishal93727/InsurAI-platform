package com.insurai.platform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The platform is a modular monolith: three bounded contexts that were written as separate services and share
 * only a small kernel ({@code security}, {@code config}). These rules keep it that way - if portal code ever
 * needs core's policies, it should go through an explicit interface in the kernel, not an import.
 *
 * <p>Each module's own internal layering is checked separately (see core's {@code ArchitectureTest}).
 */
@AnalyzeClasses(packages = "com.insurai.platform", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundariesTest {

    private static final String PORTAL = "com.insurai.platform.portal..";
    private static final String CORE = "com.insurai.platform.core..";
    private static final String FRAUD = "com.insurai.platform.fraudengine..";

    @ArchTest
    static final ArchRule portalStandsAlone = noClasses().that().resideInAPackage(PORTAL)
            .should().dependOnClassesThat().resideInAnyPackage(CORE, FRAUD)
            .because("modules communicate through the database, Kafka and the shared token, not by calling each other");

    @ArchTest
    static final ArchRule coreStandsAlone = noClasses().that().resideInAPackage(CORE)
            .should().dependOnClassesThat().resideInAnyPackage(PORTAL, FRAUD)
            .because("modules communicate through the database, Kafka and the shared token, not by calling each other");

    @ArchTest
    static final ArchRule fraudEngineStandsAlone = noClasses().that().resideInAPackage(FRAUD)
            .should().dependOnClassesThat().resideInAnyPackage(PORTAL, CORE)
            .because("modules communicate through the database, Kafka and the shared token, not by calling each other");

    @ArchTest
    static final ArchRule kernelDoesNotKnowTheModules = noClasses()
            .that().resideInAnyPackage("com.insurai.platform.security..", "com.insurai.platform.config..")
            .should().dependOnClassesThat().resideInAnyPackage(PORTAL, CORE, FRAUD)
            .because("the shared kernel sits below the modules; otherwise it becomes a hidden coupling point");
}
