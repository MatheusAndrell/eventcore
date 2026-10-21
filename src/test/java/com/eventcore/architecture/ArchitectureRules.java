package com.eventcore.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Optional;
import java.util.Set;

final class ArchitectureRules {

    private ArchitectureRules() {
    }

    static ArchRule domainIsIndependentOfFrameworks() {
        return noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta..",
                        "java.sql..",
                        "javax.sql..",
                        "com.rabbitmq..",
                        "tools.jackson..",
                        "com.fasterxml.jackson..",
                        "org.flywaydb..")
                .allowEmptyShould(true);
    }

    static ArchRule applicationIsIndependentOfAdapters() {
        return noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("..web..", "..infrastructure..")
                .allowEmptyShould(true);
    }

    static ArchRule webDoesNotReachInfrastructure() {
        return noClasses().that().resideInAPackage("..web..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..infrastructure..",
                        "org.springframework.jdbc..",
                        "org.springframework.amqp..",
                        "com.rabbitmq..")
                .allowEmptyShould(true);
    }

    static ArchRule contextsInteractThroughApplication(String rootPackage, Set<String> sharedContexts) {
        return classes().that().resideInAPackage(rootPackage + "..")
                .should(onlyReachOtherContextsThroughApplication(rootPackage, sharedContexts))
                .allowEmptyShould(true);
    }

    static ArchRule contextsAreFreeOfCycles(String rootPackage) {
        return slices().matching(rootPackage + ".(*)..")
                .should().beFreeOfCycles()
                .allowEmptyShould(true);
    }

    private static ArchCondition<JavaClass> onlyReachOtherContextsThroughApplication(
            String rootPackage, Set<String> sharedContexts) {
        return new ArchCondition<>("only depend on other contexts through their application package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Optional<String> originContext = contextOf(rootPackage, origin.getPackageName());
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    contextOf(rootPackage, targetPackage)
                            .filter(targetContext -> !originContext.equals(Optional.of(targetContext)))
                            .filter(targetContext -> !sharedContexts.contains(targetContext))
                            .filter(targetContext -> !isApplicationPackage(rootPackage, targetContext, targetPackage))
                            .ifPresent(targetContext -> events.add(
                                    SimpleConditionEvent.violated(dependency, dependency.getDescription())));
                }
            }
        };
    }

    private static Optional<String> contextOf(String rootPackage, String packageName) {
        String prefix = rootPackage + ".";
        if (!packageName.startsWith(prefix)) {
            return Optional.empty();
        }
        String relative = packageName.substring(prefix.length());
        int separator = relative.indexOf('.');
        return Optional.of(separator < 0 ? relative : relative.substring(0, separator));
    }

    private static boolean isApplicationPackage(String rootPackage, String context, String packageName) {
        String applicationPackage = rootPackage + "." + context + ".application";
        return packageName.equals(applicationPackage) || packageName.startsWith(applicationPackage + ".");
    }
}
