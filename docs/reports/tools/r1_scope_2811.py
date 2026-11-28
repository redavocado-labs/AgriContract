"""Report 1 brought in line with Reports 3-5 (external review 28/11): this release covers coffee only, FE-08 states the
separation of staff duties, FE-15 gives the emergency lock to the External Verifier, FAO dropped from the acronyms,
change log filled. Content only, styles kept.

    python r1_scope_2811.py
"""
import copy
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import append_row, find, set_cell_text, set_par_text, text_of  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-1_Project_Introduction_v1.docx"
d = docx.Document(DOC)


def row_starting(tbl, first_cell: str):
    for tr in tbl.findall(qn("w:tr")):
        tcs = tr.findall(qn("w:tc"))
        if text_of(tcs[0]).strip() == first_cell:
            return tr
    raise KeyError(first_cell)


def tbl_with(text: str):
    for t in d.element.body.iter(qn("w:tbl")):
        if text in text_of(t):
            return t
    raise KeyError(text)


set_par_text(find(d, "– Hanoi, Oct 2026 –"), "– Hanoi, Nov 2026 –")

acr = tbl_with("Food and Agriculture Organization")
set_cell_text(row_starting(acr, "HTX").findall(qn("w:tc"))[1], "Hợp tác xã (Agricultural cooperative)")
acr.remove(row_starting(acr, "FAO"))

set_par_text(find(d, "The purpose of this project is to develop AgriContract"),
             "The purpose of this project is to develop AgriContract, a B2B agricultural contract execution platform "
             "for transactions between agricultural cooperatives (HTX) or supplying organizations and purchasing "
             "companies. This version covers coffee only, so that its quality terms, inspection rules and origin "
             "requirements can be handled in full for one commodity.")

feats = tbl_with("FE-08")
fe08 = row_starting(feats, "FE-08").findall(qn("w:tc"))
set_cell_text(fe08[2],
              "Manages user registration, email verification, KYC and roles. Staff duties are separate and no role "
              "acts in place of another: the Operator handles operational work such as KYC review, listing moderation "
              "and payment retries; the Arbitrator decides breach responsibility, remedies and force majeure claims; "
              "the Admin manages accounts and roles and reads audit evidence and analytics, but takes no decision on "
              "contracts, funds or disputes.")
fe15 = row_starting(feats, "FE-15").findall(qn("w:tc"))
set_cell_text(fe15[2],
              "Allows the External Verifier, an organization outside the platform's administration, to freeze the "
              "system during a serious security incident, such as suspected tampering with payment or evidence "
              "records, and to lift the freeze afterwards. Each request carries the verifier's own digital signature, "
              "so no platform staff, the Admin included, can lock or unlock the system.")
set_cell_text(fe15[3], "GAP-02, GAP-03")

lims = tbl_with("LI-13")
append_row(lims, ["LI-14", "Commodities Other Than Coffee",
                  "Rice, rubber, cashew and other commodities are not supported in this version. Each commodity has "
                  "its own quality terms and inspection rules; this version models those of coffee only."])

log = d.tables[0]._tbl
trs = log.findall(qn("w:tr"))
header = trs[0].findall(qn("w:tc"))
for i, tc in enumerate(trs[1].findall(qn("w:tc"))):
    p = tc.find(qn("w:p"))
    if not p.findall(qn("w:r")):
        r = copy.deepcopy(header[i].find(qn("w:p")).find(qn("w:r")))
        rpr = r.find(qn("w:rPr"))
        if rpr is not None:
            for b in rpr.findall(qn("w:b")) + rpr.findall(qn("w:bCs")):
                rpr.remove(b)
        p.append(r)
    set_par_text(p, ["28/11/2026", "M", "Minh Cuong",
                     "Aligned with Reports 3-5: coffee only (purpose, LI-14), separation of Operator, Arbitrator and "
                     "Admin duties (FE-08), emergency lock held by the External Verifier (FE-15); removed FAO from "
                     "the acronyms."][i])

d.save(DOC)
print("saved", DOC.name)
