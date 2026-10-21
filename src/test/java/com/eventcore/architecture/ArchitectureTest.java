package com.eventcore.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;

@AnalyzeClasses(packages = "com.eventcore", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT_PACKAGE = "com.eventcore";

    @ArchTest
    static final ArchRule domainIsIndependentOfFrameworks = ArchitectureRules.domainIsIndependentOfFrameworks();

    @ArchTest
    static final ArchRule applicationIsIndependentOfAdapters = ArchitectureRules.applicationIsIndependentOfAdapters();

    @ArchTest
    static final ArchRule webDoesNotReachInfrastructure = ArchitectureRules.webDoesNotReachInfrastructure();

    @ArchTest
    static final ArchRule contextsInteractThroughApplication =
            ArchitectureRules.contextsInteractThroughApplication(ROOT_PACKAGE, Set.of("platform"));

    @ArchTest
    static final ArchRule contextsAreFreeOfCycles = ArchitectureRules.contextsAreFreeOfCycles(ROOT_PACKAGE);
}
