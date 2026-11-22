package com.agricontract.contract.application.usecase;

import com.agricontract.contract.application.dto.BreachCaseCommands;
import com.agricontract.contract.domain.model.breach.BreachCaseStatus;
import com.agricontract.contract.domain.repository.BreachCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * listBreachCasesForArbitration (D84): the ARBITRATOR queue across all contracts (the role is checked at the
 * endpoint), reportedAt newest first. awaitingApproval keeps the cases that are not RESOLVED and carry a
 * computedResolution. page >= 0, size 1..100.
 */
@Service
@RequiredArgsConstructor
public class ListBreachCasesForArbitrationUseCase {
    public static final int MAX_SIZE = 100;

    private final BreachCaseRepository breachCases;

    public BreachCaseCommands.BreachCasePageView execute(BreachCaseStatus status, boolean awaitingApproval, int page,
                                                         int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("page must be >= 0 and size between 1 and " + MAX_SIZE);
        }
        BreachCaseRepository.Page result = breachCases.findPage(status, awaitingApproval, page, size);
        int totalPages = (int) ((result.totalElements() + size - 1) / size);
        return new BreachCaseCommands.BreachCasePageView(result.content(), page, size, result.totalElements(),
                totalPages);
    }
}
