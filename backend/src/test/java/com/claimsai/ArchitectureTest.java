package com.claimsai;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Module and layer rules, checked on every build (ADR-0002). Each module (identity, claim, policy, audit, ...)
 * has api (controllers, DTOs) -> app (services, transactions) -> domain (entities, rules), plus infra
 * (repositories, adapters) and config.
 *
 * <p>Rules are METHODS, not {@code static final ArchRule} fields: Surefire 3.5 silently skips field-based
 * rules (their test source is a field, which its filter doesn't recognise), so a broken rule would pass as
 * "Tests run: 0" (BUG-001).
 */
@AnalyzeClasses(packages = "com.claimsai", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** Business modules with the api/app/domain/infra layout. */
    private static final List<String> MODULES = List.of("identity", "claim", "policy", "audit", "notification", "document");

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
    static void commonAndPlatformDependOnNoBusinessModule(JavaClasses classes) {
        noClasses().that().resideInAnyPackage("com.claimsai.common..", "com.claimsai.platform..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        MODULES.stream().map(m -> "com.claimsai." + m + "..").toArray(String[]::new))
                .because("shared infrastructure; business modules depend on it, never the reverse")
                .check(classes);
    }

    /** A module may use another module's domain types and services, never its repositories or adapters. */
    @ArchTest
    static void modulesDoNotReachIntoEachOthersInfrastructure(JavaClasses classes) {
        for (String module : MODULES) {
            String[] foreignInfra = MODULES.stream().filter(other -> !other.equals(module))
                    .map(other -> "com.claimsai." + other + ".infra..").toArray(String[]::new);
            noClasses().that().resideInAPackage("com.claimsai." + module + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(foreignInfra)
                    .because("another module's tables and adapters are its private implementation")
                    .check(classes);
        }
    }

    /** Notifications react to claim events; they may know the event vocabulary, nothing else of claims. */
    @ArchTest
    static void notificationsKnowOnlyTheClaimEventContract(JavaClasses classes) {
        noClasses().that().resideInAPackage("com.claimsai.notification..")
                .should().dependOnClassesThat().resideInAnyPackage("com.claimsai.claim.app..", "com.claimsai.claim.api..",
                        "com.claimsai.claim.infra..")
                .because("the outbox event is the only contract between the claim and notification modules")
                .check(classes);
    }

    @ArchTest
    static void modulesAreFreeOfCycles(JavaClasses classes) {
        slices().matching("com.claimsai.(*)..").should().beFreeOfCycles().check(classes);
    }
}
