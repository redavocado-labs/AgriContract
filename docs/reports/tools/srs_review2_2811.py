"""Report 3 after the second external review (28/11): scope note at the head of Part I; JOB-02, JOB-04, JOB-05 and
JOB-08 as the flows are (completion decided with the last settlement, each milestone funded on its own date, silence
on a QUALITY flag goes to inspection); 6.2.2 and GB-27 (a flag or a clean settlement opens no breach case, a flag
uses the recorded weights and photos); plots linked per listing; Account Type in 1.1.1; KYC detail without tax ID or
representative (not in the profile); remaining code names written as business text; 65 screens; Context, BF-01,
BF-02, BF-05, the screen flow and the corrected wireframes re-embedded. Content only, styles kept.

    python srs_review2_2811.py
"""
import copy
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import append_row, find, replace_image, set_cell_text, set_par_text, text_of  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
FIG = REPO / "docs/reports/figures/srs"
DOC = REPO / "docs/reports/Report-3_Software Requirement Specification.docx"
d = docx.Document(DOC)
body = lambda: list(d.element.body.iterchildren())  # noqa: E731


def heading(text):
    """The heading paragraph (not its table-of-contents line) whose text starts with text."""
    for el in body():
        ps = el.find(f"{qn('w:pPr')}/{qn('w:pStyle')}") if el.tag == qn("w:p") else None
        st = ps.get(qn("w:val")) if ps is not None else ""
        if st.lower().startswith("heading") and text_of(el).strip().startswith(text):
            return el
    raise KeyError(text)


def rep_par(p, old, new):
    t = text_of(p)
    assert old in t, (old, t[:120])
    set_par_text(p, t.replace(old, new))


def rep_cell(tc, old, new):
    t = text_of(tc).strip()
    assert old in t, (old, t[:120])
    set_cell_text(tc, t.replace(old, new))


def tbl_after(p):
    n = p.getnext()
    while n.tag != qn("w:tbl"):
        n = n.getnext()
    return n


def tbl_with(text):
    for t in d.element.body.iter(qn("w:tbl")):
        if text in text_of(t):
            return t
    raise KeyError(text)


def row(tbl, first, col=0):
    for tr in tbl.findall(qn("w:tr")):
        tcs = tr.findall(qn("w:tc"))
        if len(tcs) > col and text_of(tcs[col]).strip() == first:
            return tr, tcs
    raise KeyError(first)


def picture_after(p):
    n = p.getnext()
    while not (n.tag == qn("w:p") and list(n.iter(qn("a:blip")))):
        n = n.getnext()
    return n


# ---------------------------------------------------------------- Part I
part1 = heading("I. Overall Requirements")
intro = find(d, "AgriContract is a B2B platform for domestic forward trading")
note = copy.deepcopy(intro)
part1.addnext(note)
set_par_text(note,
             "Scope note. This document specifies the target design of AgriContract. The version delivered with "
             "this project builds the core flows: onboarding and KYC, plots and listings, contract signing and "
             "activation, milestone delivery and settlement, Level 1.5 inspection, dispute exit, breach review and "
             "remedies (BF-01 to BF-05 in part). Force majeure claims, automatic breach on missed deadlines, "
             "termination and contract replacement (BF-06), Level 2 external inspection, the emergency system lock "
             "(BF-07), milestone price adjustment, the contract PDF, external timestamp anchoring and reputation "
             "governance are specified here but not built in this version (Report 1, LI-15 to LI-23); their use "
             "cases, screens and jobs describe the target design.")

replace_image(d, picture_after(heading("1. Context Diagram")), FIG / "context.png")
for h, png in (("2.1 ", "bf01.png"), ("2.2 Plot & Listing Publication", "bf02.png"), ("2.5 ", "bf05.png")):
    replace_image(d, picture_after(heading(h)), FIG / png)

rep_par(find(d, "The conceptual data model of AgriContract is one entity"),
        " Full-size image: figures/srs/erd_conceptual.png.", "")

uc = tbl_with("Manage Products")
_, uc13 = row(uc, "UC13")
rep_cell(uc13[3], "crop year, self-declared certifications and linked plots. Coffee products must link at least one plot.",
         "crop year and self-declared certifications. Plots are linked to each listing, not to the product (UC14).")

screens = tbl_with("Plot Risk Review Queue")
_, s20 = row(screens, "20")
rep_cell(s20[3], "crop year, certifications and linked plots, and to propose", "crop year and certifications, and to propose")
_, s36 = row(screens, "36")
rep_cell(s36[3], "the Buyer funds the milestone here, and both parties start delivery actions",
         "each milestone is funded automatically on its own funding date, and both parties start delivery actions")

jobs = tbl_with("Milestone Completion Sync (JOB-02)")
_, j2 = row(jobs, "2")
set_cell_text(j2[1], "Contract Completion on Last Settlement (JOB-02)")
set_cell_text(j2[2], "In the settlement transaction")
set_cell_text(j2[3], "When the last milestone of a contract is SETTLED, decide the return of the deposits in the same "
                     "step and start normal contract completion (GB-58)")
_, j5 = row(jobs, "5")
rep_cell(j5[3], "Lock the next milestone's batch amount", "Lock each milestone's batch amount on its own funding date")
_, j8 = row(jobs, "8")
set_cell_text(j8[3], "When the Seller does not answer a Buyer flag in time, settle a QUANTITY flag on the Buyer's "
                     "received weight without penalty and send a QUALITY flag to a Level 1.5 inspection (GB-31)")

# ---------------------------------------------------------------- Part III screens
rep_par(find(d, "This part describes the functions of AgriContract screen by screen"), "(64 screens)", "(65 screens)")

reg = tbl_after(heading("1.1.1 Register Account"))
append_row(reg, ["Account Type", "Data type: single choice; required; allowed values BUYER, SELLER; only Buyers "
                                 "and Sellers register themselves, staff accounts are created by the Admin (GB-01)."])

removed = 0
for kyc in [t for t in d.element.body.iter(qn("w:tbl")) if "Tax Identification Number" in text_of(t)]:
    for name in ("Tax Identification Number", "Representative Name", "Representative Position"):
        try:
            tr, _ = row(kyc, name)
        except KeyError:
            continue
        kyc.remove(tr)
        removed += 1
assert removed == 4, removed

rep_par(find(d, "Track funding: the system requests the batch amount lock"),
        "when the milestone becomes the next one to fund;",
        "on the milestone's own funding request date, without waiting for earlier milestones;")

rep_par(find(d, "Confirm clean: accept the batch (MSG69)"),
        "A shortfall beyond the contract threshold opens a breach case instead of an automatic penalty.",
        "Clean settlement never opens a breach case; a shortfall beyond the contract threshold can be reported as "
        "a breach (UC40).")
set_par_text(find(d, "Flag an issue: report a short quantity or a quality problem"),
             "Flag an issue: choose QUANTITY or QUALITY and accept the flag statement; the weights and photos "
             "already recorded by both parties are the evidence. The payment stays locked, the milestone moves to "
             "AWAITING_SELLER_RESPONSE and the Seller is asked to respond (MSG70). A flag is an allegation only and "
             "opens no breach case. (UC35, GB-28)")
receipt = tbl_with("Claimed Figures / Description")
_, it = row(receipt, "Issue Type")
set_cell_text(it[1], "Data type: radio; required when flagging; allowed values QUANTITY, QUALITY")
tr, _ = row(receipt, "Claimed Figures / Description")
receipt.remove(tr)
_, ev = row(receipt, "Issue Evidence")
set_cell_text(ev[0], "Flag Evidence")
set_cell_text(ev[1], "Display; the weights and photos recorded by the Seller and the Buyer; no new file is uploaded "
                     "when flagging")

term = tbl_with("TERMINATION_FOR_BREACH, TERMINATION_FOR_FORCE_MAJEURE")
_, dec = row(term, "Decision")
rep_cell(dec[1], " (sent as attributionDecisionId)", "")
act = tbl_with("Buyer / Seller Deposit State")
_, cr = row(act, "Cancel Requests")
rep_cell(cr[1], "and oneSidedCancelAllowedFrom, the time from which", "and the time from which")

# ---------------------------------------------------------------- background jobs
rep_par(find(d, "Trigger: Scheduled — every 5 seconds, in each service that writes events"),
        "every 5 seconds, in each service that writes events", "every 5 seconds, wherever events are recorded")

j2h = heading("10.2 Milestone Completion Sync (JOB-02)")
set_par_text(j2h, "10.2 Contract Completion on Last Settlement (JOB-02)")
trig = j2h.getnext()
set_par_text(trig, "Trigger: In the same step that settles a milestone; not a scheduled job")
purpose = trig.getnext()
set_par_text(purpose, "Purpose: When the last milestone of a contract is SETTLED, decide the return of the deposits "
                      "at once and start normal contract completion (GB-58).")
steps = purpose.getnext()  # "Processing steps:"
s1 = steps.getnext()
s2, s3, s4 = s1.getnext(), s1.getnext().getnext(), s1.getnext().getnext().getnext()
set_par_text(s1, "1. When a milestone is settled, check whether every milestone of the contract is now SETTLED.")
set_par_text(s2, "2. If yes, create the no-fault completion decision that returns the contract deposits, together "
                 "with the settlement; it is created once per contract.")
set_par_text(s3, "3. The contract stays ACTIVE until JOB-03 confirms the money.")
assert text_of(s4).startswith("4. Mark the record processed")
s4.getparent().remove(s4)
ft = tbl_after(j2h)
trs = ft.findall(qn("w:tr"))
set_cell_text(trs[1].findall(qn("w:tc"))[0], "Error during the settlement")
set_cell_text(trs[1].findall(qn("w:tc"))[1], "Nothing is saved: neither the settlement nor the completion decision; "
                                              "the settlement can be retried")
set_cell_text(trs[2].findall(qn("w:tc"))[1], "No completion decision is created")

rep_par(find(d, "2. When every required leg is locked, set the contract to ACTIVE"),
        "ACTIVE and start funding the first milestone.",
        "ACTIVE; each milestone is then funded on its own funding request date (GB-25).")
rep_par(find(d, "Purpose: Lock the next milestone's batch amount"),
        "Lock the next milestone's batch amount", "Lock each milestone's batch amount on its own funding date")
rep_par(find(d, "Purpose: Treat Seller silence after a Buyer flag"),
        "Treat Seller silence after a Buyer flag as acceptance of the Buyer's figures (GB-31).",
        "When the Seller does not answer a Buyer flag in time, settle a QUANTITY flag on the Buyer's received "
        "weight without penalty and send a QUALITY flag to a Level 1.5 inspection (GB-31).")
rep_par(find(d, "2. Settle each milestone on the Buyer's figures and notify both parties."),
        "2. Settle each milestone on the Buyer's figures and notify both parties.",
        "2. For a QUANTITY flag, settle the milestone on the Buyer's received weight without penalty; for a "
        "QUALITY flag, mark the milestone CONTESTED and request a Level 1.5 inspection. Notify both parties.")
rep_par(find(d, "2. Save the price quotes in one database transaction."),
        "2. Save the price quotes in one database transaction.",
        "2. Save all price quotes of the run together, so that a run is saved completely or not at all.")
rep_par(find(d, "3. Update the price cache after the commit."), "3. Update the price cache after the commit.",
        "3. Then refresh the prices shown to users.")
rep_par(find(d, "Trigger: Scheduled — daily, run by each owning service for its own data"),
        "run by each owning service for its own data", "for each kind of stored data")

# ---------------------------------------------------------------- Part IV / V
perf = tbl_with("Money state synchronisation")
_, ms = row(perf, "Money state synchronisation")
rep_cell(ms[1], "Milestone completion is synchronised every 5 seconds, and settlement is reconciled every 5 minutes",
         "Contract completion is decided together with the last milestone settlement, and settlement is "
         "reconciled with the bank ledger every 5 minutes")

msgs = tbl_with("CONSENT_CONTENT_MISMATCH")
for code, ctx in (("MSG113", "The consent statement the user accepted no longer matches the current data."),
                  ("MSG114", "The consent statement was updated after the page was opened."),
                  ("MSG118", "A concession or settlement is requested at a stage where the dispute cannot be ended "
                             "this way."),
                  ("MSG121", "A settlement is proposed or accepted while another proposal is open or has "
                             "changed.")):
    _, tcs = row(msgs, code, 1)
    set_cell_text(tcs[3], ctx)

rules = tbl_with("Normal weight loss on the road")
_, gb27 = row(rules, "GB-27")
rep_cell(gb27[1], "A shortfall beyond the contract threshold is not settled here; it opens a breach case.",
         "Clean settlement never opens a breach case; a shortfall beyond the contract threshold can be reported as "
         "a breach.")

# ---------------------------------------------------------------- wireframes and screen flow
start = body().index(heading("III. Functional Requirements"))


def style(el):
    ps = el.find(f"{qn('w:pPr')}/{qn('w:pStyle')}") if el.tag == qn("w:p") else None
    return ps.get(qn("w:val")) if ps is not None else ""


groups, cur = [], None
for el in body()[start + 1:]:
    if el.tag == qn("w:p") and text_of(el).strip() and style(el) in ("Heading4", "heading 4", "Heading 4"):
        cur = (text_of(el).strip(), [])
        groups.append(cur)
        continue
    if el.tag == qn("w:p") and text_of(el).strip().startswith("10. Background Job Inventory"):
        break
    if cur is not None and el.tag == qn("w:p"):
        cur[1].append(el)
assert len(groups) == 65, len(groups)
CHANGED = [5, 7, 8, 9, 10, 11, 12, 13, 15, 16, 19, 21, 22, 23, 24, 25, 27, 29, 30, 31, 33, 36, 39, 40, 43, 44, 45, 46,
           47, 48, 50, 51, 52, 53, 56, 57, 58, 59, 62]
files = sorted((FIG / "screens").glob("s[0-9][0-9]_*.png"))
for n in CHANGED:
    title, pars = groups[n - 1]
    png = files[n - 1]
    pic = next(p for p in pars if list(p.iter(qn("a:blip"))))
    replace_image(d, pic, png, full_width=True)

flow = None
for el in body():
    if el.tag == qn("w:p") and "Screen Flow" in text_of(el) and style(el).lower().startswith("heading"):
        flow = el
        break
replace_image(d, picture_after(flow), FIG / "screenflow.png", full_width=True)

# ---------------------------------------------------------------- change log
log = d.tables[0]._tbl
for tr in log.findall(qn("w:tr"))[1:]:
    tcs = tr.findall(qn("w:tc"))
    if text_of(tcs[2]).strip() == "Group 6":
        set_cell_text(tcs[2], "SEP490-G114")
append_row(log, ["28/11/2026", "M", "Minh Cuong",
                 "Scope note in Part I (Report 1 LI-15 to LI-23); JOB-02 decided with the last settlement, JOB-04 and "
                 "JOB-05 fund each milestone on its own date, JOB-08 splits QUANTITY and QUALITY; 6.2.2 and GB-27: a "
                 "flag or a clean settlement opens no breach case; plots linked per listing (UC13, #20, BF-02); "
                 "Account Type in 1.1.1; KYC detail without tax ID and representative; code names in 7.3.1, 7.5.1, "
                 "jobs and MSG113-121 written as business text; Context, BF-01, BF-05 labels; Dispute Exit in the "
                 "screen flow; 17 wireframes aligned with their field tables; 65 screens."])

used = {b.get(qn("r:embed")) for b in d.element.body.iter(qn("a:blip"))}
for rid, rel in list(d.part.rels.items()):
    if rel.reltype.endswith("/image") and rid not in used:
        d.part.drop_rel(rid)
d.save(DOC)
print("saved", DOC.name)
