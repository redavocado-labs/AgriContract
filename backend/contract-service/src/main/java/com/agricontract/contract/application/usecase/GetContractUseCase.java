package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.dto.ActivationRecoveryView;
import com.agricontract.contract.application.dto.CoffeeContractView;
import com.agricontract.contract.application.dto.ContractDetail;
import com.agricontract.contract.application.dto.ContractResponse;
import com.agricontract.contract.application.dto.MilestoneSummary;
import com.agricontract.contract.application.dto.TerminalDecisionView;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.exception.UnauthorizedContractActionException;
import com.agricontract.contract.application.service.MilestoneSummaries;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.repository.ActivationCancelRequestRepository;
import com.agricontract.contract.domain.repository.ActivationRecoveryRepository;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import com.agricontract.contract.domain.repository.RemedyDecisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GetContractUseCase {

    private final ContractRepository contractRepository;
    private final ActivationRecoveryRepository activationRecoveries;
    private final ActivationCancelRequestRepository cancelRequests;
    private final MilestoneRepository milestones;
    private final RemedyDecisionRepository remedyDecisions;
    private final MilestoneSummaries summaries;

    public ContractDetail execute(String contractId, String requesterId, boolean isAdmin) {
        Contract contract = contractRepository.findById(new ContractId(contractId))
                .orElseThrow(() -> new ContractNotFoundException(contractId));

        if (!isAdmin && !requesterId.equals(contract.getBuyerId()) && !requesterId.equals(contract.getSellerId())) {
            throw new UnauthorizedContractActionException("Access denied");
        }

        if (contract.getCoffeeTerms() == null) {
            return new ContractDetail(null, ContractResponse.from(contract));
        }
        CoffeeContractView view = CoffeeContractView.from(contract).withActivationRecovery(
                activationRecoveries.findByContractId(contractId)
                        .map(recovery -> ActivationRecoveryView.from(recovery, cancelRequests
                                .findStanding(contractId).stream()
                                .map(r -> r.partyRole().name()).sorted().toList()))
                        .orElse(null));
        // D99: the real milestones, in order, and the final money decision the contract waits on (or waited on).
        List<MilestoneSummary> summary = milestones.findByContractId(contractId).stream()
                .map(m -> summaries.of(contract, m)).toList();
        TerminalDecisionView terminal = contract.getPendingTerminalDecisionId() == null ? null
                : remedyDecisions.findById(contract.getPendingTerminalDecisionId())
                        .map(d -> new TerminalDecisionView(contract.getPendingTerminalStatus(),
                                contract.getStatus() == contract.getPendingTerminalStatus(), d))
                        .orElse(null);
        return new ContractDetail(view.withExecution(summary, terminal), null);
    }
}
