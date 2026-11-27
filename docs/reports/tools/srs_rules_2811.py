"""Report 3 V.1: business rules GB-17..GB-31 and GB-51..GB-62 rewritten as plain business statements, the way GB-01..
GB-50 and the template samples read (no code names, enum values, hashes or rounding-mode names). Same rules, same
numbers. Column order: GBR-ID, Rule Statement, Applies To, Rationale, Enforced By; None keeps a cell.

    python srs_rules_2811.py
"""
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import set_cell_text, text_of  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-3_Software Requirement Specification.docx"
d = docx.Document(DOC)

RULES = {
    "GB-01": (None, None,
              "The system automatically on every registration; an Admin in the identity provider for Inspector, "
              "Operator, Arbitrator and Admin accounts"),
    "GB-17": (
        "The quality result of a batch is calculated from the signed quality terms and the inspector's measurements; "
        "no person chooses it. For each quality metric, the deviation from the committed value, in percentage "
        "points, is compared with the signed limits, strictest first: the reject limit, then the penalty limit, then "
        "the tolerance. A metric beyond its reject limit, or a coffee type that does not match the contract, makes "
        "the batch NON_CONFORMING; otherwise a metric in the penalty zone makes it PARTIALLY_CONFORMING; otherwise "
        "it is CONFORMING. The Seller is paid the value of the accepted quantity (never more than the locked "
        "payment) for CONFORMING, that value less the highest applicable discount for PARTIALLY_CONFORMING, and "
        "nothing for NON_CONFORMING; the rest of the locked payment goes back to the Buyer. An INCONCLUSIVE report "
        "cannot settle a batch. A quality discount is not a contractual penalty.",
        "Applying the signed limits the same way every time keeps the result objective: discounts are not stacked "
        "and no reviewer can override a measured result.",
        "The system calculates the result; the Arbitrator reviews it when resolving a dispute"),
    "GB-22": (
        "A contract reaches a final state only after every required payment and refund is completed and no money for "
        "it remains locked. For normal completion and for termination after a breach decision, the escrow must first "
        "confirm that the final decision has been fully paid out.",
        "A closed contract must not leave money locked or half paid.",
        "The system automatically before closing a contract; other exits wait for their own refunds"),
    "GB-25": (
        "Each milestone is funded on its own, on its funding request date: the expected delivery date minus the "
        "signed funding lead time in business days. Delivery of a batch starts only after its payment is locked. If "
        "funding is late, the delivery deadline moves by the delay plus the agreed grace days.",
        "Funding a batch does not wait for the previous batch to be settled, and the Seller is never late because "
        "the Buyer funded late.",
        "The system automatically on the funding request date and when the payment lock is confirmed"),
    "GB-27": (
        "When a batch is confirmed clean, the Seller is paid for the declared weight if the weight lost in transit "
        "is within the agreed tolerance. Loss above the tolerance is shared equally: the Seller is paid for the "
        "declared weight less half of the excess loss. The payment never exceeds the amount locked for the batch, "
        "and the rest goes back to the Buyer. A shortfall beyond the contract threshold is not settled here; it "
        "opens a breach case.",
        "Normal weight loss on the road is nobody's fault, so it is shared instead of being treated as a penalty.",
        "The system automatically on every clean settlement"),
    "GB-29": (
        "If the Buyer neither confirms nor flags a batch before the confirmation window ends, the batch is confirmed "
        "and settled automatically, unless a mandatory inspection is still pending.",
        "A silent Buyer cannot hold back the Seller's payment forever, but a required inspection is never skipped.",
        "The system automatically through a scheduled job"),
    "GB-30": (
        "When the Seller contests a delivery flag, the batch goes straight to a Level 1.5 inspection. A mandatory "
        "inspection must be completed before the batch is settled. Other inspections and force majeure claims follow "
        "the signed terms and the dispute rules.",
        "A disagreement about the delivered goods is settled by an independent measurement, not by either party's "
        "word.",
        "The system automatically on every contest and mandatory inspection; staff review for other cases"),
    "GB-31": (
        "If the Seller does not answer a QUANTITY flag before the deadline, the batch is settled on the Buyer's "
        "received weight without any penalty: the Seller is paid for the received weight, never more than the "
        "locked payment, and the rest goes back to the Buyer; the transit loss tolerance does not apply. A QUALITY "
        "flag the Seller does not answer is contested automatically and goes to a Level 1.5 inspection.",
        "Silence can settle a disagreement about weight, but it is not proof of a quality problem, which needs a "
        "measurement.",
        "The system automatically through a scheduled job when the response deadline passes"),
    "GB-51": (
        "Every amount the system calculates is rounded once, to the nearest whole VND with halves rounded up, and "
        "shown with two decimal places. A remaining amount, such as a refund, is the locked amount minus the amount "
        "paid and is not rounded again. No payment or refund of zero is sent to the bank.",
        "Rounding once and getting the remainder by subtraction makes the parts always add up to the locked amount.",
        "The system automatically on every amount calculation; escrow and the bank move the amounts they are given"),
    "GB-52": (None,
              "A zero or negative weight, or one beyond any realistic batch, can only be an input mistake.",
              "The system automatically on every weight entry"),
    "GB-53": (
        "The Seller may correct a declared weight within one hour of the first declaration (configurable) and only "
        "before the Buyer records receipt. Every earlier declaration is kept. The Buyer's received weight cannot be "
        "changed once recorded.",
        "An input mistake can be fixed quickly, but no weight can change after the other party has acted on it.",
        "The system automatically on every declaration and receipt"),
    "GB-54": (
        "Recording the weight, recording receipt, confirming a clean batch, flagging an issue and contesting a flag "
        "each need the user, logged in and acting on their own contract, to accept a consent statement for that "
        "action and its content. No OTP is needed. The system never records a consent on a user's behalf for its "
        "own automatic actions.",
        "Each delivery step stays traceable to the person who took it without asking for a code at every step.",
        "The system automatically on every delivery action"),
    "GB-55": (
        "Signing a contract, cancelling an activation, conceding a dispute and agreeing a dispute settlement each "
        "need a consent statement, a recent login and an email OTP issued for that action. Withdrawing an offer "
        "needs neither consent nor OTP.",
        "These actions commit money or end a dispute, so they need proof that the right person confirmed this exact "
        "action.",
        "The system automatically on every such action"),
    "GB-56": (
        "Before accepting an action, the system checks that the user consented to the current version of the "
        "consent statement and to exactly the content shown on screen. If either has changed, the action is refused "
        "and nothing changes. A consent covers only the action it was given for; a contract signature is bound to "
        "the signed terms separately.",
        "A consent given for one content can never be reused for another.",
        "The system automatically on every consent check"),
    "GB-57": (
        "In a delivery dispute each party has a position calculated by the system, never above the locked payment. "
        "For a new flag, the Seller's position is payment for the declared weight; the Buyer's position is payment "
        "for the received weight on a QUANTITY flag and nothing on a QUALITY flag. After a contested decision, the "
        "Buyer's position is what that decision would pay the Seller. A party may concede and accept the other "
        "side's position: a Seller concession on a QUANTITY flag carries no fault; a Seller concession on a QUALITY "
        "flag or a contested decision counts as a NON_CONFORMING batch with the Seller at fault and nothing paid to "
        "the Seller; a Buyer concession carries no fault. Alternatively the parties agree a settlement amount "
        "between the two positions, with no fault, and the rest of the locked payment goes back to the Buyer. "
        "While the Seller is asked to respond, only a concession is possible; a settlement needs the dispute to be "
        "contested first. A concession is one party's decision; a settlement needs both. Neither can skip a "
        "mandatory inspection or a case the system opened, and neither is possible once the current inspection has "
        "produced a complete report. Inspection fees are recorded against the responsible party, not paid through "
        "the bank: the conceding party pays, and a settlement reached while waiting for a re-inspection after an "
        "inconclusive report splits the fee.",
        "The parties can end a dispute early on figures both can see, without skipping required inspections or "
        "moving money that is not locked.",
        "The system automatically on every concession and settlement, checking the window, the amount and the OTP"),
    "GB-58": (
        "A locked amount is only ever divided, never exceeded. On normal completion, the return of the deposits is "
        "decided together with the settlement of the last milestone. The deposit returned is the original deposit "
        "minus any forfeiture already finalized, never below zero. The contract stays ACTIVE until the escrow "
        "confirms that every payment and refund of that final decision has completed and nothing remains locked.",
        "A contract cannot close too early or return a deposit twice.",
        "The system automatically on the last milestone settlement and on the escrow's confirmation"),
    "GB-59": (
        "A milestone has at most one open settlement proposal. Only the other party can accept it, and only for the "
        "exact amount proposed. The proposer can withdraw it and the other party can refuse it.",
        "A settlement must be agreed by both sides on one amount, without competing proposals.",
        "The system automatically on every proposal action"),
    "GB-60": (
        "An open settlement proposal does not stop the inspection. It lapses when the dispute ends another way or the "
        "inspection report comes back complete.",
        "Proposing a settlement the other party will not accept must not delay the inspection.",
        "The system automatically when the dispute or the inspection changes state"),
    "GB-61": (
        "Cancelling an inspection within 4 hours of its assignment (configurable) costs nothing. After that, the fee "
        "is borne by the party who conceded or, for an agreed settlement, by the party who asked for the inspection. "
        "If the current round already returned an INCONCLUSIVE report, the conceding party pays, or the parties "
        "split the fee for an agreed settlement. The fee is recorded only; no payment is sent to the bank.",
        "An inspector who has already started work is paid for it by the party who caused the cancellation.",
        "The system automatically when a dispute ends by concession or settlement"),
    "GB-62": (
        "A confirmed concession or accepted settlement is final, with no further contest window. The pending "
        "inspection is cancelled only as a result of that final outcome.",
        "A dispute the parties have ended themselves cannot be reopened, and an inspection is never cancelled on "
        "its own.",
        "The system automatically on every confirmed concession or accepted settlement"),
}

tbl = next(t for t in d.element.body.iter(qn("w:tbl")) if text_of(t).startswith("GBR-ID"))
done = set()
for tr in tbl.findall(qn("w:tr")):
    tcs = tr.findall(qn("w:tc"))
    gid = text_of(tcs[0]).strip()
    if gid in RULES:
        rule, why, by = RULES[gid]
        for i, v in ((1, rule), (3, why), (4, by)):
            if v is not None:
                set_cell_text(tcs[i], v)
        done.add(gid)
assert done == set(RULES), set(RULES) - done
d.save(DOC)
print("saved", DOC.name, len(done), "rules")
