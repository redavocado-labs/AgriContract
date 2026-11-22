package com.agricontract.contract.domain.model.quality;

public class InvalidQualityInputException extends RuntimeException {
    public InvalidQualityInputException(String message) {
        super(message);
    }
}
