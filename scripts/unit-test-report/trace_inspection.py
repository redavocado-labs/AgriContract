# Report 5.1 (#521): the test methods behind each row of the inspection-service sheets, in sheet order (see
# trace_contract.py). Rows that are values of one parameterized test share its method; where a row's text is terse
# the test body was read (e.g. InspectionAuthorization: which test calls requireCommission or requireMilestone).
SERVICE = "inspection-service"

TESTS = {
    "InspectionAuthorization": [
        ["eligibleAssignedInspectorCanAccessCommission"],
        ["eligibleInspectorCannotAccessOtherInspectorsCommission"],
        ["missingCommissionIsDeniedWithoutRevealingExistence"],
        ["historicalAssignmentNoLongerGrantsAccess"],
        ["eligibilityFailureCannotBeBypassedByOwningAssignment"],
        ["milestoneLookupUsesBothRouteIdentifiersAndChecksOwner"],
        ["milestoneLookupRejectsOtherOwner"],
        ["dependencyOutageCannotBeBypassedByOwningAssignment"],
    ],
    "InspectorEligibilityGate": [
        ["verifiedInspectorWithUnexpiredLicensePassesEligibilityOnly"],
        ["explicitUnlimitedLicenseIsAllowed"],
        *[["otherAuthenticatedRolesCannotUseInspectorGate"]] * 5,   # BUYER, SELLER, ADMIN, OPERATOR, ARBITRATOR
        *[["authoritativeProfileMustAlsoHaveInspectorRole"]] * 5,
        *[["unverifiedInspectorIsDenied"]] * 3,                     # PENDING, REJECTED, UNKNOWN
        *[["expiryAtOrBeforeNowIsDenied"]] * 2,                     # now - 1 s, now
        ["dependencyFailureFailsClosed"],
        ["missingProfileFailsClosed"],
        ["mismatchedProfileFailsClosed"],
        ["missingAuthenticationIsDenied"],
        ["unauthenticatedPrincipalIsDenied"],
        ["anonymousPrincipalIsDeniedEvenWithInspectorAuthority"],
    ],
    "InternalUserEligibility": [
        ["usesFrozenInternalPathAndServiceAuthenticationWithoutApplyingBuyerSellerLock"],
        ["parsesAuthoritativeLicenseExpiry"],
        *[["incompleteOrMismatchedResponsesFailClosed"]] * 5,       # {}, null, [], not JSON, userId "x"
        *[["missingRequiredEligibilityInformationFailsClosed"]] * 4,
        *[["malformedExpiryMustNotBecomeUnlimited"]] * 4,           # 0, false, "", "invalid timestamp"
        *[["dependencyHttpErrorsFailClosedWithoutLeakingResponse"]] * 6,  # 302, 401, 403, 404, 500, 503
        ["connectionFailureFailsClosed"],
        ["responseHeaderTimeoutIsTransportFailure"],
        ["interruptedResponseBodyIsTransportFailure"],
        ["malformedSensitiveResponseDoesNotEscapeIntoDiagnostics"],
        *[["invalidContentTypeRemainsFailClosed"]] * 2,
        ["emptySecretIsRejected"],
        ["poolUsesPrivateServiceEndpointAndMinimalIdentityResponse"],
        ["emptyAuthoritativePoolIsValid"],
        ["malformedPoolFailsClosedInsteadOfBecomingEmpty"],
        ["unavailablePoolIsNotReportedAsEmpty"],
        ["duplicatedPoolIdsCannotBiasSelection"],
    ],
    "GatewayAuthenticationFilter": [
        ["validatedGatewayIdentityPopulatesContextAndIsClearedAfterRequest"],
        *[["clientHeadersDoNotAuthenticateWithoutValidGatewaySecret"]] * 2,
        ["headersNeverOverrideExistingAuthenticatedIdentity"],
        *[["ambiguousIdentityHeadersAreRejected"]] * 3,            # X-User-Id, X-User-Role, X-Gateway-Secret twice
        *[["incompleteRoleDoesNotEstablishIdentityOrInvokeChain"]] * 5,  # null, "", " ", "\t", " \t "
        ["arbitratorIsRecognizedButCannotActAsInspector"],
        ["validMultipleRolesAreTrimmedAndDeduplicated"],
        ["healthBypassesIdentityValidation"],
        ["noDefaultOrBlankGatewaySecret"],
    ],
    "InspectionDlqMonitor": [
        ["nonEmptyDlq_logsWarnWithQueueAndCount"],
        ["emptyDlq_logsNothingAndOnlyCountsPassively"],
        ["failedProbe_logsNoBrokerDetailAndStillClosesTheConnection"],
        ["unreachableBroker_doesNotEscapeTheScheduledRun"],
        ["usesItsOwnBoundedConnectionSettings"],
        ["disabledPropertyOmitsMonitorBean"],
        ["enabledByDefaultUsesIsolatedProbe"],
    ],
    "InspectionHash": [
        ["rfcNumberAndRecursiveOrderingVector"],
        ["deterministicBytesAndEquivalentRepresentations"],
        ["unicodeOrderingEscapingAndInvalidSurrogates"],
        ["reasonsPreservePresenceAndOrder"],
        ["timestampAndReportProjectionArePreservedExactly"],
        ["requiredFieldsRejectAbsentAndNull"],
        ["subjectiveFieldsRejectedAtBothLevels"],
        ["strictInputRejectsCoercionDuplicatesTrailingAndInvalidValues"],
        ["metricsRequireEveryNonNullField"],
        ["reportRequiresExactNonNullStringFields"],
        ["additionalInvalidValuesAndMutableCallerListCannotBypassBoundary"],
        ["everyResultFieldTamperingIsRejected"],
        ["everyReportFieldTamperingIsRejected"],
        ["verificationAcceptsExactCommitmentAndRejectsMismatchedOrMalformedHashes"],
    ],
    "InspectionHashes": [
        ["noncanonicalOrInvalidTimestampsAreRejectedBeforeHashing"],
        ["canonicalTimestampAndLowercaseIdentitiesAreAccepted"],
        ["uppercaseUuidCannotProduceAlternativeCommitmentForSameIdentity"],
        ["quantityBoundsAcceptMeaningfulScaleAndTrailingZeros"],
        ["bothQuantitiesRejectOutOfDomainValues"],
        ["allPercentagesAcceptMeaningfulScaleAndTrailingZeros"],
        ["everyPercentageRejectsOutOfDomainValues"],
        ["acceptedQuantityCannotExceedMeasuredQuantity"],
        ["distinctAcceptedNumbersDoNotCollapseThroughJcsBinary64"],
        ["validDigestMismatchIsDistinctFromMalformedExpectedHash"],
    ],
    "InspectionHashContract": [
        ["generatedResultSatisfiesBothFrozenSchemasAndRejectsPolicyFields"],
        ["reportHashSourceTimestampTakesConfirmedAtValueWithoutRenamingProjectionKey"],
    ],
    "AssignmentConfiguration": [
        ["invalidCalendarCannotSilentlyBecomeWeekendOnly"],
        ["missingConfigurationFailsStartup"],
        ["validExternalConfigurationIsUsed"],
    ],
    "Level15RequestedConsumer": [
        ["validCanonicalEventUsesSharedCodecAndApplicationBoundary"],
        ["malformedAndInspectorOverridePayloadsAreRejectedWithoutMutation"],
        ["databaseOrDependencyFailuresRemainRetryable"],
        ["duplicateClaimDoesNotInvokeUseCase"],
        ["rejectsInvalidContractFields"],
    ],
}
