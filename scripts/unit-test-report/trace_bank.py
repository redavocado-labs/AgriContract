# Report 5.1 (#521): the test methods behind each row of the bank-service sheets, in sheet order (see
# trace_contract.py). Several rows may share one parameterized test: each row is one of its cases.
SERVICE = "bank-service"

TESTS = {
    "LedgerEntry": [
        ["createGeneratesIdentityAndKeepsTheGivenTime"],
        ["rejectsAWrongFund"],
        ["milestoneIdOnlyWithMilestonePayment"],
        ["remedyIdsComeTogether"],
        ["locksAreNeverRemedyLegs"],
        ["forfeiturePenaltyAndDamagesNeedADecision"],
        ["releaseAndRefundMayOrMayNotBeRemedyLegs"],
        ["rejectsMissingOrMalformedIds"],
    ],
    "Money": [
        ["normalizesToTwoDecimalsWithoutRounding"],
        ["normalizesToTwoDecimalsWithoutRounding"],
        ["normalizesToTwoDecimalsWithoutRounding"],
        ["normalizesToTwoDecimalsWithoutRounding"],
        ["rejectsAnAmountThatWouldNeedRounding"],
        ["rejectsZeroAndNegative"],
        ["rejectsZeroAndNegative"],
        ["rejectsNull"],
        ["fitsDecimal18Comma2"],
        ["sameAmountIsEqualWhateverTheInputScale"],
    ],
    "LedgerCommandConsumer": [
        ["readsAValidRequest"],
        ["readsReleaseAndRefundRequests"],
        ["aQueueRefusesAnotherOperationsEventOrEntryType"],
        ["rejectsWhatTheContractDoesNotAllow"],
    ],
    "ProcessLedgerCommand": [
        ["firstRequestRecordsOneEntryAndConfirmsIt"],
        ["repeatedRequestWritesNothingAndReconfirmsTheSameEntry"],
        ["sameSourceEventWithDifferentContentIsNotConfirmed"],
        ["ledgerRuleViolationIsAnsweredWithLockFailed"],
        ["losingAnInsertRaceReconfirmsTheWinnersEntry"],
    ],
}
