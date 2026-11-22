package com.agricontract.contract.domain.model.consent;

/** What the consent is about; subjectId is that object's id. */
public enum ConsentSubjectType {
    CONTRACT,
    /** A milestone action (weigh, receive, confirm, flag, respond); subjectId is the milestoneId. */
    MILESTONE
}
