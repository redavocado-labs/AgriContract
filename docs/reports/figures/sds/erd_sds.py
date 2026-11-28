"""SDS I.3 Figures 4a, 4b, 5a, 5b: physical ERDs (crow's foot), keys and key columns only; 3.1-3.34 hold the full
column specifications.

Code first: a table created by Flyway is drawn from the schema the migrations produce (checked 26/11/2026, contract V1-V29 and escrow V9-V15, by
applying every service's migrations to MySQL 8.0 and reading information_schema). A table not created yet is
drawn «planned» (dashed) with its design columns. Lines are foreign keys (solid FK constraints, or the planned
ones of the design); cross-service references are ids without a line.
Output: fig04a/04b/05a/05b_*.drawio/.svg/.png.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from vpdiagram import Diagram  # noqa: E402

OUT = Path(__file__).resolve().parent
LEGEND = ("Bold underlined: primary key · italic: foreign key · (UQ) unique · dashed «planned»: in the design, "
          "not created by Flyway yet")

T = {
    # ---- contract_db, implemented (V1-V29) ----
    "contracts": dict(pk=["id : BIGINT"], cols=[
        "contract_id : VARCHAR(255) (UQ)", "listing_id : VARCHAR(255)", "buyer_id : VARCHAR(255)",
        "seller_id : VARCHAR(255)", "status : VARCHAR(30)", "terms_snapshot : LONGTEXT",
        "signed_content_hash : CHAR(64)", "version : BIGINT", "reserved_quantity : DECIMAL(15,3)",
        "pending_terminal_decision_id : CHAR(36)", "pending_terminal_status : VARCHAR(20)",
        "settled_at : TIMESTAMP(6)"]),
    "consent_evidence": dict(pk=["consent_id : CHAR(36)"], cols=[
        "action_type : VARCHAR(32)", "subject_type : VARCHAR(32)", "subject_id : VARCHAR(255)",
        "actor_user_id : VARCHAR(255)", "actor_role : VARCHAR(16)", "statement_code : VARCHAR(64)",
        "statement_version : VARCHAR(16)", "statement_hash : CHAR(64)", "presented_content_hash : CHAR(64)",
        "consented_at : TIMESTAMP(6)", "correlation_id : VARCHAR(64)"]),
    "signature_otp": dict(pk=["otp_id : CHAR(36)"],
        fk=["consent_id : CHAR(36) (FK)", "contract_id : VARCHAR(255) (FK)"], cols=[
        "action_type : VARCHAR(32)", "signer_role : VARCHAR(16)", "user_id : VARCHAR(255)",
        "auth_time : TIMESTAMP(6)", "signed_content_hash : CHAR(64)", "otp_hash : CHAR(64)",
        "sent_at : TIMESTAMP(6)", "expires_at : TIMESTAMP(6)", "verified_at : TIMESTAMP(6)", "attempt_count : INT"]),
    "signature": dict(pk=["signature_id : CHAR(36)"],
        fk=["contract_id : VARCHAR(255) (FK)", "otp_id : CHAR(36) (FK, UQ)", "consent_id : CHAR(36) (FK)"], cols=[
        "signer_role : VARCHAR(16)", "signer_user_id : VARCHAR(255)", "auth_time : TIMESTAMP(6)",
        "signed_at : TIMESTAMP(6)", "signed_content_hash : CHAR(64)", "ip_address : VARCHAR(45)",
        "UQ (contract_id, signer_role)"]),
    "activation_recovery": dict(pk=["contract_id : VARCHAR(255) (FK)"], cols=[
        "deposit_lock_failed_at : TIMESTAMP(6)", "one_sided_cancel_allowed_from : TIMESTAMP(6)",
        "last_failed_leg : VARCHAR(8)", "last_failure_reason : VARCHAR(500)", "last_failed_at : TIMESTAMP(6)",
        "failure_reports : INT"]),
    "activation_cancel_request": dict(pk=["request_id : CHAR(36)"],
        fk=["contract_id : VARCHAR(255) (FK)", "otp_id : CHAR(36) (FK, UQ)"], cols=[
        "party_role : VARCHAR(8)", "user_id : VARCHAR(255)", "requested_at : TIMESTAMP(6)",
        "withdrawn_at : TIMESTAMP(6)", "standing_role : VARCHAR(8) (generated)", "UQ (contract_id, standing_role)"]),
    "milestone": dict(pk=["milestone_id : VARCHAR(36)"], fk=["contract_id : VARCHAR(255) (FK)"], cols=[
        "milestone_index : INT", "committed_quantity : DECIMAL(10,3)", "expected_delivery_date : DATE",
        "funding_scheduled_date : DATE", "status : VARCHAR(32)", "funding_status : VARCHAR(16)",
        "effective_unit_price : DECIMAL(10,2)", "batch_amount : DECIMAL(18,2)",
        "seller_declared_weight : DECIMAL(10,3)", "buyer_received_weight : DECIMAL(10,3)",
        "settlement_path : VARCHAR(32)", "seller_amount : DECIMAL(18,2)", "buyer_refund_amount : DECIMAL(18,2)",
        "breach_case_id : VARCHAR(36)", "UQ (contract_id, milestone_index)"]),
    "milestone_short": dict(title="milestone", pk=["milestone_id : VARCHAR(36)"],
        fk=["contract_id : VARCHAR(255) (FK)"], cols=[
        "status : VARCHAR(32)", "funding_status : VARCHAR(16)", "breach_case_id : VARCHAR(36)"]),
    "seller_weigh_declaration": dict(pk=["declaration_id : CHAR(36)"],
        fk=["milestone_id : VARCHAR(36) (FK)", "consent_id : CHAR(36) (FK)"], cols=[
        "contract_id : VARCHAR(255)", "declaration_number : INT", "seller_declared_weight : DECIMAL(10,3)",
        "seller_evidence_file_id : VARCHAR(36)", "delivery_certificate_file_id : VARCHAR(36)",
        "declared_by : VARCHAR(255)", "declared_at : TIMESTAMP(6)"]),
    "milestone_evidence_file": dict(pk=["file_id : VARCHAR(36)"], fk=["milestone_id : VARCHAR(36) (FK)"], cols=[
        "contract_id : VARCHAR(255)", "uploaded_by : VARCHAR(255)", "idempotency_key : VARCHAR(36)",
        "named_at : TIMESTAMP(6)", "attached_confirmed_at : TIMESTAMP(6)", "next_attach_attempt_at : TIMESTAMP(6)"]),
    "file_scan_outcome": dict(pk=["file_id : VARCHAR(36)"], cols=[
        "status : VARCHAR(16)", "failure_reason : VARCHAR(500)", "received_at : TIMESTAMP(6)"]),
    "breach_case": dict(pk=["breach_case_id : VARCHAR(36)"], fk=["contract_id : VARCHAR(255) (FK)"], cols=[
        "milestone_id : VARCHAR(36) (UQ)", "requested_by : VARCHAR(16)", "breach_reason_code : VARCHAR(32)",
        "status : VARCHAR(32)", "final_breaching_role : VARCHAR(16)", "decision_source : VARCHAR(16)",
        "contest_status : VARCHAR(16)", "contest_deadline : TIMESTAMP(6)", "computed_resolution : JSON"]),
    "remedy_decision": dict(pk=["remedy_decision_id : VARCHAR(36)"],
        fk=["contract_id : VARCHAR(255) (FK)", "breach_case_id : VARCHAR(36) (FK)", "milestone_id : VARCHAR(36) (FK)"],
        cols=["status : VARCHAR(16)", "penalty_amount : DECIMAL(19,2)", "penalty_debt_amount : DECIMAL(19,2)",
              "round : TINYINT", "inspection_fee_party : VARCHAR(8)", "contract_level_key : VARCHAR(255) (UQ)",
              "UQ (breach_case_id, round)"]),
    "remedy_leg": dict(pk=["remedy_leg_id : VARCHAR(36)"], fk=["remedy_decision_id : VARCHAR(36) (FK)"], cols=[
        "seq : INT", "remedy_type : VARCHAR(24)", "fund_type : VARCHAR(24)", "role : VARCHAR(16)",
        "amount : DECIMAL(19,2)"]),
    "dispute_exit_otp": dict(pk=["otp_id : CHAR(36) (FK)"], fk=["milestone_id : VARCHAR(36) (FK)"], cols=[
        "exit_type : VARCHAR(16)", "agreed_seller_entitlement_amount : DECIMAL(19,2)"]),
    "dispute_settlement_proposal": dict(pk=["proposal_id : CHAR(36)"],
        fk=["contract_id : VARCHAR(255) (FK)", "milestone_id : VARCHAR(36) (FK)"], cols=[
        "agreed_seller_entitlement_amount : DECIMAL(19,2)", "proposed_by_role : VARCHAR(8)", "status : VARCHAR(16)",
        "open_milestone_id : VARCHAR(36) (UQ)"]),
    "level1_5_request": dict(pk=["request_id : CHAR(36)"], fk=["milestone_id : VARCHAR(36) (FK)"], cols=[
        "reason : VARCHAR(32)", "initiated_by_actor_id : VARCHAR(36)", "requested_at : TIMESTAMP(6)",
        "ended_at : TIMESTAMP(6)", "end_reason : VARCHAR(16)"]),
    "level1_5_assignment": dict(pk=["commission_id : VARCHAR(36)"], fk=["milestone_id : VARCHAR(36) (FK)"], cols=[
        "assigned_at : TIMESTAMP(6)", "free_cancel_until : TIMESTAMP(6)", "report_due_at : TIMESTAMP(6)"]),
    "inspection_report_c": dict(title="inspection_report", pk=["report_id : VARCHAR(36)"],
        fk=["contract_id : VARCHAR(255) (FK)", "milestone_id : VARCHAR(36) (FK)"], cols=[
        "measurement_status : VARCHAR(16)", "result_hash : CHAR(64)", "report_hash : CHAR(64)",
        "received_event_id : VARCHAR(36) (UQ)", "round : TINYINT", "purpose : VARCHAR(16)"]),
    "quality_assessment": dict(pk=["report_id : VARCHAR(36) (FK)"], cols=[
        "disposition : VARCHAR(24)", "type_outcome : VARCHAR(16)", "metric_outcomes : JSON",
        "quality_discount_rate : DECIMAL(7,4)", "quality_discount_amount : DECIMAL(19,2)",
        "quantity_adjusted_entitlement : DECIMAL(19,2)", "final_seller_entitlement : DECIMAL(19,2)",
        "buyer_refund_amount : DECIMAL(19,2)"]),
    # ---- contract_db, not created by Flyway ----
    "contract_document": dict(planned=True, pk=["document_id : CHAR(36)"], fk=["contract_id : VARCHAR(255) (FK)"],
        cols=["document_type : VARCHAR(30)", "template_code : VARCHAR(50)", "source_content_hash : CHAR(64)",
              "file_id : CHAR(36)", "status : VARCHAR(10)"]),
    "price_adjustment": dict(planned=True, pk=["proposal_id : CHAR(36)"], fk=["milestone_id : CHAR(36) (FK)"], cols=[
        "proposed_unit_price : DECIMAL(18,2)", "proposer_id : CHAR(36)", "accepter_id : CHAR(36)",
        "status : VARCHAR(10)"]),
    "milestone_sync_outbox": dict(planned="earlier design", pk=["outbox_id : CHAR(36)"],
        fk=["milestone_id : CHAR(36) (FK)"], cols=["processed : BOOLEAN", "retry_count : INT",
                                                    "processed_at : TIMESTAMP"]),
    # ---- escrow_db (V9-V15) ----
    "deposit_leg": dict(pk=["leg_id : CHAR(36)"], cols=[
        "contract_id : CHAR(36)", "leg : VARCHAR(10)", "user_id : CHAR(36)", "amount : DECIMAL(18,2)",
        "status : VARCHAR(30)", "request_event_id : CHAR(36) (UQ)", "ledger_entry_id : CHAR(36)",
        "failed_attempts : INT", "next_attempt_at : DATETIME(6)", "return_request_event_id : CHAR(36) (UQ)",
        "return_ledger_entry_id : CHAR(36)"]),
    "escrow_milestone": dict(pk=["milestone_id : CHAR(36)"], cols=[
        "contract_id : CHAR(36)", "buyer_id : CHAR(36)", "batch_amount : DECIMAL(18,2)", "status : VARCHAR(30)",
        "request_event_id : CHAR(36) (UQ)", "ledger_entry_id : CHAR(36)", "next_attempt_at : DATETIME(6)",
        "settlement_event_id : CHAR(36)", "settled_at : DATETIME(6)"]),
    "settlement_leg": dict(pk=["leg_id : CHAR(36)"], fk=["milestone_id : CHAR(36) (FK)"], cols=[
        "trigger_event_id : CHAR(36)", "entry_type : VARCHAR(30)", "amount : DECIMAL(18,2)",
        "request_event_id : CHAR(36) (UQ)", "status : VARCHAR(20)", "ledger_entry_id : CHAR(36)"]),
    "escrow_remedy_decision": dict(title="remedy_decision", pk=["remedy_decision_id : CHAR(36)"], cols=[
        "contract_id : CHAR(36)", "event_id : CHAR(36)", "status : VARCHAR(20)", "reject_reason : VARCHAR(500)",
        "created_at : DATETIME(6)"]),
    "escrow_remedy_leg": dict(title="remedy_leg", pk=["remedy_leg_id : CHAR(36)"],
        fk=["remedy_decision_id : CHAR(36) (FK)"], cols=[
        "contract_id : CHAR(36)", "milestone_id : CHAR(36)", "payee_role : VARCHAR(10)", "amount : DECIMAL(18,2)",
        "request_event_id : CHAR(36) (UQ)", "status : VARCHAR(20)", "next_attempt_at : DATETIME(6)",
        "ledger_entry_id : CHAR(36)"]),
    "contract_clearance_check": dict(pk=["contract_id : CHAR(36)"], cols=[
        "requested_at : TIMESTAMP(6)", "checked_at : TIMESTAMP(6)"]),
    # ---- bank_db (V1) ----
    "ledger_entry": dict(pk=["entry_id : CHAR(36)"], cols=[
        "source_event_id : CHAR(36) (UQ)", "contract_id : CHAR(36)", "milestone_id : CHAR(36)",
        "user_id : CHAR(36)", "entry_type : VARCHAR(30)", "fund_type : VARCHAR(30)",
        "remedy_decision_id : CHAR(36)", "remedy_leg_id : CHAR(36) (UQ)", "amount : DECIMAL(18,2)",
        "created_at : DATETIME(6)"]),
    "system_lock": dict(planned=True, pk=["lock_id : CHAR(36)"], cols=[
        "status : VARCHAR(20)", "verifier_org_id : CHAR(36)", "triggered_at : DATETIME(6)",
        "released_at : DATETIME(6)"]),
    "used_nonce": dict(planned=True, pk=["nonce : VARCHAR(64)"], cols=["seen_at : DATETIME(6)"]),
    # ---- reputation_db (V1-V2) ----
    "completed_contract_fact": dict(pk=["contract_id : CHAR(36)"], cols=[
        "buyer_id : CHAR(36)", "seller_id : CHAR(36)", "settled_at : DATETIME(6)",
        "source_event_id : CHAR(36) (UQ)"]),
    "dispute_outcome_fact": dict(pk=["milestone_id : CHAR(36)"], cols=[
        "contract_id : CHAR(36)", "flagged_by_user_id : CHAR(36)", "resolution_favors : VARCHAR(10)",
        "resolved_at : DATETIME(6)", "source_event_id : CHAR(36) (UQ)"]),
    "lock_entry": dict(pk=["entry_id : CHAR(36)"], cols=[
        "source_event_id : CHAR(36) (UQ)", "remedy_decision_id : CHAR(36) (UQ)", "contract_id : CHAR(36)",
        "user_id : CHAR(36)", "penalized_role : VARCHAR(10)", "base_days : INT",
        "repeat_offense_multiplier : DECIMAL(3,2)", "track_record_multiplier : DECIMAL(3,2)",
        "zero_progress_multiplier : DECIMAL(3,2)", "lock_duration_days : INT", "locked_until : DATETIME(6)"]),
    "lock_override_event": dict(pk=["event_id : CHAR(36)"], fk=["lock_entry_id : CHAR(36) (FK)"], cols=[
        "override_type : VARCHAR(20)", "reason : TEXT", "proposed_by : CHAR(36)", "approved_by : CHAR(36)"]),
    "governance_action_request": dict(planned=True, pk=["request_id : CHAR(36)"], cols=[
        "action_type : VARCHAR(30)", "target_id : VARCHAR(100)", "status : VARCHAR(15)", "reason : TEXT"]),
    # ---- inspection_db (V1-V2) ----
    "level1_5_inspection_assignment": dict(pk=["commission_id : CHAR(36)"], cols=[
        "contract_id : CHAR(36)", "milestone_id : CHAR(36)", "inspector_id : CHAR(36)",
        "assigned_at : TIMESTAMP(6)", "ended_at : TIMESTAMP(6)", "assigned_by_service : VARCHAR(64)",
        "initiated_by_actor_id : CHAR(36)", "active_slot : TINYINT (generated)",
        "report_due_business_days : INT", "report_due_at : TIMESTAMP(6)"]),
    "level1_5_assignment_lock": dict(pk=["contract_id : CHAR(36)", "milestone_id : CHAR(36)"], cols=[]),
    "inspection_report": dict(pk=["report_id : CHAR(36)"],
        fk=["l15_commission_id : CHAR(36) (FK, UQ)"], cols=[
        "tier : VARCHAR(10)", "contract_id : CHAR(36)", "milestone_id : CHAR(36)", "content : JSON",
        "normalized_result : JSON", "result_hash : VARCHAR(64)", "report_hash : VARCHAR(64)",
        "inspector_id : CHAR(36)", "external_org : VARCHAR(255)", "status : VARCHAR(20)"]),
    "inspector_signature": dict(pk=["signature_id : CHAR(36)"], fk=["report_id : CHAR(36) (FK, UQ)"],
        cols=["contract_id : CHAR(36)", "signer_user_id : CHAR(36)", "auth_time : TIMESTAMP(6)",
              "signed_at : TIMESTAMP(6)", "report_hash : VARCHAR(64)"]),
    "level2_inspection_commission": dict(planned=True, pk=["commission_id : CHAR(36)"], cols=[
        "contract_id : CHAR(36)", "org : VARCHAR(255)", "intake_case_id : VARCHAR(255)", "status : VARCHAR(20)",
        "requested_at : TIMESTAMP"]),
    # ---- audit_db ----
    "audit_record": dict(pk=["record_id : CHAR(36)"], cols=[
        "subject_type : VARCHAR(20)", "subject_id : VARCHAR(100)", "source_event_type : VARCHAR(100)",
        "source_event_id : CHAR(36) (UQ)", "source_hash : CHAR(64)", "content : JSON",
        "record_hash : CHAR(64)", "prev_hash_global : CHAR(64)", "prev_hash_subject : CHAR(64)"]),
    "audit_anchor": dict(planned=True, pk=["anchor_id : CHAR(36)"], fk=["record_id : CHAR(36) (FK)"], cols=[
        "anchored_hash : CHAR(64)", "anchor_type : VARCHAR(20)", "proof : TEXT"]),
    "audit_chain_lock": dict(pk=["lock_id : TINYINT"], cols=["one row (CHECK lock_id = 1)"]),
}


def tab(d, name, cx, top):
    t = T[name]
    return d.entity(name, cx, top, t.get("title", name), t["pk"], t.get("fk", ()), cols=t.get("cols", ()),
                    planned=t.get("planned", False))


def fig04a():
    d = Diagram("fig04a_contract_signing_erd", 900, 860)
    tab(d, "activation_recovery", 160, 20)
    tab(d, "contract_document", 760, 20)
    tab(d, "activation_cancel_request", 135, 270)
    tab(d, "contracts", 450, 235)
    tab(d, "signature", 760, 255)
    tab(d, "signature_otp", 450, 545)
    tab(d, "consent_evidence", 760, 600)
    n = d.nodes
    d.er("contracts", "t", "activation_recovery", "r", (390, 93), s_off=-60, d="zero_one", label="has", side="l")
    d.er("contracts", "t", "contract_document", "l", (510, 100), s_off=60, label="renders", side="t", seg=1)
    d.er("contracts", "r", "signature", "l", label="is signed by", side="t")
    d.er("contracts", "l", "activation_cancel_request", "r", label="receives", side="t")
    d.er("contracts", "b", "signature_otp", "t", label="is challenged by", side="r")
    d.er("signature_otp", "r", "signature", "b", (690, 565), d="zero_one", s_off=565 - n["signature_otp"].cy,
         d_off=690 - 760, label="authorizes", side="t")
    d.er("consent_evidence", "l", "signature_otp", "r", s_off=700 - n["consent_evidence"].cy,
         d_off=700 - n["signature_otp"].cy, label="precedes", side="t")
    d.er("consent_evidence", "t", "signature", "b", s_off=60, d_off=60, label="backs", side="r")
    d.er("signature_otp", "l", "activation_cancel_request", "b", (135, 680), s_off=680 - n["signature_otp"].cy,
         d="zero_one", label="confirms", side="t")
    d.legend("legend", 5, 835, LEGEND)
    return d


def fig04b_1():
    d = Diagram("fig04b_1_milestone_delivery_erd", 900, 700)
    d.entity("contracts", 140, 20, "contracts", ghost=True)
    d.entity("consent_evidence", 470, 20, "consent_evidence", ghost=True)
    tab(d, "file_scan_outcome", 750, 20)
    tab(d, "milestone", 140, 90)
    tab(d, "seller_weigh_declaration", 470, 140)
    tab(d, "milestone_evidence_file", 750, 170)
    tab(d, "price_adjustment", 140, 470)
    tab(d, "milestone_sync_outbox", 470, 470)
    n = d.nodes
    d.er("contracts", "b", "milestone", "t", d="many", label="has", side="r")
    d.er("milestone", "r", "seller_weigh_declaration", "l", label="is declared by", side="t")
    d.er("consent_evidence", "b", "seller_weigh_declaration", "t", label="backs", side="r")
    d.er("milestone", "r", "milestone_evidence_file", "b", (750, 362), s_off=362 - n["milestone"].cy,
         label="holds evidence", side="t")
    d.er("milestone", "b", "price_adjustment", "t", s_off=-40, d_off=-40, label="adjusts", side="r")
    d.er("milestone", "b", "milestone_sync_outbox", "t", (200, 430), (470, 430), s_off=60, label="settles into",
         side="t", seg=1)
    d.legend("legend", 5, 655, LEGEND + "\nfile_scan_outcome is keyed by the file id (no foreign key); "
             "«earlier design»: superseded, never created")
    return d


def fig04b():
    d = Diagram("fig04b_milestone_dispute_erd", 1040, 1040, allow_crossings=True)
    L, C, R = 215, 540, 880
    d.entity("signature_otp", 330, 50, "signature_otp", ghost=True)
    d.entity("contracts", C, 50, "contracts", ghost=True)
    tab(d, "dispute_exit_otp", L, 135)
    tab(d, "milestone_short", C, 135)
    tab(d, "breach_case", R, 135)
    tab(d, "dispute_settlement_proposal", L, 320)
    tab(d, "level1_5_request", C, 320)
    tab(d, "remedy_decision", R, 400)
    tab(d, "level1_5_assignment", L, 530)
    tab(d, "inspection_report_c", C, 520)
    tab(d, "remedy_leg", R, 660)
    tab(d, "quality_assessment", C, 790)
    n = d.nodes
    m = n["milestone_short"]
    rd = n["remedy_decision"]
    ir = n["inspection_report_c"]
    d.er("contracts", "b", "milestone_short", "t", d="many", label="has", side="r")
    d.er("signature_otp", "b", "dispute_exit_otp", "t", d_off=330 - L, d="zero_one", label="confirms", side="r")
    d.er("contracts", "r", "breach_case", "t", (R, n["contracts"].cy), label="concerns", side="t")
    d.er("breach_case", "b", "remedy_decision", "t", label="is decided by", side="r")
    d.er("remedy_decision", "b", "remedy_leg", "t", d="many", label="contains", side="r")
    d.er("milestone_short", "r", "remedy_decision", "l", (715, m.cy + 37), (715, 540), s_off=37,
         d_off=540 - rd.cy, label="is settled by", side="r", seg=1)
    d.er("milestone_short", "l", "dispute_exit_otp", "r", label="is exited by", side="t")
    d.er("milestone_short", "b", "dispute_settlement_proposal", "t", (C - 70, 290), (L + 20, 290), s_off=-70,
         d_off=20, label="has proposals", side="t", seg=1)
    d.er("milestone_short", "b", "level1_5_request", "t", label="requests", side="r")
    d.er("milestone_short", "l", "level1_5_assignment", "r", (398, m.cy + 47), (398, 588), s_off=47,
         d_off=588 - n["level1_5_assignment"].cy, label="is assigned", side="t", seg=2)
    d.er("milestone_short", "r", "inspection_report_c", "r", (700, m.cy + 50), (700, 600), s_off=50,
         d_off=600 - ir.cy, label="has report", side="b", seg=0)
    d.er("inspection_report_c", "b", "quality_assessment", "t", d="zero_one", label="is assessed by", side="r")
    d.legend("legend", 5, 985, LEGEND + "\nGrey box: a table drawn in full in another figure. contract_id foreign keys of "
             "remedy_decision, dispute_settlement_proposal and inspection_report\nare listed in the tables; "
             "their lines to contracts are left out because these tables already hang off milestone.")
    return d


def fig05a():
    d = Diagram("fig05a_money_stores_erd", 830, 1130)
    h = lambda name: d.nodes[name].box()[3]  # bottom of a drawn table
    d.package("escrow_db", 5, 30, 820, 450, "escrow_db")
    tab(d, "deposit_leg", 140, 45)
    tab(d, "escrow_milestone", 415, 45)
    tab(d, "escrow_remedy_decision", 690, 45)
    tab(d, "contract_clearance_check", 140, 300)
    tab(d, "settlement_leg", 415, 290)
    tab(d, "escrow_remedy_leg", 690, 240)
    d.er("escrow_milestone", "b", "settlement_leg", "t", label="settles by", side="r")
    d.er("escrow_remedy_decision", "b", "escrow_remedy_leg", "t", label="contains", side="r")
    d.package("bank_db", 5, 510, 820, 250, "bank_db")
    tab(d, "ledger_entry", 140, 525)
    tab(d, "system_lock", 415, 525)
    tab(d, "used_nonce", 690, 525)
    d.package("reputation_db", 5, 790, 820, 310, "reputation_db")
    tab(d, "lock_entry", 140, 805)
    tab(d, "completed_contract_fact", 415, 805)
    tab(d, "governance_action_request", 690, 805)
    tab(d, "lock_override_event", 415, 945)
    tab(d, "dispute_outcome_fact", 690, 945)
    d.er("lock_entry", "b", "lock_override_event", "l", (140, h("lock_entry") + 30), label="is overridden by",
         side="t", seg=1)
    d.legend("legend", 5, 1110, LEGEND)
    return d


def fig05b():
    d = Diagram("fig05b_evidence_stores_erd", 800, 605)
    d.package("inspection_db", 5, 30, 520, 540, "inspection_db")
    tab(d, "level1_5_inspection_assignment", 125, 45)
    tab(d, "level2_inspection_commission", 400, 45)
    tab(d, "level1_5_assignment_lock", 400, 215)
    tab(d, "inspection_report", 130, 320)
    tab(d, "inspector_signature", 410, 320)
    d.package("audit_db", 545, 30, 250, 540, "audit_db")
    tab(d, "audit_record", 670, 45)
    tab(d, "audit_anchor", 670, 320)
    tab(d, "audit_chain_lock", 670, 480)
    d.er("level1_5_inspection_assignment", "b", "inspection_report", "t", d="zero_one", label="authorizes")
    d.er("inspection_report", "r", "inspector_signature", "l", d="zero_one", label="signed by", side="t")
    d.er("audit_record", "b", "audit_anchor", "t", label="is anchored by")
    d.legend("legend", 5, 585, LEGEND)
    return d


if __name__ == "__main__" and sys.argv[1:] != ["measure"]:
    failed = []
    for build in (fig04a, fig04b_1, fig04b, fig05a, fig05b):
        try:
            build().write(OUT)
            print("ok", build.__name__)
        except SystemExit as e:
            failed.append(str(e))
    if failed:
        print("\n".join(failed))
        sys.exit(1)

if __name__ == "__main__" and sys.argv[1:] == ["measure"]:
    d = Diagram("m", 800, 800)
    for k in T:
        n = tab(d, k, 0, 0)
        print(f"{k:32s} w={n.w:5.0f} h={n.h:4.0f}")
