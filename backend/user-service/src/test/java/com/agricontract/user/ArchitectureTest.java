package com.agricontract.user;

import com.agricontract.arch.LayerRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.agricontract.user", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule layers = LayerRules.forService("com.agricontract.user");

    @ArchTest
    static final ArchRule sharedKernel =
            LayerRules.sharedKernelMustNotDependOnInfrastructure("com.agricontract.user", "common");
}
