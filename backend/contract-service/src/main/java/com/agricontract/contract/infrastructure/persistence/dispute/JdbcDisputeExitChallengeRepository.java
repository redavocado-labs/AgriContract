package com.agricontract.contract.infrastructure.persistence.dispute;

import com.agricontract.contract.domain.model.dispute.DisputeExitType;
import com.agricontract.contract.domain.model.terms.VndAmount;
import com.agricontract.contract.domain.repository.DisputeExitChallengeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** dispute_exit_otp (V26): insert once with its challenge. */
@Repository
@RequiredArgsConstructor
public class JdbcDisputeExitChallengeRepository implements DisputeExitChallengeRepository {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(Binding b) {
        jdbc.update("INSERT INTO dispute_exit_otp (otp_id, milestone_id, exit_type, agreed_seller_entitlement_amount)"
                        + " VALUES (?, ?, ?, ?)", b.otpId().toString(), b.milestoneId(), b.type().name(),
                b.amount() == null ? null : b.amount().value());
    }

    @Override
    public Optional<Binding> findByOtpId(UUID otpId) {
        return jdbc.query("SELECT * FROM dispute_exit_otp WHERE otp_id = ?", (rs, n) -> {
            BigDecimal amount = rs.getBigDecimal("agreed_seller_entitlement_amount");
            return new Binding(UUID.fromString(rs.getString("otp_id")), rs.getString("milestone_id"),
                    DisputeExitType.valueOf(rs.getString("exit_type")), amount == null ? null : new VndAmount(amount));
        }, otpId.toString()).stream().findFirst();
    }
}
