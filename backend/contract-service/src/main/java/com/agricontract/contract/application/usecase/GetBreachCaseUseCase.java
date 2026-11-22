package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.exception.BreachCaseNotFoundException;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.exception.UnauthorizedContractAccessException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.breach.BreachCase;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.repository.BreachCaseRepository;
import com.agricontract.contract.domain.repository.ContractRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * getBreachCase (D84): a BUYER or SELLER only on their own contract (403, checked before the case is looked up, so a
 * stranger learns nothing); an ARBITRATOR reads any case. No fallback: any other role is refused.
 */
@Service
@RequiredArgsConstructor
public class GetBreachCaseUseCase {
    private final ContractRepository contracts;
    private final BreachCaseRepository breachCases;
    private final TransactionRunner transactions;

    public BreachCase execute(String contractId, String breachCaseId, String actorId, Set<String> roles) {
        return transactions.inTransaction(() -> {
            boolean arbitrator = roles.contains("ARBITRATOR");
            boolean party = roles.contains("BUYER") || roles.contains("SELLER");
            if (!arbitrator && !party) {
                throw new UnauthorizedContractAccessException("This role cannot read breach cases.");
            }
            Contract contract = contracts.findById(new ContractId(contractId)).orElse(null);
            if (!arbitrator && (contract == null || contract.roleOf(actorId).isEmpty())) {
                throw new UnauthorizedContractAccessException("This user is not a party of this contract.");
            }
            if (contract == null) {
                throw new ContractNotFoundException(contractId);
            }
            return breachCases.findById(breachCaseId).filter(c -> c.contractId().equals(contractId))
                    .orElseThrow(() -> new BreachCaseNotFoundException(contractId, breachCaseId));
        });
    }
}
