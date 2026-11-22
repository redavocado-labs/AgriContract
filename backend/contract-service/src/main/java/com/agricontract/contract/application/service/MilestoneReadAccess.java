package com.agricontract.contract.application.service;

import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.exception.MilestoneNotFoundException;
import com.agricontract.contract.domain.exception.UnauthorizedContractAccessException;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Who may read a milestone (D84): a BUYER or SELLER only on their own contract (403, checked before the milestone is
 * looked up, so a stranger learns nothing about it); the staff roles the endpoint names read any milestone. No
 * fallback: a role not named is refused.
 */
@Service
@RequiredArgsConstructor
public class MilestoneReadAccess {
    private final ContractRepository contracts;
    private final MilestoneRepository milestones;

    public record Readable(Contract contract, Milestone milestone) {
    }

    public Readable require(String contractId, String milestoneId, String actorId, Set<String> actorRoles,
                            Set<String> staffRoles) {
        boolean staff = actorRoles.stream().anyMatch(staffRoles::contains);
        boolean party = actorRoles.contains("BUYER") || actorRoles.contains("SELLER");
        if (!staff && !party) {
            throw new UnauthorizedContractAccessException("This role cannot read milestones.");
        }
        Contract contract = contracts.findById(new ContractId(contractId)).orElse(null);
        if (!staff && (contract == null || contract.roleOf(actorId).isEmpty())) {
            throw new UnauthorizedContractAccessException("This user is not a party of this contract.");
        }
        if (contract == null) {
            throw new ContractNotFoundException(contractId);
        }
        Milestone milestone = milestones.findById(milestoneId)
                .filter(m -> m.contractId().equals(contractId))
                .orElseThrow(() -> new MilestoneNotFoundException(contractId, milestoneId));
        return new Readable(contract, milestone);
    }
}
