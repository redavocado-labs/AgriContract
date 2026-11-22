package com.agricontract.escrow.domain.model;

import java.math.BigDecimal;

/** An amount drawn from one pool: a command escrow sent that the bank has not answered yet. */
public record PoolAmount(FundPool pool, BigDecimal amount) {
}
