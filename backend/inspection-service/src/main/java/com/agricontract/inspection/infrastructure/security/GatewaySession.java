package com.agricontract.inspection.infrastructure.security;

import java.time.Instant;

/** Details installed only after the internal gateway secret has been validated. */
public record GatewaySession(Instant authenticatedAt) {}
