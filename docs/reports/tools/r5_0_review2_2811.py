"""Report 5.0 after the second external review (28/11): no test of the External Verifier (emergency lock not built,
Report 1 LI-19), deferred features named out of scope, the UAT run of 28/11 is a Playwright dry run and not acceptance,
out-of-scope UAT scripts are N/A, DEF-20 closed, legacy seed rows explained, '9 service Java' wording, change log
without blank rows. Content only, styles kept.

    python r5_0_review2_2811.py
"""
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import append_row, find, set_cell_text, set_par_text, text_of  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-5.0_Test Documentation.docx"
d = docx.Document(DOC)


def replace_in(p, old: str, new: str):
    t = text_of(p)
    assert old in t, (old, t[:80])
    set_par_text(p, t.replace(old, new))


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


DEFERRED = ("force majeure claims, automatic breach on missed deadlines, BF-06 Mutual Termination & Replacement, "
            "Level 2 external inspection, BF-07 Emergency Security Lock, milestone price adjustment, the contract "
            "PDF, external timestamp anchoring and reputation governance (specified in Report 3, not built in this "
            "version: Report 1, LI-15 to LI-23)")

# I.1 Scope
set_par_text(find(d, "BF-05 Breach & Force Majeure Resolution, BF-06"),
             "BF-05 Breach & Force Majeure Resolution at the depth it is implemented: breach case review, Level 1.5 "
             "inspection, attribution decisions and remedies.")
replace_in(find(d, "Out of scope: real bank and e-mail providers"),
           "penetration testing and high availability / failover.",
           f"penetration testing and high availability / failover; {DEFERRED}.")
replace_in(find(d, "Constraints and assumptions:"),
           "the external bank and the External Verifier are simulated by bank-service and a signing script;",
           "the external bank is simulated by bank-service;")

# I.2.1
replace_in(find(d, "Security testing. Objective"),
           "with a stale login for signing, and replayed External Verifier nonces.",
           "with a stale login for signing, and with a replayed or expired OTP.")
replace_in(find(d, "Acceptance testing. Objective"),
           "on 28/11/2026 the scripts were run by Playwright through the web client.",
           "on 28/11/2026 the team dry-ran the scripts through the web client with Playwright to prepare the "
           "session with the mentor; a dry run is not acceptance. Scripts of features not built in this version are "
           "N/A (Out of scope).")

# II.1.2 seed data
seed = tbl_with("duplicate-name check")
cat = row(seed, "Category")
set_cell_text(cat[3], text_of(cat[3]).strip() + ". Ngũ cốc, Gia vị and Trái cây are legacy Phase 1 rows: this "
              "version contracts coffee only, and they are kept to check that other goods cannot be contracted")
cof = row(seed, "Coffee listing for contract tests")
replace_in(cof[3].find(qn("w:p")), "is 500 kg of rice and cannot be reserved",
           "is 500 kg of rice, a legacy Phase 1 row, and cannot be reserved (fails closed)")

# II.2
replace_in(find(d, "Acceptance Test Scripts (AT):"),
           "on 28/11/2026 they were run by Playwright through the web client (1 Pass, 4 Partial, 6 Not Executed).",
           "on 28/11/2026 the team dry-ran them through the web client with Playwright (1 Pass, 4 Partial, 3 Not "
           "Executed, 3 N/A out of scope); acceptance by the mentor is planned for 30/11/2026.")

# II.3
rep = find(d, "Results as of 28/11/2026")
replace_in(rep, "AT counts the UAT scripts of Report-5.5 run by Playwright; a Partial or Not Executed script is "
                "counted as Pending.",
           "AT counts the Playwright dry run of the UAT scripts of Report-5.5, not yet the acceptance by the mentor; a "
           "Partial or Not Executed script is counted as Pending, an out-of-scope script as N/A.")
replace_in(rep, "5 defects are still open: DEF-03 (#472), DEF-15 (#663), DEF-19 (#713), DEF-20 (#714), DEF-23 (#717).",
           "4 defects are still open: DEF-03 (#472), DEF-15 (#663), DEF-19 (#713), DEF-23 (#717); DEF-20 (#714) was "
           "closed by PR #715 and re-tested.")

summary = tbl_with("Round #1")
r1 = row(summary, "Round #1")
assert text_of(r1[5]).strip() == "11" and text_of(r1[6]).strip() == "1245"
set_cell_text(r1[5], "8")
set_cell_text(r1[6], "1242")
set_cell_text(r1[7], text_of(r1[7]).strip().replace(
    "AT: Report-5.5 run by Playwright (28/11/2026): 1 Pass, 4 Partial, 6 Not Executed",
    "AT: Playwright dry run of Report-5.5 (28/11/2026): 1 Pass, 4 Partial, 3 Not Executed; 3 N/A out of scope "
    "(SC-09, SC-10, SC-11: Report 1 LI-15, LI-17, LI-19)"))

levels = tbl_with("Open Defects (Major)")
major = row(levels, "Open Defects (Major)")
assert text_of(major[4]).strip() == "1" and text_of(major[6]).strip() == "4"
set_cell_text(major[4], "0")
set_cell_text(major[6], "3")
set_cell_text(major[7], "DEF-03 IT (#472), DEF-15 IT (#663), DEF-19 IT (#713). DEF-20 ST closed (PR #715). "
                        "Minor open: DEF-23 ST (#717)")

wp = row(tbl_with("Software Package"), "Software Package")
set_cell_text(wp[7], text_of(wp[7]).strip().replace("9 service Java 1", "Java services (shared build) 1"))

# Change log
log = d.tables[0]._tbl
for tr in log.findall(qn("w:tr"))[1:]:
    if not text_of(tr).strip():
        log.remove(tr)
append_row(log, ["28/11/2026", "M", "Minh Cuong",
                 "Section I: deferred features out of scope (Report 1 LI-15 to LI-23), no External Verifier test, "
                 "the 28/11 UAT run is a Playwright dry run. II.1.2 legacy seed rows. II.3: DEF-20 closed, AT "
                 "scripts out of scope counted as N/A, Java services wording in II.3.3."])

d.save(DOC)
print("saved", DOC.name)
