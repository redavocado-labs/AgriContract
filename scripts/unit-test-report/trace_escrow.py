# Report 5.1 (#521): the test methods behind each row of the escrow-service sheets, in sheet order (see
# trace_contract.py). Several rows may share one parameterized test: each row is one of its cases.
SERVICE = "escrow-service"

TESTS = {
    "ContractSignedConsumer": [
        ["readsAValidEvent"],
        ["startsTheChainFromTheSignedEventWithoutACorrelationId"],
        ["acceptsAContractWithoutASellerDeposit"],
        *[["rejectsWhatTheContractDoesNotAllow"]] * 10,
    ],
    "RequestActivationDeposits": [
        ["buyerAndSellerDepositsGiveTwoLegsAndTwoCommands"],
        ["noSellerDepositGivesOnlyTheBuyerLeg"],
        ["anInvalidBuyerAmountFailsBeforeAnythingIsStoredOrSent"],
        ["aNegativeSellerDepositIsRejectedNotTreatedAsNoDeposit"],
        ["anInvalidSellerAmountFailsBeforeTheBuyerLegIsStored"],
    ],
    "DepositLeg": [
        ["requestCreatesARequestedLegStampedWithTheGivenTime"],
        ["requestGivesEachLegItsOwnIdAndRequestEventId"],
        ["rejectsAContractIdThatIsNotAUuid"],
        ["rejectsAMissingUserId"],
        ["rejectsMissingPartyAmountOrTime"],
        ["moneyRejectsZeroAndExtraDecimals"],
    ],
    "ActivationDeposits": [
        ["lockedAndExhausted_isAFailedSeller"],
        ["exhaustedWhileTheOtherIsStillInProgress_isNotFailedYet"],
        ["bothExhausted_isBoth_andABuyerOnlyContractCanFail"],
        ["restartingAFailedLeg_givesItAFreshCycle_andLeavesOtherLegsAlone"],
    ],
    "BankLockResultConsumer": [
        ["completedResult_becomesALockedCommand"],
        ["failedResult_carriesTheReason"],
        ["milestoneIdIsOptional"],
        ["payloadOutsideTheSchema_isRefused"],
        ["wrongTypeOrProducer_isRefused"],
    ],
    "DepositLegResult": [
        ["completedResultLocksTheLeg_onceOnly"],
        ["retrySucceedingAfterAFailure_locksTheLeg"],
        ["failuresFollowTheBackoff_thenNoRetryIsLeft"],
        ["lateFailureForALockedLeg_isIgnored"],
        ["retryIsDueOnlyAtItsTime_andOnlyForAFailedLeg"],
        ["scheduleNeedsPositiveDurations"],
    ],
    "DepositLegReturn": [
        ["lockedLeg_requestsItsReturn_withAFreshCommandId"],
        ["failedLeg_isCancelled_andNeverRetriedAgain"],
        ["lockInFlight_waits_thenItsResultDecides"],
        ["returnConfirmed_once"],
        ["refusedReturn_isResentPerTheSchedule_thenStaysFailed"],
        ["aReturnResultForALegWithNoReturn_isRefused"],
        ["refundIsSettledOnlyWhenEveryLegIsCancelledOrReturned"],
    ],
}
