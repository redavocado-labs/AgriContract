package com.agricontract.escrow.application.dto;

/** contract.remedy_retry_requested, parsed: OPERATOR asked to resend the FAILED remedy legs of a contract (D93). */
public record RemedyRetryCommand(String eventId, String correlationId, String contractId, String operatorUserId) {
}
