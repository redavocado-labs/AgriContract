package com.agricontract.bank.infrastructure.web;

import com.agricontract.bank.application.dto.LedgerQuery;
import com.agricontract.bank.application.exception.InvalidLedgerQueryException;
import com.agricontract.bank.application.usecase.QueryLedgerEntriesUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * getInternalLedgerEntries. Params arrive as text so a malformed uuid or date-time is the same 400 VALIDATION_ERROR as
 * the other rule breaks, not a framework type-mismatch error.
 */
@RestController
@RequestMapping("/internal/v1/bank/ledger")
@RequiredArgsConstructor
public class LedgerReadController {

    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final QueryLedgerEntriesUseCase query;

    @GetMapping
    public List<LedgerEntryResponse> list(@RequestParam(required = false) String contractId,
                                          @RequestParam(required = false) String participantId,
                                          @RequestParam(required = false) String remedyDecisionId,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to) {
        return query.execute(new LedgerQuery(uuid("contractId", contractId), uuid("participantId", participantId),
                        uuid("remedyDecisionId", remedyDecisionId), instant("from", from), instant("to", to)))
                .stream().map(LedgerEntryResponse::from).toList();
    }

    private static String uuid(String name, String value) {
        if (value == null) return null;
        if (!UUID.matcher(value).matches()) throw new InvalidLedgerQueryException(name + " is not a UUID");
        return value.toLowerCase();
    }

    private static Instant instant(String name, String value) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidLedgerQueryException(name + " is not an ISO-8601 date-time");
        }
    }
}
