package com.agricontract.contract.domain.model.milestone;

/** What contract-service knows of a file's virus scan (D71): PROCESSING until file.ready or file.failed arrives. */
public enum EvidenceScanStatus {
    PROCESSING,
    READY,
    FAILED
}
