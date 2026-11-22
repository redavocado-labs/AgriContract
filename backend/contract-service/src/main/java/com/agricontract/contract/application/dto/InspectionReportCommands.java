package com.agricontract.contract.application.dto;

import com.agricontract.contract.domain.model.inspection.InspectionReport;

/** inspection.report_confirmed (golden-flow-events.yaml inspectionReportConfirmed), after its hashes were verified. */
public final class InspectionReportCommands {
    private InspectionReportCommands() {
    }

    /** The report the event delivered; report.receivedEventId() is the event's eventId. */
    public record ApplyConfirmed(InspectionReport report) {
    }
}
