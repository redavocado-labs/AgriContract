package com.agricontract.contract.application.port;

import com.agricontract.contract.domain.model.milestone.EvidenceScanStatus;

import java.io.InputStream;

/**
 * file-service, service to service (supplementary-services-api.yaml storeOnBehalfOf, confirmAttached). The gateway
 * never exposes file-service: a party's evidence reaches it only through contract-service (D71).
 */
public interface EvidenceFileStore {

    /**
     * Streams the raw file to storeOnBehalfOf with X-Acting-User-Id = {@code actingUserId} and the same
     * Idempotency-Key. A repeat of the same (user, key) returns the same fileId without storing again.
     *
     * @throws com.agricontract.contract.application.exception.FileServiceUnavailableException file-service did not
     *         answer or answered 5xx: nothing recorded, safe to retry with the same key
     * @throws com.agricontract.contract.application.exception.FileRejectedException file-service refused the body
     *         (type, length, size)
     */
    StoredFile storeOnBehalfOf(String actingUserId, String idempotencyKey, String contentType, long contentLength,
                               InputStream body);

    /**
     * confirm-attached: contract-service keeps a reference to this file, so file-service's orphan cleanup must not
     * delete it. Idempotent.
     */
    void confirmAttached(String fileId);

    /**
     * The internal file-service GET (getFile, #274): the bytes of a READY file. The caller closes the stream.
     *
     * @throws com.agricontract.contract.application.exception.FileServiceUnavailableException file-service did not
     *         answer, or did not have the file
     */
    FileContent read(String fileId);

    record StoredFile(String fileId, EvidenceScanStatus status) {
    }

    /** contentLength is -1 when file-service did not send one. */
    record FileContent(String contentType, long contentLength, InputStream body) {
    }
}
