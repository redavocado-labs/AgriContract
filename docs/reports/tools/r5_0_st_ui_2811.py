"""Report 5.0 after the system test was redone through the web client (28/11): ST = Playwright on the real stack
(tests/st, Report-5.3, 30 cases), the Bruno business-flow and security collection is integration testing (Report-5.2),
test folders moved to tests/ (api, scripts, st), the UAT scripts were run by Playwright, defects DEF-21..DEF-23.
Summary, defect and work product tables recounted from Reports 5.2, 5.3, 5.5 and 5.6. Content only, styles kept.

    python r5_0_st_ui_2811.py
"""
import copy
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import append_row, set_cell_text, set_par_text, text_of  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-5.0_Test_Documentation.docx"
d = docx.Document(DOC)
body = d.element.body


def sub(old: str, new: str):
    """old -> new in the one body paragraph (table cells included) that contains it."""
    ps = [p for p in body.iter(qn("w:p")) if old in text_of(p)]
    if len(ps) != 1:
        raise ValueError(f"{old[:60]!r}: {len(ps)} paragraphs")
    p = ps[0]
    for t in p.iter(qn("w:t")):
        if t.text and old in t.text:
            t.text = t.text.replace(old, new)
            t.set("{http://www.w3.org/XML/1998/namespace}space", "preserve")
            return p
    set_par_text(p, text_of(p).replace(old, new))
    return p


# ---- I.1 levels and constraints
sub("focus is persistence, outbox publishing, consumers, idempotency and REST adapters; done when the CI job of the "
    "module passes.",
    "focus is persistence, outbox publishing, consumers, idempotency and REST adapters; done when the CI job of the "
    "module passes. The Bruno collection (tests/api) also calls the API of each business flow BF-01..BF-07 and the "
    "security cases through the gateway on the full stack.")
sub("focus is each business flow end to end through the gateway;",
    "focus is each business flow end to end through the web client (Playwright on the real stack, tests/st);")
sub("can only run on a Vietnam business day, because a milestone is funded at activation only when its funding date "
    "is today (D63, D67).",
    "can only run on a Vietnam business day, because a milestone is funded at activation only when its funding date "
    "is today.")

# ---- I.2 testing types
sub("Bruno requests at IT, the user flows BF-01..BF-07 at ST,",
    "Bruno requests at IT (one service, or a whole business flow through the gateway), the user flows BF-01..BF-07 "
    "through the web client at ST,")
sub("response times of every write request of the ST and IT rounds (Bruno reports)",
    "response times of every write request of the IT rounds (Bruno reports)")
sub("Technique: Playwright browser scripts (frontend/tests/qa) on the role gates, command recovery and evidence upload "
    "of the business flows, axe accessibility scans, keyboard use and 320 px reflow;",
    "Technique: Playwright browser scripts in two suites: frontend/tests/qa (API responses stubbed, run in CI) on the "
    "role gates, command recovery and evidence upload of the business flows, axe accessibility scans, keyboard use "
    "and 320 px reflow; tests/st on the real stack in Chromium and Firefox, which also gives the usability and "
    "browser evidence of Report-5.4;")
sub("Round 3 of 28/11/2026 in Report-5.1, 5.2 and 5.3 is such a run.",
    "Round 3 of 28/11/2026 in Report-5.1 and 5.2 is such a run.")
sub("run by a business user on the demo stack, plus exploratory sessions logged in the same workbook.",
    "run by a business user on the demo stack, plus exploratory sessions logged in the same workbook; on 28/11/2026 "
    "the scripts were run by Playwright through the web client.")

# ---- I.2.3 tools
sub("API test collections: the integration test cases of Report-5.2, each calling one service (or the broker) "
    "directly or going through the gateway with a Keycloak token",
    "API test collections (tests/api): the integration test cases of Report-5.2, each calling one service (or the "
    "broker) directly or going through the gateway with a Keycloak token, including the business flows "
    "BF-01..BF-07 and the security cases")
pw = next(tr for tr in body.iter(qn("w:tr"))
          if text_of(tr.findall(qn("w:tc"))[0]).startswith("Automated browser tests of the web client: frontend/tests/qa"))
st_row = copy.deepcopy(pw)
pw.addnext(st_row)
tcs = st_row.findall(qn("w:tc"))
set_cell_text(tcs[0], "System tests through the web client on the real stack: tests/st (Chromium and Firefox; "
                      "Report-5.3, usability and browser evidence of Report-5.4, UAT runs of Report-5.5)")
set_cell_text(tcs[3], "1.64")
sub("Integration test case and statistics workbook (built from backend/bruno)",
    "Integration test case and statistics workbook (built from tests/api)")
sub("System test case and statistics workbook (run-e2e-full.sh rounds)",
    "System test case and statistics workbook (Playwright through the web client)")

# ---- II.1 paths
sub("backend/scripts/run-e2e.sh reloads the seed before each Bruno run, backend/bruno/notification-service/reset.sh",
    "tests/scripts/run-e2e.sh reloads the seed before each Bruno run, tests/api/notification-service/reset.sh")
sub("the Bruno environment backend/bruno/environments/local.bru", "the Bruno environment tests/api/environments/local.bru")

# ---- II.2 workbooks
sub("filled from the Bruno collection backend/bruno for api-gateway, user-service and product-service, plus the "
    "GoldenFlow module (the whole stack built from a commit).",
    "filled from the Bruno collection tests/api: api-gateway, user-service and product-service called one at a "
    "time, the GoldenFlow module (the whole stack built from a commit), and 8 sheets of business-flow and security "
    "requests through the gateway (BF01..BF07 ...Api and SecurityApi, ITC IDs) moved from Report-5.3; their rounds "
    "are runs of tests/scripts/run-e2e-full.sh on a fresh Docker Compose stack.")
st = sub("one sheet per business flow BF-01..BF-07 plus security, one row per Bruno step of backend/bruno/e2e and "
         "backend/bruno/security; rounds are runs of backend/scripts/run-e2e-full.sh on a fresh Docker Compose stack, "
         "with an Actual Results column.",
         "30 Playwright cases through the web client on the real stack (tests/st) in 5 sheets: BF01_Onboarding, "
         "BF02_Marketplace, BF03_Signing, BF04_Milestone and RolePortals, with an Actual Results column; Round 1 of "
         "28/11/2026: 23 Passed, 7 Pending (signing, activation and milestone steps that need a Vietnam business "
         "day).")
sub("each row names its evidence (UT, ST case, Playwright test of frontend/tests/qa, or the load measurement)",
    "each row names its evidence (UT, IT case, ST case through the web client of tests/st, Playwright test of "
    "frontend/tests/qa, or the load measurement)")
at = next(p for p in body.iter(qn("w:p")) if text_of(p).startswith("Acceptance Test Scripts (AT): Report-5.5"))
defect = copy.deepcopy(at)
at.addnext(defect)
set_par_text(at, "Acceptance Test Scripts (AT): Report-5.5_Acceptance_Test_Scripts.xlsx, one UAT script per business "
                 "scenario of BF-01..BF-07 in business language, run by a business user on the demo stack; on "
                 "28/11/2026 they were run by Playwright through the web client (1 Pass, 4 Partial, 6 Not Executed). "
                 "The Exploratory sheet logs free exploration sessions.")
set_par_text(defect, "Defect Log: Report-5.6_Defect_Log.xlsx, every real test failure with its run, cause, fix and "
                     "re-test; the Defect IDs columns of 5.1-5.4 point at it.")

# ---- II.3 intro
p = next(p for p in body.iter(qn("w:p")) if text_of(p).startswith("Results as of 28/11/2026, read from the workbooks"))
set_par_text(p,
             "Results as of 28/11/2026, read from the workbooks of II.2 (UT Report-5.1, IT Report-5.2, ST Report-5.3, "
             "NFR Report-5.4, AT Report-5.5) and the defect log Report-5.6. A round's Total counts its Passed, Failed "
             "and Pending cases; N/A cases (not part of that round, or not implemented yet) are left out and named in "
             "the Note. Round 1 is each level's first run; Round 2 confirms the fixes of Round 1; Round 3 is the "
             "regression run of 28/11/2026 on the current code (UT every service, IT the whole Bruno collection). The "
             "Bruno business-flow and security cases, counted at ST before 28/11, are integration tests and are "
             "counted at IT in every round. IT Round 3 ran on a Saturday: the second signature was refused (DEF-19), "
             "so BF-04 did not run and the audit cases that read its settled contract failed. ST is the system test "
             "through the web client, first run on 28/11/2026. AT counts the UAT scripts of Report-5.5 run by "
             "Playwright; a Partial or Not Executed script is counted as Pending. A defect is an entry of Report-5.6; "
             "its severity there (High, Medium, Low) is counted below as Critical, Major and Minor. 5 defects are "
             "still open: DEF-03 (#472), DEF-15 (#663), DEF-19 (#713), DEF-20 (#714), DEF-23 (#717).")


def table(start: str):
    return next(t for t in body.iter(qn("w:tbl")) if text_of(t).startswith(start))


def fill(tbl, rows):
    """rows: {row index: [cell values from column 2 on, None keeps a cell]}"""
    trs = tbl.findall(qn("w:tr"))
    for i, vals in rows.items():
        tcs = trs[i].findall(qn("w:tc"))
        for tc, v in zip(tcs[2:], vals):
            if v is not None:
                set_cell_text(tc, v)


fill(table("RoundMetrics"), {
    1: ["1035", "169", "30", "11", "1245",
        "UT: Report-5.1 Round 1 (31/10/2026, later modules on their merge date). IT: Report-5.2 Round 1 (25/11/2026; "
        "GoldenFlow 27/11; business-flow and security sheets run-20261127-1603 on 94d46500), 9 Pending, 8 N/A. ST: "
        "Report-5.3 Round 1 through the web client (28/11/2026), 7 Pending. AT: Report-5.5 run by Playwright "
        "(28/11/2026): 1 Pass, 4 Partial, 6 Not Executed"],
    2: ["1034", "129", "23", "1", "1187", None],
    3: ["1", "31", "0", "0", "32",
        "UT: AnalyticsTopology UTC004 (DEF-01). IT: 17 cases on DEF-16 (#647), GoldenFlow on DEF-17, 12 security "
        "cases on DEF-15 (#663) and BF04-250 on DEF-18 (#685)"],
    4: ["465", "169", "-", "-", "634",
        "UT: re-run on 07/11/2026 of the modules merged by 31/10, and UTC004 after #449. IT: whole collection after "
        "PR #648, GoldenFlow after PR #678, business-flow and security sheets run-20261127-1630 after PR #686; 9 "
        "Pending, 8 N/A"],
    5: ["465", "148", "-", "-", "613", None],
    6: ["0", "12", "-", "-", "12", "IT: the 12 DEF-15 cases (#663 still open)"],
    7: ["1224", "169", "-", "-", "1393",
        "Regression 28/11/2026. UT: every service, per test from surefire / Jest (results/ut-round3-20261128). IT: "
        "results/round3 and run-20261128-0350 (results/st-round3); GoldenFlow skipped itself (not a business day); "
        "54 Pending, 8 N/A. NFR Report-5.4: Security 14 Pass, 2 Not Run, 1 Fail, 1 Blocked, 1 N/A; Reliability 9 "
        "Pass; Usability 7 Pass, 1 Fail, 1 Not Run; Performance 5 Pass, 4 Not Run, 2 Fail"],
    8: ["1224", "94", "-", "-", "1318", None],
    9: ["0", "21", "-", "-", "21",
        "IT: 12 cases on DEF-15 (#663), 9 on DEF-19 (Saturday run: BF03-070 refused, BF-04 fixtures and audit reads "
        "without a contract)"],
})
fill(table("MetricReview"), {
    1: ["6", "9", "8", "-", "23",
        "Report-5.6 DEF-01..DEF-23. DEF-11 (UT/IT) is counted under UT. DEF-09/10 are Playwright failures of 25/11, "
        "filed at ST; DEF-20 and DEF-22 were found during the load test of Report-5.4, DEF-21 and DEF-23 by the "
        "system test through the web client"],
    2: ["0", "0", "0", "0", "0", "None open"],
    3: ["0", "3", "1", "0", "4",
        "DEF-03 IT (#472), DEF-15 IT (#663), DEF-19 IT (#713), DEF-20 ST (#714). Minor open: DEF-23 ST (#717)"],
})
wp = table("ProductReview")
fill(wp, {1: ["6", "9", "8", "-", "23",
              "By module: notification-service 4, e2e 4, frontend 3, api-gateway 2, docker-compose 2, "
              "analytics-service 1, user-service 1, escrow-service 1, reputation-service 1, contract-service 1, "
              "product-service / seed 1, 9 service Java 1, Keycloak realm 1"]})
append_row(wp, ["Report 3 (SRS)", "-", "-", "-", "-", "-", "-", "Report reviews are not counted as defects here"])
append_row(wp, ["Report 4 (SDS)", "-", "-", "-", "-", "-", "-", "Report reviews are not counted as defects here"])

append_row(d.tables[0]._tbl, [
    "28/11/2026", "M", "Minh Cuong",
    "System test redone through the web client (Report-5.3, Playwright tests/st); Bruno business-flow and security "
    "cases moved to IT (Report-5.2); test folders moved to tests/ (api, scripts, st); UAT scripts run by Playwright; "
    "defects DEF-21..DEF-23; II.3 tables recounted; Defect Log on its own line in II.2; Report 3 and 4 rows in II.3.3"])
d.save(DOC)
print("saved", DOC.name)
