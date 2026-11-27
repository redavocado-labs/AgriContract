# Report 5.5 (acceptance test scripts) on the school template: one UAT script per business scenario of SRS I.2
# (BF-01..BF-07), written in business language. Each script is executed by the Playwright system tests through the
# web client on a real stack (tests/st, Report 5.3): Result comes from their run, nothing typed.
#   Pass         every step of the script ran through the screens and passed
#   Partial      what ran passed, but some steps have no screen yet (done through the API) or did not run today
#   Fail         a test of the script failed
#   Not Executed the flow has no screen yet; Feedback names the API evidence of Report 5.2
# The Exploratory sheet keeps only its header: no exploratory session with business users has been held. Pure openpyxl.
#
#   python fill_55_uat.py results/st-ui/results-chromium.json ../../docs/reports/Report-5.2_Test_Report.xlsx \
#       "../../docs/reports/Report-5.5_Acceptance Test Scripts.xlsx"
import json
import sys
from copy import copy

import openpyxl

UI_RESULTS, IT_BOOK, BOOK = sys.argv[1:4]

SCRIPTS = [  # id, BF, title, actor, steps, outcome, priority, UI tests, steps without a screen, 5.2 sheet
    ("SC-01", "BF-01", "A buyer signs up and passes KYC", "Guest → Buyer, Operator",
     "1. The guest opens the sign-up page, chooses the Buyer role, enters an e-mail and a password.\n"
     "2. Fills in the organization profile (name, phone, address) and submits it.\n"
     "3. Enters the 6-digit code from the verification e-mail.\n"
     "4. Before approval, tries to make an offer.\n"
     "5. The Operator verifies the profile.\n"
     "6. The buyer reloads the account page and reads the result e-mail.",
     "The account is Verified and the buyer receives the result by e-mail; before approval no offer can be made and "
     "the screen says why.", "Critical",
     ["ST-BF01-010", "ST-BF01-020", "ST-BF01-030", "ST-USA-040", "ST-BF01-040", "ST-BF01-050"],
     "step 5, the Operator's verification, calls the API (no KYC review screen yet)", "BF01_OnboardingApi"),
    ("SC-02", "BF-01", "A seller's KYC is rejected", "Guest → Seller, Operator",
     "1. The guest signs up as a Seller, submits the organization profile and verifies the e-mail.\n"
     "2. The Operator rejects the profile with a reason.\n"
     "3. The seller reloads the account page and reads the e-mail.",
     "The screen says the profile was not accepted; the rejection e-mail gives the exact reason.", "High",
     ["ST-BF01-060", "ST-BF01-070"], "step 2, the Operator's rejection, calls the API (no KYC review screen yet)",
     "BF01_OnboardingApi"),
    ("SC-03", "BF-02", "A seller publishes a coffee listing, a guest views it on the marketplace",
     "Seller (verified), Guest",
     "1. The seller adds a plot and waits for the deforestation risk check.\n"
     "2. Creates a Robusta product and a listing of 5,000 kg at 96,000 VND/kg.\n"
     "3. A guest (not signed in) opens the marketplace and the listing.\n"
     "4. The guest clicks Make an offer.",
     "The listing is Open for sale; the guest sees the price, quantities and declared quality but no contact data of "
     "the seller; making an offer needs signing in.", "Critical",
     ["ST-BF02-010", "ST-BF02-020", "ST-BF02-030", "ST-BF02-040"],
     "the seller's steps 1-2 call the API (no seller screens yet)", "BF02_PlotListingApi"),
    ("SC-04", "BF-03", "Negotiate, sign and activate a contract", "Buyer, Seller",
     "1. The buyer opens a listing and offers 1,000 kg with a delivery date.\n"
     "2. Check that the listing reserves exactly the offered quantity.\n"
     "3. The seller revises the unit price.\n"
     "4. The buyer receives the OTP by e-mail and signs.\n"
     "5. The seller receives the OTP and signs.\n"
     "6. Both open the contract and wait for the deposit lock.",
     "The contract is Active only after both deposits are locked (signing ≠ activation); the listing reserves exactly "
     "the offered quantity.", "Critical",
     ["ST-BF03-010", "ST-BF03-020", "ST-BF03-030", "ST-BF03-040", "ST-BF03-050", "ST-BF03-060"], "",
     "BF03_SigningApi"),
    ("SC-05", "BF-03", "The buyer withdraws an offer before the second signature", "Buyer",
     "1. The buyer makes an offer on a listing.\n"
     "2. Before the seller signs, the buyer clicks Withdraw offer and confirms.\n"
     "3. Check the listing and the contract again.",
     "The contract is Withdrawn; the reserved quantity returns to the listing; no money is locked or charged.", "High",
     ["ST-BF03-110", "ST-BF03-120", "ST-BF03-130"], "", "BF03_SigningApi"),
    ("SC-06", "BF-04", "Clean delivery of a milestone and settlement", "Seller, Buyer",
     "1. The milestone is funded when the contract activates.\n"
     "2. The seller weighs the goods, uploads the weighing photo and records the weight.\n"
     "3. The buyer receives the goods, uploads a photo and records the received weight.\n"
     "4. The buyer confirms the goods conform.\n"
     "5. Both look at the milestone and contract status.",
     "The milestone is Settled; when every milestone is done the contract is Settled and nothing stays locked.",
     "Critical",
     ["ST-BF04-010", "ST-BF04-020", "ST-BF04-030", "ST-BF04-040", "ST-BF04-050"], "", "BF04_MilestoneApi"),
    ("SC-07", "BF-04", "Quality dispute and Level 1.5 inspection", "Buyer, Seller, Inspector, Operator",
     "1. The buyer reports the goods do not conform (moisture too high).\n"
     "2. The seller answers that they disagree.\n"
     "3. The Operator assigns an inspector.\n"
     "4. The inspector signs in again and submits the result.\n"
     "5. Both see the decision and wait for the 2-business-day contest window to pass.",
     "The milestone goes Disputed and then settles by the inspection result; compensation is paid only after the "
     "contest window.", "High", [], "no test through the screens for the dispute branch yet", "BF04_MilestoneApi"),
    ("SC-08", "BF-05", "Report a breach and get a ruling", "Buyer, Arbitrator",
     "1. The buyer reports that the seller breached the contract, with evidence.\n"
     "2. The Arbitrator opens the breach case and asks for an inspection if needed.\n"
     "3. The Arbitrator decides who is at fault.\n"
     "4. After the contest window, both see the penalty and the reputation score.",
     "Only an attribution decision triggers a money penalty and a reputation lock; an allegation not yet ruled on has "
     "no effect.", "High", [], "no breach report screen yet; the Arbitrator's queue opens (ST-ARB-010)",
     "BF05_BreachApi"),
    ("SC-09", "BF-05", "Ask for relief by force majeure", "Seller, Arbitrator",
     "1. The seller reports force majeure (a flood) for a milestone within 7 days.\n"
     "2. The Arbitrator reviews the evidence and accepts or rejects it.",
     "Accepted: the milestone is handled without penalty; rejected: the milestone goes on as before.", "Medium", [],
     "no force majeure screen yet", "BF05_BreachApi"),
    ("SC-10", "BF-06", "Mutual termination or contract replacement", "Buyer, Seller",
     "1. One party proposes a mutual termination (or a replacement contract).\n"
     "2. The other party accepts.\n"
     "3. Both look at the contract status and the money still locked.",
     "The contract is Terminated (or Superseded); every locked amount returns to its owner, nobody is penalized.",
     "Medium", [], "no controller or screen yet (#242, #243)", "BF06_TerminationApi"),
    ("SC-11", "BF-07", "Emergency lock of every money movement", "External Verifier, Buyer, Admin",
     "1. The External Verifier sends a signed lock request.\n"
     "2. The buyer tries to fund a milestone.\n"
     "3. The Admin tries to unlock.\n"
     "4. The External Verifier sends an unlock request.",
     "While locked every money movement is refused and the screen says why; the Admin cannot unlock; after the unlock "
     "transactions run normally again.", "High", [], "no emergency lock screen yet", "BF07_SecurityLockApi"),
]

UI = {}
report = json.load(open(UI_RESULTS, encoding="utf-8"))


def _walk(suite):
    for spec in suite.get("specs", []):
        for t in spec["tests"]:
            UI[spec["title"].split(" ")[0]] = t["results"][-1]["status"]
    for child in suite.get("suites", []):
        _walk(child)


for s in report["suites"]:
    _walk(s)


def it_round3(book):
    """Round 3 counts of each Bruno e2e sheet of Report 5.2"""
    wb = openpyxl.load_workbook(book, read_only=True, data_only=True)
    out = {}
    for ws in wb.worksheets:
        if not ws.title.startswith(("BF0", "SecurityApi")):
            continue
        counts = {}
        for row in ws.iter_rows(min_row=12, max_col=12, values_only=True):
            if isinstance(row[0], str) and row[0].startswith("ITC") and row[11]:
                counts[row[11]] = counts.get(row[11], 0) + 1
        out[ws.title] = ", ".join(f"{counts[k]} {k}" for k in ("Passed", "Failed", "Pending", "N/A") if counts.get(k))
    return out


IT = it_round3(IT_BOOK)
WEEKEND = ("28/11/2026 is a Saturday: no milestone funding date is valid (D63, D67), so the second signature → "
           "activation → delivery skip themselves and run again on a business day")


def result(tests, gap, it_sheet):
    it = f"API side of the same flow (Report 5.2, {it_sheet}, Round 3): {IT[it_sheet]}."
    if not tests:
        return "Not Executed", f"Cannot run through the screens yet: {gap}. {it}"
    got = {t: UI[t] for t in tests}
    passed = [t for t, s in got.items() if s == "passed"]
    failed = [t for t, s in got.items() if s in ("failed", "timedOut")]
    skipped = [t for t, s in got.items() if s == "skipped"]
    text = (f"Run automatically by Playwright through the screens on a real stack, 28/11/2026 (Chromium; Report 5.3): "
            f"{len(passed)}/{len(tests)} steps passed ({', '.join(passed) or 'none'}).")
    if failed:
        text += f" Fail: {', '.join(failed)}."
    if skipped:
        text += f" Not run: {', '.join(skipped)} ({WEEKEND})."
    if gap:
        text += f" Note: {gap}."
    verdict = "Fail" if failed else "Partial" if (skipped or gap) else "Pass"
    if not passed and not failed:
        verdict = "Not Executed"
    return verdict, text + (f" {it}" if verdict != "Pass" else "")


wb = openpyxl.load_workbook(BOOK)
uat = wb["UAT"]
style = [copy(uat.cell(2, c)._style) for c in range(1, 10)]
for row in uat.iter_rows(min_row=2, max_row=max(uat.max_row, 300)):
    for cell in row:
        cell.value = None
verdicts = {}
for i, (sid, bf, title, actor, steps, outcome, priority, tests, gap, it_sheet) in enumerate(SCRIPTS, start=2):
    verdict, text = result(tests, gap, it_sheet)
    verdicts[verdict] = verdicts.get(verdict, 0) + 1
    for c, v in enumerate([sid, bf, title, actor, steps, outcome, priority, verdict, text], 1):
        uat.cell(i, c, v)._style = copy(style[c - 1])
uat.column_dimensions["E"].width = 60
uat.column_dimensions["F"].width = 44
uat.column_dimensions["I"].width = 44
ex = wb["Exploratory"]
for row in ex.iter_rows(min_row=2, max_row=max(ex.max_row, 300)):
    for cell in row:
        cell.value = None
wb.save(BOOK)
print(f"{len(SCRIPTS)} UAT scripts", verdicts)
