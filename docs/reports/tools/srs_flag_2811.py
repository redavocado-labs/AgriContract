"""Report 3: 6.3.1 Respond to Flag tells QUANTITY from QUALITY silence (GB-31) and names the concession (UC56, OTP);
a line under the use case list says why UC55 is missing. Content only.

    python srs_flag_2811.py
"""
import copy
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import set_cell_text, set_par_text, text_of  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-3_Software_Requirement_Specification.docx"
d = docx.Document(DOC)
body = d.element.body


def one_par(start: str):
    hits = [p for p in body.iter(qn("w:p")) if text_of(p).strip().startswith(start)]
    assert len(hits) == 1, (start, len(hits))
    return hits[0]


set_par_text(one_par("Accept the flag: accept the Buyer's figures (MSG73)"),
             "Concede: accept the Buyer's figures with consent, a recent login and an email OTP (MSG73); the milestone "
             "is settled on the Buyer's figures. (UC36, UC56, GB-55)")
set_par_text(one_par("See the response deadline: silence for 2 business days"),
             "See the response deadline: without a response within 2 business days, a QUANTITY flag is settled on the "
             "Buyer's received weight without penalty, and a QUALITY flag is contested automatically and sent to a "
             "Level 1.5 inspection; after the deadline the screen refuses a response (MSG75). (UC36, GB-31)")
dec = [tc for tc in body.iter(qn("w:tc")) if text_of(tc).strip() == "Buttons; ACCEPT or CONTEST"]
assert len(dec) == 1
set_cell_text(dec[0], "Buttons; CONCEDE (consent and email OTP) or CONTEST")

uc_list = next(t for t in body.iter(qn("w:tbl")) if "Concede Milestone Dispute" in text_of(t)
               and "Withdraw or Refuse Settlement Proposal" in text_of(t))
body_text = one_par("The conceptual data model of AgriContract")
note = copy.deepcopy(body_text)
uc_list.addnext(note)
set_par_text(note, "UC55 is not used: the objection procedure it described was removed in v1.7, because a decision "
                   "is challenged through the contest window of GB-50.")
d.save(DOC)
print("saved", DOC.name)

# change log row for all 28/11 review fixes (srs_fixes, srs_rules, srs_jobs_apis, srs_bf_labels, this script)
from docxedit import append_row  # noqa: E402
d = docx.Document(DOC)
append_row(d.tables[0]._tbl, [
    "28/11/2026", "M", "Group 6",
    "Review fixes: only Buyer and Seller self-register (GB-01); permission matrix row 1; business rules GB-17 to GB-31 "
    "and GB-51 to GB-62, background jobs and external APIs written in business terms; weights > 0 with up to 3 "
    "decimals (6.2.1, 6.2.2); 6.3.1 tells QUANTITY from QUALITY and names the concession; UC55 gap explained; "
    "decision and issue numbers removed from the text and from BF-03 and BF-04"])
d.save(DOC)
print("change log row added")
