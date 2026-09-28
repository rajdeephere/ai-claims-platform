package com.claimsai;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Module and layer rules, checked on every build (ADR-0002). Each module (identity, claim, payment, ...) has
 * api (controllers, DTOs) -> app (services, transactions) -> domain (entities, rules), plus infra
 * (repositories, adapters) and config.
 *
 * <p>Rules are METHODS, not {@code static final ArchRule} fields: Surefire 3.5 silently skips field-based
 * rules (their test source is a field, which its filter doesn't recognise), so a broken rule would pass as
 * "Tests run: 0" (BUG-001).
 */
@AnalyzeClasses(packages = "com.claimsai", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static void controllersDoNotUseRepositoriesDirectly(JavaClasses classes) {
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage("..infra..")
                .because("controllers go through application services, which own the transaction and the rules")
                .check(classes);
    }

    @ArchTest
    static void domainDoesNotDependOnOuterLayers(JavaClasses classes) {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..api..", "..app..", "..infra..", "..config..")
                .because("the domain model is the core; everything else depends on it, not the other way round")
                .check(classes);
    }

    @ArchTest
    static void commonDependsOnNoBusinessModule(JavaClasses classes) {
        noClasses().that().resideInAPackage("com.claimsai.common..")
                .should().dependOnClassesThat().resideInAnyPackage("com.claimsai.identity..")
                .because("common is shared infrastructure; business modules depend on it, never the reverse")
                .check(classes);
    }

    @ArchTest
    static void modulesAreFreeOfCycles(JavaClasses classes) {
        slices().matching("com.claimsai.(*)..").should().beFreeOfCycles().check(classes);
    }
}
