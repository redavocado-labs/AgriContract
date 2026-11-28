"""Report 3: the dispute exits UC56-UC58 get their Part II specifications (3.8-3.10, after UC37) and a Part III screen
6.3.3 Dispute Exit with its wireframe (screen 41; screens 41-64 of the I.5.1 list become 42-65). Content follows
GB-55..GB-62, milestone-escrow design §3.4 and the running frontend panel. The 6.3.1 bullets rewritten on 28/11 get
their bold label back. New headings, tables and bullets are copies of their neighbours, so styles stay.

    python srs_dispute_exit_2811.py
"""
import copy
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import append_row, replace_image, set_cell_text, set_par_text, text_of  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "Report-3_Software_Requirement_Specification.docx"
PNG = ROOT / "figures/srs/screens/s41_dispute_exit.png"
d = docx.Document(DOC)
body = d.element.body


def top_par(text: str):
    hits = [p for p in body.iterchildren() if p.tag == qn("w:p") and text_of(p).strip() == text]
    assert len(hits) == 1, (text, len(hits))
    return hits[0]


def next_tbl(el):
    el = el.getnext()
    while el.tag != qn("w:tbl"):
        el = el.getnext()
    return el


def set_bullet(p, label: str, rest: str):
    """Bold label run + plain run, formatted like the paragraph's own first two runs."""
    runs = p.findall(qn("w:r"))
    bold, plain = copy.deepcopy(runs[0]), copy.deepcopy(runs[1] if len(runs) > 1 else runs[0])
    for r in runs:
        p.remove(r)
    for h in p.findall(qn("w:hyperlink")):
        p.remove(h)
    for r, t in ((bold, label), (plain, rest)):
        for child in list(r):
            if child.tag != qn("w:rPr"):
                r.remove(child)
        if r is plain and r.find(qn("w:rPr")) is not None:
            for b in r.find(qn("w:rPr")).findall(qn("w:b")) + r.find(qn("w:rPr")).findall(qn("w:bCs")):
                r.find(qn("w:rPr")).remove(b)
        w = r.makeelement(qn("w:t"), {})
        w.text = t
        w.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
        r.append(w)
        p.append(r)


# ---- 6.3.1 bullets: bold label back (a template bullet with a bold label gives the run formatting)
tpl_bullet = next(p for p in body.iterchildren() if p.tag == qn("w:p")
                  and text_of(p).startswith("Upload evidence: attach proof of the event"))
for start, label, rest in (
        ("Concede: accept the Buyer's figures", "Concede",
         ": accept the Buyer's figures with consent, a recent login and an email OTP (MSG73); the milestone is "
         "settled on the Buyer's figures. (UC36, UC56, GB-55)"),
        ("See the response deadline: without a response", "See the response deadline",
         ": without a response within 2 business days, a QUANTITY flag is settled on the Buyer's received weight "
         "without penalty, and a QUALITY flag is contested automatically and sent to a Level 1.5 inspection; after "
         "the deadline the screen refuses a response (MSG75). (UC36, GB-31)")):
    p = next(p for p in body.iterchildren() if p.tag == qn("w:p") and text_of(p).startswith(start))
    new = copy.deepcopy(tpl_bullet)
    p.addprevious(new)
    body.remove(p)
    set_bullet(new, label, rest)

# ---- Part II: UC56-UC58 after UC37
h37 = top_par("3.7 Submit Force Majeure Claim (UC37)")
t37 = next_tbl(h37)
flow_cell = t37.findall(qn("w:tr"))[3].findall(qn("w:tc"))[1]
TITLE_P = copy.deepcopy(flow_cell.findall(qn("w:p"))[0])   # bold sub-heading
STEP_P = copy.deepcopy(flow_cell.findall(qn("w:p"))[1])    # plain line


def fill(tc, lines):
    """lines: ('t', text) bold title or ('s', text) plain line."""
    for p in tc.findall(qn("w:p")):
        tc.remove(p)
    for kind, text in lines:
        p = copy.deepcopy(TITLE_P if kind == "t" else STEP_P)
        tc.append(p)
        set_par_text(p, text)


UCS = [
    ("3.8 Concede Milestone Dispute (UC56)", "Buyer, Seller", "Counterparty, Inspector (Level 1.5)",
     "As a buyer or seller, I want to accept the other party's position on a disputed milestone so that the dispute "
     "ends without waiting for an inspection result.",
     "The user is the authenticated Buyer or Seller of an ACTIVE contract. The milestone is AWAITING_SELLER_RESPONSE, "
     "or CONTESTED with no COMPLETE report for the current inspection round and no final decision. The dispute does "
     "not come from a mandatory inspection.",
     [("t", "Concede Milestone Dispute"),
      ("s", "1. User opens the disputed milestone."),
      ("s", "2. System shows the amount the Seller receives under the Buyer's position and under the Seller's "
            "position, and the Buyer refund for each, as calculated by the System."),
      ("s", "3. User selects Concede. For the Buyer the action reads Withdraw Flag."),
      ("s", "4. System shows the CONCEDE_DISPUTE consent statement with the amounts and the consequences."),
      ("s", "5. User explicitly accepts the statement and requests an OTP after a recent login."),
      ("s", "6. User enters the OTP sent by email."),
      ("s", "7. System validates ownership, milestone state, the exit window, the consent and the OTP."),
      ("s", "8. System ends the dispute on the accepted position: the Seller receives the amount of that position and "
            "the rest of the locked payment is refunded to the Buyer (GB-57)."),
      ("s", "9. System cancels the pending Level 1.5 inspection, if any, and records who bears the inspection fee "
            "(GB-61)."),
      ("s", "10. System notifies both parties (MSG125).")],
     [("t", "Seller Concedes a QUANTITY Flag"),
      ("s", "The Seller is paid for the Buyer's received weight. No party is at fault and no penalty applies."),
      ("t", "Seller Concedes a QUALITY Flag or a Contested Decision"),
      ("s", "The batch counts as NON_CONFORMING with the Seller at fault: the Seller receives nothing, and the "
            "applicable penalty and reputation lock apply."),
      ("t", "Buyer Withdraws the Flag"),
      ("s", "The Seller is paid at the Seller's position. No party is at fault; the flag counts as an unsuccessful "
            "flag."),
      ("t", "Exit Not Allowed"),
      ("s", "When a COMPLETE inspection report exists, the decision is final or the case comes from a mandatory "
            "inspection, System rejects the concession (MSG118)."),
      ("t", "Consent or OTP Not Valid"),
      ("s", "System rejects the request without changing the milestone; the user reloads the statement or requests a "
            "new OTP.")],
     "The dispute is final, with no contest window (GB-62). The locked payment is divided according to the accepted "
     "position; a penalty or reputation lock applies only when the accepted position carries fault."),
    ("3.9 Propose or Accept Dispute Settlement (UC57)", "Buyer, Seller", "Counterparty, Inspector (Level 1.5)",
     "As a buyer or seller, I want to agree with the other party on the amount the Seller receives for a disputed "
     "milestone so that the dispute ends with no party at fault.",
     "The user is the authenticated Buyer or Seller of an ACTIVE contract. The milestone is CONTESTED, with no "
     "COMPLETE report for the current inspection round and no final decision, and the dispute does not come from a "
     "mandatory inspection. At most one proposal is open for the milestone.",
     [("t", "Propose Settlement"),
      ("s", "1. User opens the contested milestone and selects Propose Settlement."),
      ("s", "2. System shows the amounts the Seller receives under the Buyer's and the Seller's positions; a "
            "proposal must lie between them."),
      ("s", "3. User enters the amount the Seller receives, in whole VND."),
      ("s", "4. User explicitly accepts the SETTLE_DISPUTE consent statement and requests an OTP after a recent "
            "login."),
      ("s", "5. User enters the OTP sent by email."),
      ("s", "6. System validates ownership, milestone state, the amount range, the consent and the OTP."),
      ("s", "7. System records the open proposal (MSG124) and notifies the other party. The inspection continues."),
      ("t", "Accept Settlement"),
      ("s", "8. The other party opens the milestone and reviews the proposal: the amount the Seller receives and the "
            "Buyer refund."),
      ("s", "9. The other party selects Accept Proposal, accepts the SETTLE_DISPUTE consent statement for the same "
            "amount and enters an OTP."),
      ("s", "10. System checks that the amount is exactly the proposed amount and that the proposal is still open."),
      ("s", "11. System ends the dispute with no party at fault: the Seller receives the agreed amount and the rest of "
            "the locked payment is refunded to the Buyer."),
      ("s", "12. System cancels the pending Level 1.5 inspection, records who bears the inspection fee (GB-61) and "
            "notifies both parties (MSG125).")],
     [("t", "Amount Outside the Range"),
      ("s", "System rejects an amount below the Buyer's position or above the Seller's position (MSG119)."),
      ("t", "Proposal Already Open or Changed"),
      ("s", "System rejects a second proposal, or an acceptance of a proposal that has changed (MSG121)."),
      ("t", "Proposal Lapses"),
      ("s", "When the dispute ends another way or the inspection report comes back COMPLETE, the open proposal lapses "
            "(GB-60)."),
      ("t", "Exit Not Allowed"),
      ("s", "While the Seller is still asked to respond, or once the decision is final, System rejects the proposal "
            "(MSG118).")],
     "When accepted, the dispute is final with no fault, no penalty, no reputation lock and no contest window "
     "(GB-62). Without acceptance, the dispute and the inspection continue."),
    ("3.10 Withdraw or Refuse Settlement Proposal (UC58)", "Buyer, Seller", "Counterparty",
     "As the party who made a settlement proposal I want to withdraw it, and as the other party I want to refuse it, "
     "so that the dispute continues through inspection.",
     "The user is the authenticated Buyer or Seller of the contract. The milestone has an open settlement proposal.",
     [("t", "Withdraw or Refuse Settlement Proposal"),
      ("s", "1. User opens the milestone and reviews the open proposal."),
      ("s", "2. The proposer selects Withdraw Proposal; the other party selects Refuse Proposal."),
      ("s", "3. System asks for confirmation (MSG122 to withdraw, MSG123 to refuse)."),
      ("s", "4. User confirms. No consent statement or OTP is needed."),
      ("s", "5. System closes the proposal and notifies the other party."),
      ("s", "6. The dispute and any pending inspection continue; either party may make a new proposal.")],
     [("t", "Proposal No Longer Open"),
      ("s", "If the proposal was accepted, withdrawn or has lapsed in the meantime, System rejects the action "
            "(MSG121).")],
     "No proposal is open for the milestone. The dispute, the locked payment and the inspection are unchanged."),
]
anchor = t37
for title, primary, secondary, desc, pre, flow, alts, post in UCS:
    h = copy.deepcopy(h37)
    anchor.addnext(h)
    set_par_text(h, title)
    t = copy.deepcopy(t37)
    h.addnext(t)
    rows = t.findall(qn("w:tr"))
    r0 = rows[0].findall(qn("w:tc"))
    set_cell_text(r0[1], primary)
    set_cell_text(r0[3], secondary)
    set_cell_text(rows[1].findall(qn("w:tc"))[1], desc)
    set_cell_text(rows[2].findall(qn("w:tc"))[1], pre)
    fill(rows[3].findall(qn("w:tc"))[1], flow)
    fill(rows[4].findall(qn("w:tc"))[1], alts)
    set_cell_text(rows[5].findall(qn("w:tc"))[1], post)
    anchor = t

# ---- Part III: 6.3.3 Dispute Exit after 6.3.2
h632 = top_par("6.3.2 Claim Force Majeure")
pic632, intro632 = h632.getnext(), h632.getnext().getnext()
bullet632 = intro632.getnext()
t632 = next_tbl(h632)
h = copy.deepcopy(h632)
t632.addnext(h)
set_par_text(h, "6.3.3 Dispute Exit")
pic = copy.deepcopy(pic632)
h.addnext(pic)
for docpr in pic.iter(qn("wp:docPr")):
    docpr.set("id", str(int(docpr.get("id")) + 2000))
replace_image(d, pic, PNG, full_width=True)
intro = copy.deepcopy(intro632)
pic.addnext(intro)
set_par_text(intro, "This screen allows the Buyer and Seller to:")
last = intro
for label, rest in (
        ("See both positions", ": the amount the Seller receives under the Buyer's position and under the Seller's "
                               "position, as calculated by the System. (UC56, UC57, GB-57)"),
        ("Concede", ": accept the other party's position with consent, a recent login and an email OTP; for the "
                    "Buyer the action reads Withdraw Flag. (UC56, GB-55)"),
        ("Propose a settlement", ": enter the amount the Seller receives, between the two positions, and confirm with "
                                 "consent and an email OTP (MSG124). (UC57, GB-59)"),
        ("Accept the proposal", ": confirm the other party's proposal for exactly the same amount with consent and an "
                                "email OTP; the dispute ends with no party at fault (MSG125). (UC57)"),
        ("Withdraw or refuse", ": the proposer withdraws the proposal and the other party refuses it; the dispute and "
                               "the inspection continue (MSG122, MSG123). (UC58, GB-60)")):
    b = copy.deepcopy(bullet632)
    last.addnext(b)
    set_bullet(b, label, rest)
    last = b
ft = copy.deepcopy(t632)
last.addnext(ft)
rows = ft.findall(qn("w:tr"))
data_tpl = rows[1]
for tr in rows[1:]:
    ft.remove(tr)
for name, desc in (
        ("Seller Amount at Buyer's Position", "Display; VND; calculated by the System; never more than the locked "
                                              "payment"),
        ("Seller Amount at Seller's Position", "Display; VND; calculated by the System; never more than the locked "
                                               "payment"),
        ("Open Proposal", "Display; amount the Seller receives, Buyer refund, proposing party and time; shown only "
                          "when a proposal is open"),
        ("Proposed Seller Amount (VND)", "Data type: whole VND; required when proposing; between the two positions, "
                                         "inclusive, otherwise MSG119"),
        ("Consent Statement", "Checkbox; required; the CONCEDE_DISPUTE or SETTLE_DISPUTE statement with the amounts"),
        ("OTP", "Data type: 6 digits; required; sent by email; valid for 5 minutes; a new code after 60 seconds"),
        ("Actions", "Buttons; Concede (Withdraw Flag for the Buyer), Propose Settlement or Accept Proposal, Withdraw "
                    "or Refuse Proposal; shown only while the exit window is open")):
    tr = copy.deepcopy(data_tpl)
    tcs = tr.findall(qn("w:tc"))
    set_cell_text(tcs[0], name)
    set_cell_text(tcs[1], desc)
    ft.append(tr)

# ---- I.5.1 screen list: new #41, later screens renumbered, #39 says concede
slist = next(t for t in body.iter(qn("w:tbl")) if "Propose Replacement Contract" in text_of(t)
             and "Force Majeure Trends" in text_of(t))
rows = slist.findall(qn("w:tr"))
fm = next(tr for tr in rows if text_of(tr.findall(qn("w:tc"))[1]).strip() == "Claim Force Majeure")
new = copy.deepcopy(fm)
fm.addnext(new)
c = new.findall(qn("w:tc"))
set_cell_text(c[1], "Dispute Exit")
set_cell_text(c[3], "Allows the Buyer or Seller to end a milestone dispute by conceding, or by proposing, accepting, "
                    "withdrawing or refusing a settlement amount")
n = 0
for tr in slist.findall(qn("w:tr")):
    tc0 = tr.findall(qn("w:tc"))[0]
    if text_of(tc0).strip().isdigit():
        n += 1
        set_cell_text(tc0, str(n))
assert n == 65, n
rf = next(tr for tr in slist.findall(qn("w:tr")) if text_of(tr.findall(qn("w:tc"))[1]).strip() == "Respond to Flag")
set_cell_text(rf.findall(qn("w:tc"))[3], "Allows the Seller to concede or contest the Buyer's flag before the response "
                                         "deadline")

append_row(d.tables[0]._tbl, [
    "28/11/2026", "A", "Group 6",
    "UC56-UC58 specified in Part II (3.8-3.10); Part III screen 6.3.3 Dispute Exit with its wireframe; screen list "
    "renumbered (65 screens)"])
d.save(DOC)
print("saved", DOC.name)
