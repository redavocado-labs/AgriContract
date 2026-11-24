package com.agricontract.contract.application.dto;

import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.vo.ContractStatus;

/**
 * ContractResponse.terminalDecision (D99): the final money decision the contract waits on (D93), kept after the
 * commit. moneyCleared once the contract reached targetStatus (escrow.contract_lock_cleared named the decision).
 */
public record TerminalDecisionView(ContractStatus targetStatus, boolean moneyCleared, RemedyDecision remedyDecision) {
}
