# Report 5.1 (#521): the test methods behind each row of the reputation-service sheets, in sheet order (see
# trace_contract.py). Rows that are values of one parameterized test share its method.
SERVICE = "reputation-service"

TESTS = {
    "ApplicationBoundary": [
        ["useCaseEntryPointsExposeOnlyImmutableApplicationDtos"],
        ["completionCommandMapsEveryFieldAndDomainNormalizesTime"],
        ["disputeCommandPreservesFlaggerResolutionAndDuplicateResult"],
        ["invalidResolutionDoesNotReachPersistence"],
        ["immutableConflictIsNotConvertedIntoSuccess"],
        ["queryMapsDomainHistoryToImmutableResultAndExplicitHttpAllowlist"],
    ],
    "FactAndScore": [
        *[["approvedBaseline"]] * 7,             # (0,0) (5,0) (4,1) (1,7) (0,7) (2,1) (1,2)
        ["countsDoNotOverflowLongAddition"],
        ["invalidCountsFail"],
        ["factsAreImmutableRecordsAndNormalizeToDatabasePrecision"],
        *[["requiredFields"]] * 11,              # 5 completed-contract fields, 6 dispute-outcome fields
        *[["strictUuid"]] * 4,
    ],
    "RabbitTopology": [
        ["ownsDlxAndPreservesCanonicalDeadLetterKeys"],
    ],
    "FrozenEventContract": [
        ["completionFixtureMatchesFrozenSchema"],
        ["disputeFixtureMatchesFrozenSchema"],
    ],
    "DlqMonitor": [
        ["nonemptyQueueEmitsSafeStructuredWarning"],
        ["emptyQueuesEmitNoWarningsAndOnlyOwnedQueuesAreInspected"],
        ["failedProbeIsSafeAndDoesNotStopOtherQueuesOrFutureChecks"],
        ["connectionTimeoutAndRuntimeFailuresAreContained"],
        ["asynchronousMonitorDriverDoesNotLogRawConnectionExceptions"],
        ["stalledBrokerHandshakeIsTimeBounded"],
        ["monitorUsesBoundedIsolatedConnectionConfiguration"],
        *[["refusesUnboundedTimeoutsAndTightPolling"]] * 5,  # (0,300000) (10001,300000) (1000,0) (1000,29999) ...
    ],
    "ReputationFactConsumer": [
        ["completionMapsCanonicalIdsAndUtcPrecision"],
        *[["malformedOrUnapprovedInputDoesNotWrite"]] * 18,   # each malformed or unapproved envelope / payload
        ["conflictingContentIsPermanentButDatabaseOutageIsRetryable"],
        ["invalidJsonAndNullArePermanent"],
        ["disputeMapsDirectlyWithoutParticipantLookup"],
        ["badDisputeRoleRejected"],
        ["stringEventVersionIsRejectedWithoutAppending"],
        ["invalidEnvelopeIsTranslatedWithoutExposingValuesOrCause"],
    ],
    "GatewayConfiguration": [
        *[["missingConfiguredTrustSecretPreventsStartup"]] * 4,  # null, "", " ", "\t"
    ],
    "BatchSummaryApplication": [
        ["batchCallsOneRepositoryOperationAndCapturesClockOnce"],
        ["publicSummaryUsesSameProjectionAndTimeForHistory"],
        ["queryMakesDefensiveCopyAndRejectsDuplicates"],
    ],
    "InternalServiceAuthFilter": [
        ["missingOrBlankConfigurationNeverAuthenticates"],
        ["servicePrincipalHasNoEndUserIdentityAndContextIsCleared"],
    ],
}
