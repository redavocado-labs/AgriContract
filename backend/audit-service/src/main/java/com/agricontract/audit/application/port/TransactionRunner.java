package com.agricontract.audit.application.port;

import java.util.function.Supplier;

/** Transaction boundary for the application layer (D44). Implemented in infrastructure. */
public interface TransactionRunner {

    /** Runs {@code work} in a transaction; a runtime failure propagates and rolls it back. */
    <T> T inTransaction(Supplier<T> work);
}
