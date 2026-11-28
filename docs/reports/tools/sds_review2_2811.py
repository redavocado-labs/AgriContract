"""Report 4 after the second external review (28/11): activation failure is a Buyer/Seller cancel request with OTP
(no Operator mark-failed), planned parts marked in I.1 and IV.5 as the code is, Nginx as the production edge only,
I.3 conventions as the tables are, audit_chain_lock in 3.23 and Figure 5b, clean settlement does not open a breach
case, change log with one group name and one date format. Content only, styles kept.

    python sds_review2_2811.py
"""
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import append_row, find, replace_image, set_cell_text, set_par_text, text_of  # noqa: E402

REPORTS = Path(__file__).resolve().parents[1]
DOC = REPORTS / "Report-4_SDS.docx"
d = docx.Document(DOC)


def replace_in_par(p, old: str, new: str):
    t = text_of(p)
    assert old in t, (old, t[:100])
    set_par_text(p, t.replace(old, new))


def replace_in_cell(tc, old: str, new: str):
    t = text_of(tc).strip()
    assert old in t, (old, t[:100])
    set_cell_text(tc, t.replace(old, new))


def tbl_with(text: str):
    for t in d.element.body.iter(qn("w:tbl")):
        if text in text_of(t):
            return t
    raise KeyError(text)


def row(tbl, first: str, col: int = 0):
    for tr in tbl.findall(qn("w:tr")):
        tcs = tr.findall(qn("w:tc"))
        if len(tcs) > col and text_of(tcs[col]).strip() == first:
            return tcs
    raise KeyError(first)


# I.1 Software Architecture
replace_in_par(find(d, "AgriContract is a B2B forward-contract platform"),
               "an edge tier (Nginx, API Gateway, Keycloak)",
               "an edge tier (Nginx in production, API Gateway, Keycloak; the Docker Compose demo stack has no Nginx "
               "and the API Gateway is its edge)")
comp = tbl_with("TLS termination, coarse per-IP rate limit")
replace_in_cell(row(comp, "Nginx", 1)[2], "Service ports are never exposed.",
                "Service ports are never exposed. Production target only: the Docker Compose demo stack runs without "
                "Nginx, with Spring Cloud Gateway as its edge.")
replace_in_cell(row(comp, "bank-service", 1)[2],
                "lock/release/refund/seize processing, emergency security lock controlled by the External Verifier "
                "(ES256), large-transaction signals.",
                "lock/release/refund/seize processing. Planned: emergency security lock controlled by the External "
                "Verifier (ES256), large-transaction signals.")
replace_in_cell(row(comp, "audit-service", 1)[2],
                "periodic verification, OpenTimestamps anchoring, evidence export.",
                "periodic verification. Planned: OpenTimestamps anchoring, evidence export.")
replace_in_cell(row(comp, "External Verifier", 1)[2], "(no admin bypass).",
                "(no admin bypass). Planned: the emergency lock is not built in this version.")
replace_in_cell(row(comp, "OpenTimestamps", 1)[2], "Public timestamp anchoring of audit digests.",
                "Public timestamp anchoring of audit digests (planned).")
rate = row(tbl_with("Rate limiting"), "Rate limiting")
replace_in_cell(rate[1], "Nginx coarse per-IP;", "Nginx coarse per-IP (production);")

# I.3 Database Design, opening conventions
db = find(d, "Each service owns one MySQL 8 schema and one DB user")
replace_in_par(db, "quantities are kilograms (DECIMAL(15,3));",
               "quantities are kilograms with three decimals (DECIMAL(15,3) in most tables; some use DECIMAL(10,3), "
               "DECIMAL(13,3) or DECIMAL(12,4), as each table states);")
replace_in_par(db, "lock_entry, audit_record, audit_anchor)", "lock_entry, audit_record, and audit_anchor when built)")
replace_in_par(db, "Tables already created by Flyway in the repository are documented from the schema their migrations "
                   "produce (contract_db: contracts, signing, consent and activation recovery; bank, escrow, "
                   "reputation, inspection, user, product, pricing, notification); the other tables are the planned "
                   "Phase 2 design.",
               "Tables already created by Flyway in the repository are documented from the schema their migrations "
               "produce; the schema overview below marks, per database, which tables are implemented and which are "
               "the planned Phase 2 design.")

# 3.23 audit_db
replace_in_par(find(d, "audit_db · (audit_record implemented"),
               "(audit_record implemented, V1–V2; audit_anchor planned)",
               "(audit_record and audit_chain_lock implemented, V1–V2; audit_anchor planned)")
append_row(tbl_with("audit_anchor.anchored_hash"),
           ["audit_chain_lock.lock_id", "TINYINT", "PK, CHECK (lock_id = 1)",
            "One row, locked FOR UPDATE to serialise chained inserts, so the runtime account needs no UPDATE "
            "privilege on audit_record"])
cap = find(d, "Figure 5. Money, trust and evidence stores")
pic = cap.getprevious()
while not list(pic.iter(qn("a:blip"))):
    pic = pic.getprevious()
replace_image(d, pic, REPORTS / "figures/sds/fig05b_evidence_stores_erd.png")

# IV.2 State machines
sm = tbl_with("Contract state")
replace_in_cell(row(sm, "SIGNED")[2],
                "ACTIVATION_REFUND_PENDING when the Operator marks the activation failed",
                "ACTIVATION_REFUND_PENDING when both parties request cancellation with OTP, or one party has and 5 "
                "business days have passed since the first failed deposit lock (no Operator approval)")
arp = row(sm, "ACTIVATION_REFUND_PENDING")
set_cell_text(arp[1], "Cancel request by BUYER/SELLER (OTP) after a failed deposit lock, with locked deposit legs")

# IV.3 Milestone settlement
alg = row(tbl_with("Quality disposition"), "Milestone settlement")
replace_in_cell(alg[1], "(shortfall beyond threshold opens a BreachCase, never auto-breach)",
                "(clean settlement pays on the calculated quantity and never opens a BreachCase; a shortfall beyond "
                "the threshold is a breach only if a party reports it and the Arbitrator attributes it)")

# IV.5 API surface
api = tbl_with("Resource group")
act = row(api, "/contracts/{id}/activation/*")
set_cell_text(act[2], "retry-deposit-lock; cancel initiate / verify (OTP), withdraw cancel request")
set_cell_text(act[3], "retry OPERATOR; cancel BUYER, SELLER (no Operator path)")
rep = row(api, "/api/v1/reputation, /admin/reputation/actions")
replace_in_cell(rep[2], "public summary; propose / approve / reject", "public summary; propose / approve / reject (planned)")
for res, old in (("/api/v1/analytics/*", "terminations, escrow effectiveness, force-majeure trends"),
                 ("/api/v1/escrows/statements", "statement export")):
    replace_in_cell(row(api, res)[2], old, old + " (planned)")
replace_in_cell(row(api, "/api/v1/security/*")[2], "emergency-lock / unlock", "emergency-lock / unlock (planned)")

# Change log: one group name, one date format
log = d.tables[0]._tbl
for tr in log.findall(qn("w:tr"))[1:]:
    tcs = tr.findall(qn("w:tc"))
    if text_of(tcs[2]).strip() == "Group 6":
        set_cell_text(tcs[2], "SEP490-G114")
    date = text_of(tcs[0]).strip()
    if len(date) == 8 and date[2] == "/" and date[5] == "/":
        set_cell_text(tcs[0], date[:6] + "20" + date[6:])
append_row(log, ["28/11/2026", "M", "Minh Cuong",
                 "Activation failure handled by the parties' cancel request with OTP, no Operator mark-failed (IV.2, "
                 "IV.5); planned parts marked in I.1 and IV.5; Nginx is the production edge only; I.3 quantity "
                 "types; audit_chain_lock in 3.23 and Figure 5b; clean settlement never opens a breach case (IV.3); "
                 "change log group name and dates."])

d.save(DOC)
print("saved", DOC.name)
