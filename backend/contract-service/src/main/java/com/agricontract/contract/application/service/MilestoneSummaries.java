package com.agricontract.contract.application.service;

import com.agricontract.calendar.VietnamBusinessDays;
import com.agricontract.contract.application.dto.MilestoneSummary;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.terms.VndAmount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/** The computed values of MilestoneResponse, one place for the milestone and the contract read models (D84, D99). */
@Service
@RequiredArgsConstructor
public class MilestoneSummaries {
    private final VietnamBusinessDays calendar;

    public MilestoneSummary of(Contract contract, Milestone milestone) {
        VndAmount price = milestone.effectiveUnitPrice() != null ? milestone.effectiveUnitPrice()
                : contract.getCoffeeTerms().snapshot().negotiated().agreedPrice();
        return new MilestoneSummary(milestone, price, effectiveDeliveryDeadline(milestone));
    }

    /**
     * expectedDeliveryDate + fundingDelayBusinessDays + graceDays, counted in Vietnam business days (MilestoneResponse,
     * design §6b).
     */
    LocalDate effectiveDeliveryDeadline(Milestone milestone) {
        LocalDate date = milestone.expectedDeliveryDate();
        int remaining = milestone.fundingDelayBusinessDays() + milestone.graceDays();
        while (remaining > 0) {
            date = date.plusDays(1);
            if (calendar.isBusinessDay(date)) {
                remaining--;
            }
        }
        return date;
    }
}
