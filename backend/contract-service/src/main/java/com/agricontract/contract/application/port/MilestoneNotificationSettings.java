package com.agricontract.contract.application.port;

/**
 * D96: the BF-04 reminders and the operator recipient. buyerReceiveWindowDays (design §3.2, default 2 business days
 * after the seller weighed), fundingReminderBusinessDays (default 2 business days before the funding date), and the
 * operator email added to FUNDING_FAILED (null when not configured).
 */
public record MilestoneNotificationSettings(int buyerReceiveWindowDays, int fundingReminderBusinessDays,
                                            String operatorEmail) {
    public MilestoneNotificationSettings {
        if (buyerReceiveWindowDays < 1 || fundingReminderBusinessDays < 1) {
            throw new IllegalArgumentException("reminder windows must be at least one business day");
        }
        operatorEmail = operatorEmail == null || operatorEmail.isBlank() ? null : operatorEmail.trim();
    }
}
