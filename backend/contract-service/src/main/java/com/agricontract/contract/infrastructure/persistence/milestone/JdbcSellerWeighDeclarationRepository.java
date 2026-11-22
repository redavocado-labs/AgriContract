package com.agricontract.contract.infrastructure.persistence.milestone;

import com.agricontract.contract.domain.model.milestone.SellerWeighDeclaration;
import com.agricontract.contract.domain.repository.SellerWeighDeclarationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** Plain INSERT; the table's triggers refuse UPDATE and DELETE (V17). */
@Repository
@RequiredArgsConstructor
public class JdbcSellerWeighDeclarationRepository implements SellerWeighDeclarationRepository {
    private static final RowMapper<SellerWeighDeclaration> ROW = (rs, n) -> new SellerWeighDeclaration(
            UUID.fromString(rs.getString("declaration_id")),
            rs.getString("milestone_id"),
            rs.getString("contract_id"),
            rs.getInt("declaration_number"),
            rs.getBigDecimal("seller_declared_weight").stripTrailingZeros(),
            rs.getString("seller_evidence_file_id"),
            rs.getString("delivery_certificate_file_id"),
            UUID.fromString(rs.getString("consent_id")),
            rs.getString("declared_by"),
            rs.getTimestamp("declared_at").toInstant());

    private final JdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(SellerWeighDeclaration d) {
        jdbc.update("INSERT INTO seller_weigh_declaration (declaration_id, milestone_id, contract_id,"
                        + " declaration_number, seller_declared_weight, seller_evidence_file_id,"
                        + " delivery_certificate_file_id, consent_id, declared_by, declared_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                d.declarationId().toString(), d.milestoneId(), d.contractId(), d.declarationNumber(),
                d.declaredWeight(), d.evidenceFileId(), d.deliveryCertificateFileId(), d.consentId().toString(),
                d.declaredBy(), Timestamp.from(d.declaredAt()));
    }

    @Override
    public List<SellerWeighDeclaration> findByMilestoneId(String milestoneId) {
        return jdbc.query("SELECT * FROM seller_weigh_declaration WHERE milestone_id = ?"
                + " ORDER BY declaration_number", ROW, milestoneId);
    }
}
