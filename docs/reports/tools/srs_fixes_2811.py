"""Report 3 fixes from the external review of 28/11: GB-01 (only Buyer and Seller self-register), permission matrix
row 1 columns, screen list #34 summary, weights > 0 with up to 3 decimals (6.2.1, 6.2.2), the 3.1 lead-in and the
onboarding documents field in business terms, internal decision and issue numbers dropped from the text and the
change log. Text inside a single run is replaced in that run, so mixed formatting (bold labels) stays.

    python srs_fixes_2811.py
"""
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import fill_change_log, set_cell_text, set_par_text, text_of  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-3_Software_Requirement_Specification.docx"
d = docx.Document(DOC)
body = d.element.body


def paras_with(sub: str):
    return [p for p in body.iter(qn("w:p")) if sub in text_of(p)]


def sub(old: str, new: str, count: int = 1):
    """Replaces old by new in the paragraphs that contain it; inside one run when possible."""
    ps = paras_with(old)
    if len(ps) != count:
        raise ValueError(f"{old!r}: {len(ps)} paragraphs, expected {count}")
    for p in ps:
        for t in p.iter(qn("w:t")):
            if t.text and old in t.text:
                t.text = t.text.replace(old, new)
                t.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
                break
        else:
            set_par_text(p, text_of(p).replace(old, new))


sub("Only Buyer, Seller and Inspector accounts can be self-registered. Operator, Arbitrator and Admin accounts",
    "Only Buyer and Seller accounts can be self-registered. Inspector, Operator, Arbitrator and Admin accounts")

matrix = next(t for t in body.iter(qn("w:tbl")) if "Restricted⁷ |No" in text_of(t))
row = matrix.findall(qn("w:tr"))[1].findall(qn("w:tc"))
set_cell_text(row[8], "Restricted⁷")
set_cell_text(row[9], "No")

for tc in body.iter(qn("w:tc")):
    if text_of(tc).startswith("Allows the Buyer or Seller to propose or confirm a new contract that replaces the current one"):
        set_cell_text(tc, "Allows the Buyer or Seller to propose or confirm a new contract that replaces the current one")
        break
else:
    raise KeyError("screen list #34")

sub("Data type: file selection; multiple files; currently supports local selection only.",
    "Data type: files; optional; JPG, PNG or PDF; max 10 MB each; scanned for malware before they are kept.")
sub("Select organization documents: select business documents from the local device.",
    "Upload organization documents: attach the business documents needed for KYC review.")
sub("Data type: file selection; supports multiple local files.",
    "Data type: files; JPG, PNG or PDF; max 10 MB each; scanned for malware before they are kept.")
sub("Decimal; required; > 0 and ≤ 1,000,000; up to 2 decimals; 0 or empty shows MSG68",
    "Decimal; required; > 0 and ≤ 1,000,000; up to 3 decimals; 0 or empty shows MSG68")
sub("Decimal; required; ≥ 0 and ≤ 1,000,000; up to 2 decimals",
    "Decimal; required; > 0 and ≤ 1,000,000; up to 3 decimals; 0 or empty shows MSG68")

sub("is one entity relationship diagram drawn from the business tables of every service in the code (the Flyway "
    "migrations on main). A table that two services both keep (inspection report, remedy decision, remedy leg) is "
    "drawn once, and the join tables listing_plot and milestone_evidence_file are drawn as many-to-many relationships",
    "is one entity relationship diagram of the core business entities and their relationships. An entity that two "
    "parts of the system both keep (inspection report, remedy decision, remedy leg) is drawn once, and the links "
    "between listings and plots and between milestones and evidence files are drawn as many-to-many relationships")

sub("the Operator cannot cancel the contract, the parties decide (D42).",
    "the Operator cannot cancel the contract, the parties decide.")
sub(" The separate shortfall penalty workflow is not initiated by the implemented clean settlement path (deferred, #240).",
    " A shortfall beyond the contract threshold is not settled here; it opens a breach case.")
sub("BF-03 follows D42/D43. Activation failure (D42): the parties",
    "BF-03 shows offer withdrawal and activation failure. Activation failure: the parties")
sub("4.3.6 UC29 is the Operator's lock retry (D42)", "4.3.6 UC29 is the Operator's lock retry")
sub("Phase 2 sync of groups A-C (#662): onboarding with e-mail OTP verification and unverified-account cleanup (D59), "
    "self-registration as Buyer or Seller only (D47),",
    "Phase 2 sync of groups A-C: onboarding with e-mail OTP verification and unverified-account cleanup, "
    "self-registration as Buyer or Seller only,")

d.save(DOC)
print("saved", DOC.name)
