package com.agricontract.contract.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.agricontract.contract.application.exception.FileServiceFailedException;
import com.agricontract.contract.application.exception.FileServiceUnavailableException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Only a 5xx is file-service being down (503, retryable); any other unexpected status cannot be fixed by a retry. */
class HttpEvidenceFileStoreStatusTest {

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 599})
    void aServerError_isUnavailable_andRetryable(int status) {
        assertThat(HttpEvidenceFileStore.unexpected("storeOnBehalfOf", status, "x"))
                .isInstanceOf(FileServiceUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 301, 401, 403, 404, 409, 415, 422, 429})
    void anyOtherUnexpectedStatus_isAFailure_notRetryable(int status) {
        assertThat(HttpEvidenceFileStore.unexpected("storeOnBehalfOf", status, "x"))
                .isInstanceOf(FileServiceFailedException.class);
    }
}
