package com.agricontract.product;

import com.agricontract.product.application.usecase.ReapOrphanReservationsUseCaseImpl;
import com.agricontract.arch.LayerRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.agricontract.product", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule layers = LayerRules.forService("com.agricontract.product",
            // known: orphan-reservation age is supplied through @Value. See #537.
            ReapOrphanReservationsUseCaseImpl.class);

    @ArchTest
    static final ArchRule sharedKernel =
            LayerRules.sharedKernelMustNotDependOnInfrastructure("com.agricontract.product", "common");
}
