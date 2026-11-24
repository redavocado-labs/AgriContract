package com.agricontract.contract.application.dto;

import java.util.UUID;

public record RetryRemedyLegsCommand(String contractId, String operatorUserId, UUID idempotencyKey) {
}
