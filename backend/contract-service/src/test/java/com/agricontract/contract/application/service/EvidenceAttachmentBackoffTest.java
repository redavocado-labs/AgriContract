package com.agricontract.contract.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EvidenceAttachmentBackoffTest {

    @Test
    void theBackoff_doublesFromFiveMinutes_andIsCappedAtSixHours() {
        assertThat(EvidenceAttachment.backoff(0)).isEqualTo(Duration.ofMinutes(5));
        assertThat(EvidenceAttachment.backoff(1)).isEqualTo(Duration.ofMinutes(10));
        assertThat(EvidenceAttachment.backoff(5)).isEqualTo(Duration.ofMinutes(160));
        assertThat(EvidenceAttachment.backoff(7)).isEqualTo(Duration.ofHours(6));
        assertThat(EvidenceAttachment.backoff(Integer.MAX_VALUE)).isEqualTo(Duration.ofHours(6));
    }
}
