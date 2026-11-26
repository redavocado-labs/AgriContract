package com.agricontract.gateway;

import com.agricontract.arch.LayerRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Gateway uses config, filter and infrastructure packages rather than the three service layers. */
@AnalyzeClasses(packages = "com.agricontract.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule packageCycles = LayerRules.noPackageCycles("com.agricontract.gateway");
}
