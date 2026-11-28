"""Report 2 after the second external review (28/11): scope and out of scope follow Report 1 (coffee only, LI-14 to
LI-23), a note under the WBS on the baseline plan, the delivery phases and where the 27 pds of the deferred Iteration 3
packages went, objectives rows 1, 5, 6 and 10, II.1, II.2 and II.6 describe the process the team followed, org chart
without the tester role, change log without blank rows. Content only, styles kept.

    python r2_review2_2811.py
"""
import copy
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import append_row, find, replace_image, set_cell_text, set_par_text, text_of  # noqa: E402

REPORTS = Path(__file__).resolve().parents[1]
DOC = REPORTS / "Report-2_Project Management Plan_v1.docx"
d = docx.Document(DOC)

PHASES = "P1 27/10–06/11, P2 07/11–17/11 and P3 18/11–30/11/2026"

# I.1 Scope / Out of Scope
set_par_text(find(d, "AgriContract covers user eligibility"),
             "This version covers coffee only. AgriContract covers user eligibility and the separation of staff "
             "duties, coffee plots and listings with their origin information, contract offers, negotiation and "
             "electronic signing, deposits and milestone funding, delivery and Level 1.5 inspection, dispute exit "
             "and settlement, breach attribution and remedies, reputation, audit evidence, notifications, reference "
             "prices and file management. Force majeure claims, automatic breach on missed deadlines, termination "
             "and contract replacement, Level 2 external inspection, the emergency system lock, milestone price "
             "adjustment, the contract PDF, external timestamp anchoring and reputation governance are specified in "
             "Report 3 but not built in this version (Report 1, LI-14 to LI-23).")
out = find(d, "AgriContract does not manage physical logistics")
set_par_text(out, text_of(out).replace(
    "Signed contract terms cannot be directly modified after signing; milestone prices may only be adjusted within "
    "the price range already agreed in the signed contract, while changes outside that range require contract "
    "termination or replacement.",
    "Contract terms cannot be modified after the first signature; in the target design, later changes go through a "
    "bounded milestone price adjustment or a contract replacement, and neither is built in this version."))
assert "first signature" in text_of(out)

# I.3: note under the WBS
effort = find(d, "Effort is estimated in person-days")
wbs = effort.getnext()
while wbs.tag != qn("w:tbl"):
    wbs = wbs.getnext()
note = copy.deepcopy(effort)
wbs.addnext(note)
set_par_text(note,
             "The table is the baseline plan made at the start of the term. The Phase 2 implementation was delivered "
             f"in three phases: {PHASES}. The 27 pds planned for 4.3.2 Level 2 Inspection, 4.3.3 Termination, "
             "Replacement & Price Adjustment and 4.3.4 Emergency Security Lock were moved to completing the core "
             "flow (sign, lock deposits, deliver, settle), dispute exit (UC56–UC58), the audit-service and the "
             "system test redone through the web client; those three packages remain target design (Report 1, "
             "LI-17 to LI-20).")

# I.5 Project Objectives
obj = next(t for t in d.element.body.iter(qn("w:tbl")) if "Effort Usage" in text_of(t))
ROWS = {
    "Effort Usage": (None,
                     "Effective capacity budget; variance >10% requires replanning. No timesheets were kept; the "
                     "figure is estimated from the weekly plan and the work done, about 7% over budget. The 27 pds of "
                     "the deferred Iteration 3 packages went to the core flow, dispute exit, audit-service and the "
                     "system test through the web client (note under I.3)."),
    "System Test Defects": ("0 unresolved High (8 found, 2 High closed; open: DEF-23 Low)", None),
    "Acceptance Test Defects": ("Not run yet",
                                "Acceptance by the mentor on the demo build is planned for 30/11/2026. On 28/11/2026 "
                                "the team dry-ran the 11 scripts of Report-5.5 through the web client with Playwright "
                                "(1 Pass, 4 Partial, 3 Not Executed, 3 N/A out of scope); a dry run is not counted as "
                                "acceptance."),
    "Verification Gate Pass Rate": ("77% (28/11/2026)",
                                    "Critical end-to-end flows must pass before final acceptance. 23 of 30 system "
                                    "test cases through the web client passed on 28/11/2026; the 7 signing, activation "
                                    "and milestone cases need a Vietnam business day (DEF-19) and are re-run on "
                                    "30/11/2026."),
}
done = set()
for tr in obj.findall(qn("w:tr")):
    tcs = tr.findall(qn("w:tc"))
    name = text_of(tcs[1]).strip()
    if name in ROWS:
        actual, notes = ROWS[name]
        if actual:
            set_cell_text(tcs[4], actual)
        if notes:
            set_cell_text(tcs[5], notes)
        done.add(name)
assert done == set(ROWS), set(ROWS) - done

# II.1 Project Process
set_par_text(find(d, "The project applies an iterative and milestone-driven"),
             "The project followed an iterative, contract-first process. Before the Java and Node.js services were "
             "written in parallel, the REST interfaces (OpenAPI) and event schemas (JSON Schema) of each flow were "
             "reviewed and frozen in the contracts folder; a change to a frozen contract went through its own "
             "documentation pull request, merged before the code that used it. Each issue started with a short "
             "discussion of its business rules, edge cases and open choices, and the team leader decided the "
             f"business questions before coding. Work was delivered in three phases ({PHASES}): the core flow "
             "first (sign, lock deposits, deliver, settle), then disputes, Level 1.5 inspection and dispute exit, "
             "then system testing through the web client and the reports.")

# II.2 Quality Management
set_par_text(find(d, "Quality management is applied throughout"),
             "Quality is controlled at three points: the frozen contracts, the pull request and the test levels of "
             "Report 5.")
set_par_text(find(d, "Requirements and design changes are reviewed"),
             "Every change goes through a GitHub pull request linked to an issue and is reviewed before merge; by "
             "28/11/2026 reviews had raised 102 change requests on 66 of 362 pull requests, all resolved before "
             "merge. A pull request merges only when the CI gate on GitHub Actions passes: the unit and integration "
             "tests of the services it touches (JUnit 5, Testcontainers with real MySQL and RabbitMQ, Jest), "
             "contract validation and architecture rules. From 07/11 to 15/11/2026 GitHub Actions was unavailable "
             "because of an account billing lock; during that time the same CI commands were run locally on the "
             "pull request head before merge. Business flows are then checked end to end with Bruno CLI through the "
             "gateway and with Playwright through the web client (Report 5).")

# II.4 org chart: Nguyen Viet Hung is a Backend Developer, no dedicated tester
p = find(d, "RACI Chart:").getprevious()
while not list(p.iter(qn("a:blip"))):
    p = p.getprevious()
replace_image(d, p, REPORTS / "figures/plan/org_chart.png")

# II.6.1 Document Management
set_par_text(find(d, "Project documents are maintained with clear version"),
             "Reports are Word documents kept in the repository under docs/reports, so every version is in the Git "
             "history. Design documents and the frozen contracts (docs/design, contracts) are edited only by the "
             "team leader; a member who needs a change opens an issue, and the change is merged in a separate "
             "documentation pull request before the code that depends on it. Changes after a report is baselined "
             "are recorded in its Change Log.")
set_par_text(find(d, "Approved or frozen versions should be separated"),
             "Most figures are kept as editable sources next to the reports (Draw.io files, and the scripts that draw "
             "the wireframes and the screen flow), so a figure is changed at its source and exported again.")

# II.6.2 Source Code Management
set_par_text(find(d, "Source code is managed using Git and GitHub"),
             "Source code is managed with Git in one GitHub repository (backend services, web client, contracts, "
             "tests and reports). The main branch is protected: changes come only through reviewed pull requests "
             "that pass the CI gate and are up to date with main, and they are merged by rebase so the history stays "
             "linear.")
set_par_text(find(d, "Recommended workflow:"), "Workflow:")
set_par_text(find(d, "Task/Issue → Feature Branch"),
             "Issue → Discussion → Feature Branch → Development & Tests → Pull Request (Closes #issue) → Review → "
             "CI gate → Rebase merge")
set_par_text(find(d, "Commits should contain clear descriptions"),
             "Commit messages follow Conventional Commits (feat, fix, docs, test, with the service as scope), and "
             "each pull request closes its issue, which a pr-policy check enforces. Database changes are Flyway "
             "migrations numbered per service and merged in order.")

# Change log: no blank rows, the 28/11 row says one thing about UAT, a row for this pass
log = d.tables[0]._tbl
for tr in log.findall(qn("w:tr"))[1:]:
    if not text_of(tr).strip():
        log.remove(tr)
last = log.findall(qn("w:tr"))[-1].findall(qn("w:tc"))
set_cell_text(last[3], text_of(last[3]).strip()
              .replace("; acceptance test row marked as not run yet.", ".")
              .replace(" and the UAT run of Report-5.5.", "."))
append_row(log, ["28/11/2026", "M", "Minh Cuong",
                 "Scope and out of scope follow Report 1 (coffee only, LI-14 to LI-23); note under I.3 on the "
                 "baseline plan, the delivery phases P1-P3 and the 27 pds of the deferred packages; objectives: "
                 "DEF-20 closed, acceptance not run yet (Playwright dry run noted), verification gate pass rate; "
                 "II.1, II.2 and II.6 describe the process the team followed; org chart without the tester role."])

d.save(DOC)
print("saved", DOC.name)
