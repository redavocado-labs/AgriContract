"""Report 1 after the second external review (28/11): delivery status of each major feature, LI-11 in line with
LI-17/LI-20, audit anchoring and reputation governance listed as not built (LI-22, LI-23), lecturer phone number.
Content only, styles kept.

    python r1_review2_2811.py
"""
import copy
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import append_row, fill_change_log, set_cell_text, text_of  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-1_Project_Introduction_v1.docx"
d = docx.Document(DOC)


def tbl_with(text: str):
    for t in d.element.body.iter(qn("w:tbl")):
        if text in text_of(t):
            return t
    raise KeyError(text)


def row_starting(tbl, first_cell: str):
    for tr in tbl.findall(qn("w:tr")):
        if text_of(tr.findall(qn("w:tc"))[0]).strip() == first_cell:
            return tr
    raise KeyError(first_cell)


# lecturer phone
staff = tbl_with("tamnt122@fe.edu.vn")
set_cell_text(row_starting(staff, "Nguyen Thanh Tam").findall(qn("w:tc"))[3], "0337182784")

# V.1: Delivery Status column, taken from the width of Description
STATUS = {
    "FE-01": "Implemented",
    "FE-02": "Implemented",
    "FE-03": "Partial: milestone price adjustment not built (LI-20)",
    "FE-04": "Implemented",
    "FE-05": "Implemented",
    "FE-06": "Target design, not built (LI-17)",
    "FE-07": "Partial: no automatic breach on missed deadlines (LI-16), no force majeure claims (LI-15)",
    "FE-08": "Implemented",
    "FE-09": "Implemented",
    "FE-10": "Partial: Level 1.5 only, Level 2 not built (LI-18)",
    "FE-11": "Partial: no external timestamp anchoring (LI-22), no contract PDF (LI-21)",
    "FE-12": "Implemented",
    "FE-13": "Partial: no staff reputation actions or pair risk review (LI-23)",
    "FE-14": "Implemented",
    "FE-15": "Target design, not built (LI-19)",
}
feats = tbl_with("Contract Offer & Negotiation")
grid = feats.find(qn("w:tblGrid"))
for g in [grid] + list(grid.iter(qn("w:tblGrid")))[1:]:
    cols = g.findall(qn("w:gridCol"))
    cols[2].set(qn("w:w"), "3547")
    new = copy.deepcopy(cols[3])
    new.set(qn("w:w"), "1900")
    cols[3].addnext(new)
for k, tr in enumerate(feats.findall(qn("w:tr"))):
    tcs = tr.findall(qn("w:tc"))
    tc = copy.deepcopy(tcs[3])
    tcs[3].addnext(tc)
    set_cell_text(tc, "Delivery Status" if k == 0 else STATUS[text_of(tcs[0]).strip()])

# V.2
lims = tbl_with("Real Bank Payment Integration")
set_cell_text(row_starting(lims, "LI-11").findall(qn("w:tc"))[2],
              "Terms cannot be edited after the first valid signature. In the target design, bounded milestone price "
              "adjustments and contract replacement handle later changes; neither is built in this version "
              "(see LI-17 and LI-20).")
append_row(lims, ["LI-22", "External Timestamp Anchoring of Audit Evidence",
                  "Audit records are chained so that a changed or deleted record is detected, but the chain is not "
                  "anchored to an external public timestamp service. Specified in Report 3 but not built in this "
                  "version."])
append_row(lims, ["LI-23", "Reputation Governance and Pair Risk Review",
                  "Staff cannot propose or approve an early unlock or the clearing of an elevated risk on a party's "
                  "reputation, and repeated trading between the same two parties is not flagged for review. "
                  "Reputation locks follow final remedy decisions only. Specified in Report 3 but not built in this "
                  "version."])

fill_change_log(d.tables[0]._tbl, [
    "28/11/2026", "M", "Minh Cuong",
    "Delivery status column in the major features table; LI-11 points to LI-17 and LI-20; external timestamp "
    "anchoring (LI-22) and reputation governance (LI-23) listed as not built; lecturer phone number."])

d.save(DOC)
print("saved", DOC.name)
