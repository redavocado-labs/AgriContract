"""Report 1 V.2: the features specified in Report 3 but not built in this version become LI-15..LI-21. Content only.

    python r1_target_design_2811.py
"""
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import append_row, set_par_text, text_of  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-1_Project_Introduction_v1.docx"
d = docx.Document(DOC)

WHY = "Specified in Report 3 but not built in this version."
ROWS = [
    ("LI-15", "Force Majeure Claims", "Sellers cannot claim force majeure and the Arbitrator cannot review such claims. " + WHY),
    ("LI-16", "Automatic Breach on Missed Deadlines",
     "The system does not open a breach by itself when a party misses a funding or delivery deadline, and there is no "
     "cure window after a failed milestone funding. " + WHY),
    ("LI-17", "Termination for Breach, Mutual Termination and Contract Replacement",
     "An active contract cannot be ended early or replaced by a new contract. " + WHY),
    ("LI-18", "Level 2 External Inspection",
     "Disputed quality cannot be sent to an external inspection organization; quality disputes go to Level 1.5 "
     "platform inspectors only. " + WHY),
    ("LI-19", "Emergency System Lock",
     "The External Verifier cannot lock or unlock the system (FE-15). " + WHY),
    ("LI-20", "Milestone Price Adjustment",
     "Parties cannot adjust a milestone price within the signed price band; each milestone keeps the price agreed at "
     "signing. " + WHY),
    ("LI-21", "Contract PDF Document",
     "The system does not generate a PDF of the draft or signed contract; the signed terms are shown on screen. " + WHY),
]
lims = next(t for t in d.element.body.iter(qn("w:tbl")) if text_of(t).startswith("IDExcluded Item"))
for r in ROWS:
    append_row(lims, list(r))

log = d.tables[0]._tbl.findall(qn("w:tr"))
cells = log[1].findall(qn("w:tc"))
set_par_text(cells[3].find(qn("w:p")),
             "Aligned with Reports 3-5: coffee only (purpose, LI-14), separation of Operator, Arbitrator and Admin "
             "duties (FE-08), emergency lock held by the External Verifier (FE-15); features specified but not built "
             "in this version listed as LI-15 to LI-21; removed FAO from the acronyms.")
d.save(DOC)
print("saved", DOC.name)
