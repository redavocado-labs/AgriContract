"""Report 4 fixes from the external review of 28/11: decision and issue numbers (D37, D42, D94, F18, #662) replaced by
what they mean, the money and identifier conventions stated as the tables really are, audit_record and the
inspection report tables marked implemented (schema overview, 3.23, Figure 5b), D37 written as a known limitation,
Figures 4b-1, 5b and 8 swapped for the new exports. Pictures with the same pixel size are replaced in place.

    python sds_review_2811.py <dir with the old PNGs of Figures 5b and 8 named <figure>.old.png>
"""
import copy
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import append_row, set_cell_text, set_par_text, text_of  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "Report-4_SDS.docx"
FIG = ROOT / "figures" / "sds"
old_dir = Path(sys.argv[1])
d = docx.Document(DOC)
body = d.element.body


def sub(old: str, new: str):
    """Replaces old by new in the one paragraph that contains it; inside a single run when possible."""
    ps = [p for p in body.iter(qn("w:p")) if old in text_of(p)]
    if len(ps) != 1:
        raise ValueError(f"{old!r}: {len(ps)} paragraphs")
    for t in ps[0].iter(qn("w:t")):
        if t.text and old in t.text:
            t.text = t.text.replace(old, new)
            return
    set_par_text(ps[0], text_of(ps[0]).replace(old, new))


sub("money is DECIMAL(18,2) rounded HALF_UP only in contract-service;",
    "money is fixed-point DECIMAL with two decimals (DECIMAL(18,2) in most tables; some earlier tables use "
    "DECIMAL(19,2), DECIMAL(15,2) or DECIMAL(10,2), as each table states), rounded HALF_UP only in contract-service; "
    "identifiers are UUID strings stored as CHAR(36), VARCHAR(36) or VARCHAR(255), as each table states;")
sub("Figure 4a now shows activation_recovery and activation_cancel_request (D42).",
    "Figure 4a now shows activation_recovery and activation_cancel_request (cancellation after a failed deposit "
    "lock).")
sub("Final signed PDF generation remains planned (issue #235).", "Final signed PDF generation remains planned.")
sub("(b) inspection and audit (earlier schema snapshot; current Level 1.5 status below)", "(b) inspection and audit")
sub("Phase 2 sync of groups A-C (#662):", "Phase 2 sync of groups A-C:")
sub("Must equal total committed milestone quantity before signing (F18).",
    "Must equal total committed milestone quantity before signing.")
sub("attributionDecisionId equals remedyDecisionId (D94).", "attributionDecisionId equals remedyDecisionId.")
sub("D94 creates the final deposit-return remedy in the final milestone settlement transaction;",
    "The final deposit-return remedy is created in the final milestone settlement transaction instead;")
sub("when the parties cancel after a failed lock (D42), of its return.",
    "when the parties cancel after a failed lock, of its return.")
sub("Implementation note (D37): initiation currently checks the requesting signer only, while verification checks "
    "both parties. The target design also requires the counterparty eligibility check at initiation.",
    "Known limitation: initiation checks the eligibility of the requesting signer only; the counterparty is checked "
    "when the code is verified, so a contract never becomes SIGNED with an ineligible party. The design also checks "
    "the counterparty at initiation, which would spare a signer an OTP that cannot be used.")

for tc in body.iter(qn("w:tc")):
    if text_of(tc).strip() == "audit_record, audit_anchor (planned; audit-service not built yet)":
        set_cell_text(tc, "audit_record, audit_chain_lock, event_outbox, processed_event (implemented, V1–V2); "
                          "audit_anchor (planned)")
        break
else:
    raise KeyError("schema overview audit_db")
sub("audit_db · Tamper-evident evidence chain (append-only grants).",
    "audit_db · (audit_record implemented, V1–V2; audit_anchor planned) Tamper-evident evidence chain (append-only "
    "grants).")
t323 = next(t for t in body.iter(qn("w:tbl")) if "audit_anchor.anchor_id / record_id" in text_of(t))
rows = t323.findall(qn("w:tr"))
for tr in rows:
    tcs = tr.findall(qn("w:tc"))
    if text_of(tcs[0]).strip() == "causation_id / correlation_id":
        set_cell_text(tcs[1], "VARCHAR(64)")
    if text_of(tcs[0]).strip() == "record_hash / prev_hash_global / prev_hash_subject":
        extra = copy.deepcopy(tr)
        tr.addnext(extra)
        for tc, v in zip(extra.findall(qn("w:tc")),
                         ["global_seq / occurred_at / created_at", "BIGINT / TIMESTAMP(6) / TIMESTAMP(6)",
                          "AUTO_INCREMENT UQ / NOT NULL / NOT NULL", "Order of the global chain"]):
            set_cell_text(tc, v)

# Figure 4(b) is embedded as fig04b_1 scaled to 2500 px wide, so it is found by that size and scaled the same way
import io  # noqa: E402

from PIL import Image  # noqa: E402

fig4b = [r.target_part for r in d.part.rels.values() if "image" in r.reltype
         and r.target_part.partname.endswith("/image8.png")]
assert len(fig4b) == 1 and fig4b[0].blob[16:24] == (2500).to_bytes(4, "big") + (1944).to_bytes(4, "big")
im = Image.open(FIG / "fig04b_1_milestone_delivery_erd.png").convert("RGB").resize((2500, 1944), Image.LANCZOS)
buf = io.BytesIO()
im.save(buf, "PNG")
fig4b[0]._blob = buf.getvalue()

for name in ("fig05b_evidence_stores_erd", "fig08_seq_verify_sign"):
    old = (old_dir / f"{name}.old.png").read_bytes()
    parts = [r.target_part for r in d.part.rels.values() if "image" in r.reltype and r.target_part.blob == old]
    assert len(parts) == 1, (name, len(parts))
    parts[0]._blob = (FIG / f"{name}.png").read_bytes()

append_row(d.tables[0]._tbl, [
    "28/11/26", "M", "Minh Cuong",
    "Decision and issue numbers replaced by their meaning (text, Figures 4b-1 and 8); money and identifier "
    "conventions as the tables are; audit_record, inspection_report and inspector_signature marked implemented "
    "(schema overview, 3.23, Figure 5b); signing initiation note written as a known limitation. The verification "
    "matrix of 31/10 is no longer part of this report (kept in the design documents)."])
d.save(DOC)
print("saved", DOC.name)
