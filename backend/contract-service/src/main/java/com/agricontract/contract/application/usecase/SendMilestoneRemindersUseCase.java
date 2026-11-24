package com.agricontract.contract.application.usecase;

import com.agricontract.calendar.VietnamBusinessDays;
import com.agricontract.contract.application.port.ContractEventPublisher;
import com.agricontract.contract.application.port.FundingStatusMail;
import com.agricontract.contract.application.port.MilestoneNotificationSettings;
import com.agricontract.contract.application.port.MilestoneStatusMail;
import com.agricontract.contract.application.port.TransactionRunner;
import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.milestone.FundingStatus;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.contract.domain.repository.ContractRepository;
import com.agricontract.contract.domain.repository.MilestoneRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Function;

/**
 * D96, the two BF-04 reminders, each written once per milestone:
 * <ul>
 *   <li>BUYER_RECEIVE_REMINDER: the milestone is still SELLER_WEIGHED buyerReceiveWindowDays business days after the
 *       seller's first declaration (design §3.2). The state does not change.</li>
 *   <li>FUNDING_REMINDER: fundingReminderBusinessDays business days before the milestone's funding date, while it is not
 *       requested yet; deadline is the start (00:00 ICT) of the funding date.</li>
 * </ul>
 * Each milestone is re-checked under the contract and milestone row locks in its own transaction, and the reminder is
 * claimed (reminded_at set only while NULL) before the command is written, so a second poll writes nothing. One failing
 * milestone does not stop the next.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SendMilestoneRemindersUseCase {
    private static final int BATCH = 100;
    /** Calendar days that surely cover the reminder's business days, holidays (Tết) included. */
    private static final int FUNDING_LOOKAHEAD_DAYS = 21;

    private final MilestoneRepository milestones;
    private final ContractRepository contracts;
    private final ContractEventPublisher events;
    private final MilestoneNotificationSettings settings;
    private final VietnamBusinessDays calendar;
    private final TransactionRunner transactions;
    private final Clock clock;

    /** Returns how many receive reminders were written. */
    public int remindBuyersToReceive() {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        return each(milestones.findIdsWeighedNotReceiveRemindedOfActiveContracts(BATCH), "buyer_receive",
                id -> receiveReminder(id, now));
    }

    /** Returns how many funding reminders were written. */
    public int remindBuyersToFund() {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        LocalDate today = now.atZone(VietnamBusinessDays.ICT).toLocalDate();
        return each(milestones.findIdsNotFundingRemindedOfActiveContracts(today.plusDays(FUNDING_LOOKAHEAD_DAYS),
                BATCH), "funding", id -> fundingReminder(id, today, now));
    }

    private int each(List<String> ids, String kind, Function<String, Boolean> one) {
        int written = 0;
        for (String milestoneId : ids) {
            try {
                if (Boolean.TRUE.equals(transactions.inTransaction(() -> one.apply(milestoneId)))) {
                    written++;
                }
            } catch (RuntimeException e) {
                log.error("event=milestone_reminder_failed kind={} milestoneId={} reason={}", kind, milestoneId,
                        e.getMessage(), e);
            }
        }
        return written;
    }

    private boolean receiveReminder(String milestoneId, Instant now) {
        Locked locked = lock(milestoneId);
        if (locked == null || locked.milestone().status() != MilestoneStatus.SELLER_WEIGHED
                || locked.milestone().sellerWeighing() == null) {
            return false;
        }
        Instant due = calendar.deadline(locked.milestone().sellerWeighing().firstDeclaredAt(),
                settings.buyerReceiveWindowDays());
        if (due.isAfter(now) || !milestones.claimBuyerReceiveReminder(milestoneId, now)) {
            return false;
        }
        events.milestoneStatusRequested(locked.contract(), locked.milestone(),
                MilestoneStatusMail.BUYER_RECEIVE_REMINDER, null, now);
        log.info("event=milestone_receive_reminder contractId={} milestoneId={}", locked.contract().getContractId()
                .value(), milestoneId);
        return true;
    }

    private boolean fundingReminder(String milestoneId, LocalDate today, Instant now) {
        Locked locked = lock(milestoneId);
        if (locked == null || locked.milestone().fundingStatus() != FundingStatus.NOT_REQUESTED) {
            return false;
        }
        LocalDate fundingDate = locked.milestone().fundingScheduledDate();
        LocalDate remindFrom = calendar.businessDaysBefore(fundingDate, settings.fundingReminderBusinessDays());
        // Not before the reminder day; and on or after the funding date the request itself is due, too late to remind.
        if (today.isBefore(remindFrom) || !today.isBefore(fundingDate)
                || !milestones.claimFundingReminder(milestoneId, now)) {
            return false;
        }
        Instant deadline = fundingDate.atStartOfDay(VietnamBusinessDays.ICT).toInstant();
        events.milestoneFundingStatusRequested(locked.contract(), locked.milestone(),
                FundingStatusMail.FUNDING_REMINDER, deadline, null, null, now);
        log.info("event=milestone_funding_reminder contractId={} milestoneId={} fundingDate={}",
                locked.contract().getContractId().value(), milestoneId, fundingDate);
        return true;
    }

    /** Contract lock first, as every contract-service write does, then the milestone; null when not ACTIVE. */
    private Locked lock(String milestoneId) {
        String contractId = milestones.findContractIdOf(milestoneId).orElse(null);
        if (contractId == null) {
            return null;
        }
        Contract contract = contracts.findByIdForUpdate(new ContractId(contractId)).orElse(null);
        Milestone milestone = milestones.findByIdForUpdate(milestoneId).orElse(null);
        if (contract == null || milestone == null || contract.getStatus() != ContractStatus.ACTIVE) {
            return null;
        }
        return new Locked(contract, milestone);
    }

    private record Locked(Contract contract, Milestone milestone) {
    }
}
