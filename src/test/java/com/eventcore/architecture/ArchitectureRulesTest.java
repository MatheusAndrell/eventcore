package com.eventcore.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.ViolationHandler;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ArchitectureRulesTest {

    private static final String FIXTURE_ROOT = "com.eventcore.architecture.fixture";

    private static final JavaClasses FIXTURES = new ClassFileImporter().importPackages(FIXTURE_ROOT);

    @Test
    void detectsFrameworkDependenciesInTheDomain() {
        assertThat(violatingDependencies(ArchitectureRules.domainIsIndependentOfFrameworks()))
                .containsExactly("orders.domain.Order -> java.sql.Timestamp");
    }

    @Test
    void detectsApplicationDependingOnAdapters() {
        assertThat(violatingDependencies(ArchitectureRules.applicationIsIndependentOfAdapters()))
                .containsExactlyInAnyOrder(
                        "billing.application.ChargeOrder -> orders.infrastructure.OrderTable",
                        "orders.application.PlaceOrder -> orders.web.OrderController");
    }

    @Test
    void detectsWebReachingInfrastructureAndPersistence() {
        assertThat(violatingDependencies(ArchitectureRules.webDoesNotReachInfrastructure()))
                .containsExactlyInAnyOrder(
                        "orders.web.OrderController -> orders.infrastructure.OrderTable",
                        "orders.web.OrderController -> org.springframework.jdbc.core.simple.JdbcClient");
    }

    @Test
    void allowsCrossContextAccessOnlyThroughApplicationOrSharedPackages() {
        assertThat(violatingDependencies(
                ArchitectureRules.contextsInteractThroughApplication(FIXTURE_ROOT, Set.of("shared"))))
                .containsExactly("billing.application.ChargeOrder -> orders.infrastructure.OrderTable");
    }

    @Test
    void detectsCyclesBetweenContexts() {
        EvaluationResult result = ArchitectureRules.contextsAreFreeOfCycles(FIXTURE_ROOT).evaluate(FIXTURES);

        assertThat(result.hasViolation()).isTrue();
        assertThat(String.join(" ", result.getFailureReport().getDetails()).replaceAll("\\s+", " "))
                .contains("Cycle detected: Slice billing -> Slice orders -> Slice billing");
    }

    private static Set<String> violatingDependencies(ArchRule rule) {
        EvaluationResult result = rule.evaluate(FIXTURES);
        Set<String> dependencies = new TreeSet<>();
        result.handleViolations(new ViolationHandler<Dependency>() {
            @Override
            public void handle(Collection<Dependency> violatingObjects, String message) {
                violatingObjects.forEach(dependency -> dependencies.add(
                        nameOf(dependency.getOriginClass()) + " -> " + nameOf(dependency.getTargetClass())));
            }
        });
        return dependencies;
    }

    private static String nameOf(JavaClass javaClass) {
        String name = javaClass.getName();
        return name.startsWith(FIXTURE_ROOT + ".") ? name.substring(FIXTURE_ROOT.length() + 1) : name;
    }
}
