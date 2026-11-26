package com.agricontract.contract;

import com.agricontract.contract.application.service.ReservationCompensation;
import com.agricontract.contract.application.service.SignerEligibility;
import com.agricontract.contract.application.usecase.RecordDepositLockFailureUseCase;
import com.agricontract.arch.LayerRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.agricontract.contract", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule layers = LayerRules.forService("com.agricontract.contract",
            // known: retry policy is supplied through @Value. See #537.
            ReservationCompensation.class,
            // known: signing authentication age is supplied through @Value. See #537.
            SignerEligibility.class,
            // known: one-sided cancellation window is supplied through @Value. See #537.
            RecordDepositLockFailureUseCase.class);

    @ArchTest
    static final ArchRule sharedKernel =
            LayerRules.sharedKernelMustNotDependOnInfrastructure("com.agricontract.contract", "common");
}
