package com.agricontract.contract.domain.model.milestone;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CleanReceiptConsentTest {

    /** printf %s 'CONFIRM_CLEAN_RECEIPT|11111111-1111-1111-1111-111111111111|2|10000.000|9700.000' | sha256sum */
    private static final String LITERAL_VECTOR = "6aeec2147159fad4408b7f7a2be26caf19082037618ce8d2b9f2714f8ce682e4";

    @Test
    void theContentHash_isTheSha256OfTheLiteralString() {
        assertThat(CleanReceiptConsent.contentHash("11111111-1111-1111-1111-111111111111", 2,
                new BigDecimal("10000"), new BigDecimal("9700"))).isEqualTo(LITERAL_VECTOR);
        assertThat(CleanReceiptConsent.contentHash("11111111-1111-1111-1111-111111111111".toUpperCase(), 2,
                new BigDecimal("10000.0"), new BigDecimal("9700.000"))).isEqualTo(LITERAL_VECTOR);
    }
}
