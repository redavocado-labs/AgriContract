"""Report 5.0: sections I and II brought up to the test runs of 28/11/2026 (content only, styles kept).

Numbers are read from the workbooks, not typed:
* UT  Report-5.1_Unit_Test.xlsx              Rounds 1-3 (Round 3 = regression run of 28/11 of every service)
* IT  Report-5.2_Test_Report.xlsx            Rounds 1-3 (Bruno, api-gateway / user / product, GoldenFlow)
* ST  Report-5.3_System_Test_FRs.xlsx        Rounds 1-3 (run-e2e-full.sh, BF-01..BF-07 and Security)
* NFR Report-5.4_System_Test_NFRs.xlsx       Security, Reliability, Usability, Performance (one status per row)
* AT  Report-5.5_Acceptance Test Scripts.xlsx UAT scripts (Result Not Executed counts as Pending)
* defects  Report-5.6_Defect_Log.xlsx
A round's Total counts its Passed, Failed and Pending cases; N/A cases are left out of Total and named in the Note.

    python r5_test_results_2811.py
"""
import collections
import sys
from pathlib import Path

import docx
import openpyxl
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import append_row, fill_change_log, find, next_table, set_cell_text, set_par_text  # noqa: E402

REPORTS = Path(__file__).resolve().parents[1]
DOC = REPORTS / "Report-5.0_Test Documentation.docx"
B = {k: REPORTS / v for k, v in {
    "5.1": "Report-5.1_Unit_Test.xlsx", "5.2": "Report-5.2_Test_Report.xlsx",
    "5.3": "Report-5.3_System_Test_FRs.xlsx", "5.4": "Report-5.4_System_Test_NFRs.xlsx",
    "5.5": "Report-5.5_Acceptance Test Scripts.xlsx", "5.6": "Report-5.6_Defect_Log.xlsx"}.items()}


def rounds(book, prefix):
    wb = openpyxl.load_workbook(B[book], read_only=True, data_only=True)
    out = [collections.Counter() for _ in range(3)]
    for ws in wb.worksheets[3:]:
        for row in ws.iter_rows(min_row=12, max_col=12, values_only=True):
            if isinstance(row[0], str) and row[0].startswith(prefix):
                for i, col in enumerate((5, 8, 11)):
                    if row[col]:
                        out[i][row[col]] += 1
    return out


def uat():
    wb = openpyxl.load_workbook(B["5.5"], read_only=True)
    c = collections.Counter()
    for row in wb["UAT"].iter_rows(min_row=2, max_col=8, values_only=True):
        if row[0]:
            c[{"Pass": "Passed", "Fail": "Failed"}.get(row[7], "Pending")] += 1
    return [c, collections.Counter(), collections.Counter()]


def nfr():
    wb = openpyxl.load_workbook(B["5.4"], read_only=True)
    out = {}
    for ws in wb.worksheets:
        col = 5 if ws.title == "Performance" else 7
        out[ws.title] = collections.Counter(row[col] for row in ws.iter_rows(min_row=2, values_only=True) if row[0])
    return out


def defects():
    wb = openpyxl.load_workbook(B["5.6"], read_only=True)
    header, out = None, []
    for row in wb["Defect List"].iter_rows(values_only=True):
        if row and row[0] == "Defect ID":
            header = list(row)
        elif header and row and str(row[0] or "").startswith("DEF-"):
            out.append(dict(zip(header, row)))
    return out


LEVELS = {"UT": rounds("5.1", "UTC"), "IT": rounds("5.2", "ITC"), "ST": rounds("5.3", "STC"), "AT": uat()}
NFR = nfr()
DEFECTS = defects()
opened = [x for x in DEFECTS if x["Status"] == "Open"]


def cell(level, rnd, metric):
    c = LEVELS[level][rnd]
    if not c:
        return "-"
    if metric == "Total":
        return c["Passed"] + c["Failed"] + c["Pending"]
    return c[metric]


def plain_total(values):
    nums = [v for v in values if isinstance(v, int)]
    return sum(nums) if nums else "-"


def extra(level, rnd):
    c = LEVELS[level][rnd]
    parts = [f"{c[k]} {k}" for k in ("Pending", "N/A") if c[k]]
    return f"{level} {', '.join(parts)}" if parts else ""


def counter_text(c):
    return ", ".join(f"{n} {k}" for k, n in c.most_common())


d = docx.Document(DOC)
rows = lambda tbl: tbl.findall(qn("w:tr"))


def write_row(tr, values):
    for tc, v in zip(tr.findall(qn("w:tc")), values):
        if v is not None:
            set_cell_text(tc, str(v))


# ---------------------------------------------------------------- I.1 constraints, I.2.1 performance testing
set_par_text(find(d, "Constraints and assumptions:"),
             "Constraints and assumptions: the team has five members and no dedicated tester, so every developer "
             "writes the UT and IT of the code they own and the team runs ST together; GitHub Actions was "
             "unavailable from 07/11 to 15/11/2026 (account billing lock), when the same CI commands were run locally "
             "on the pull request head before merge; test data is synthetic and no real money or personal data is "
             "used; the external bank and the External Verifier are simulated by bank-service and a signing script; "
             "the signing to activation to milestone chain of ST and the golden flow can only run on a Vietnam "
             "business day, because a milestone is funded at activation only when its funding date is today (D63, "
             "D67).")
set_par_text(find(d, "Performance testing. Objective:"),
             "Performance testing. Objective: the whole stack runs on one demo machine and meets the SRS response "
             "targets for 100 concurrent users. Technique: response times of every write request of the ST and IT "
             "rounds (Bruno reports), browse and filtered search over 10,000 listings for one user, 100 concurrent "
             "users looping over the main public and signed-in reads (scripts/unit-test-report/perf_nfr.py), and "
             "page loads of the built web client measured by Playwright during that load. Completion: targets met or "
             "exceeded by no more than 50 %, with no errors. Results in Report-5.4, sheet Performance.")

# ---------------------------------------------------------------- I.2.3 tools
tools = next_table(find(d, "2.3 Supporting Tools"))
for tr in rows(tools):
    tcs = tr.findall(qn("w:tc"))
    first = "".join(t.text or "" for t in tcs[0].iter(qn("w:t")))
    if first.startswith("Load and response-time testing"):
        write_row(tr, ["Load and response-time testing: 100 concurrent users, 10,000 listings "
                       "(scripts/unit-test-report/perf_nfr.py)", "Python 3 (threads, urllib), Playwright",
                       "In-house", "Python 3.14"])
for values in (
        ["System test case and statistics workbook (run-e2e-full.sh rounds)", "Report-5.3_System_Test_FRs.xlsx",
         "In-house", "v1.0"],
        ["Non-functional system test workbook (security, reliability, usability, performance)",
         "Report-5.4_System_Test_NFRs.xlsx", "In-house", "v1.0"],
        ["Acceptance test scripts (UAT) and exploratory log", "Report-5.5_Acceptance Test Scripts.xlsx", "In-house",
         "v1.0"],
        ["Defect log and statistics", "Report-5.6_Defect_Log.xlsx", "In-house", "v1.0"]):
    if values[1] not in "".join(t.text or "" for t in tools.iter(qn("w:t"))):
        append_row(tools, values)

# ---------------------------------------------------------------- II.2 test case workbooks
set_par_text(find(d, "The test cases are kept in"),
             "The test cases are kept in six workbooks, all on the provided templates and generated by scripts under "
             "scripts/unit-test-report/ from real runs:")
set_par_text(find(d, "Unit Test Cases: Report-5.1"),
             "Unit Test Cases (UT): Report-5.1_Unit_Test.xlsx, one sheet per tested class; Round 3 is read per test "
             "from the surefire / Jest reports of the regression run.")
set_par_text(find(d, "System Test Cases (ST): Report-5.3"),
             "System Test Cases (ST, functional): Report-5.3_System_Test_FRs.xlsx, one sheet per business flow "
             "BF-01..BF-07 plus security, one row per Bruno step of backend/bruno/e2e and backend/bruno/security; "
             "rounds are runs of backend/scripts/run-e2e-full.sh on a fresh Docker Compose stack, with an Actual "
             "Results column.")
set_par_text(find(d, "UI and Acceptance Test Cases (AT): Report-5.4"),
             "System Test Cases (ST, non-functional): Report-5.4_System_Test_NFRs.xlsx, one row per SRS IV.2 "
             "requirement on sheets Security, Reliability, Usability and Performance; each row names its evidence "
             "(UT, ST case, Playwright test of frontend/tests/qa, or the load measurement) and takes its status from "
             "it.")
set_par_text(find(d, "Defect Log: Report-5.5"),
             "Acceptance Test Scripts (AT): Report-5.5_Acceptance Test Scripts.xlsx, one UAT script per business "
             "scenario of BF-01..BF-07 in business language, run by a business user on the demo stack; the "
             "Exploratory sheet logs free exploration sessions.\n"
             "Defect Log: Report-5.6_Defect_Log.xlsx, every real test failure with its run, cause, fix and re-test; "
             "the Defect IDs columns of 5.1-5.4 point at it.")

# ---------------------------------------------------------------- II.3 intro
set_par_text(find(d, "Results as of"),
             "Results as of 28/11/2026, read from the workbooks of II.2 (UT Report-5.1, IT Report-5.2, ST "
             "Report-5.3, NFR Report-5.4, AT Report-5.5) and the defect log Report-5.6. A round's Total counts its "
             "Passed, Failed and Pending cases; N/A cases (not part of that round, or not implemented yet) are left "
             "out and named in the Note. Round 1 is each level's first run; Round 2 confirms the fixes of Round 1; "
             "Round 3 is the regression run of 28/11/2026 on the current code (UT every service, IT the whole Bruno "
             "collection, ST the whole run-e2e-full.sh). ST Round 3 ran on a Saturday: the second signature was "
             "refused (DEF-19), so BF-04 did not run and the audit cases that read its settled contract failed. AT "
             "counts the UAT scripts of Report-5.5, not yet run by a business user. A defect is an entry of "
             "Report-5.6; its severity there (High, Medium, Low) is counted below as Critical, Major and Minor. "
             f"{len(opened)} defects are still open: "
             + ", ".join(f"{x['Defect ID']} ({x['Fix (issue / PR)']})" for x in opened) + ".")

# ---------------------------------------------------------------- II.3.1 Summary Table
nfr_note = "; ".join(f"{k} {counter_text(v)}" for k, v in NFR.items())
NOTES = [
    ["UT: Report-5.1 Round 1 (31/10/2026, later modules on their merge date). IT: Report-5.2 Round 1 (25/11/2026; "
     "GoldenFlow 27/11). ST: Report-5.3 run-20261127-1603 on 94d46500. AT: Report-5.5 UAT scripts, Not Executed"
     + "; " + "; ".join(x for x in (extra(lv, 0) for lv in LEVELS) if x),
     "",
     "UT: AnalyticsTopology UTC004 (DEF-01). IT: 17 cases on DEF-16 (#647) and GoldenFlow on DEF-17. ST: 12 security "
     "cases on DEF-15 (#663) and BF04-250 on DEF-18 (#685)"],
    ["UT: re-run on 07/11/2026 of the modules merged by 31/10, and UTC004 after #449. IT: whole collection after "
     "PR #648 and GoldenFlow after PR #678. ST: run-20261127-1630 after PR #686"
     + "; " + "; ".join(x for x in (extra(lv, 1) for lv in LEVELS) if x),
     "",
     "ST: the 12 DEF-15 cases (#663 still open)"],
    ["Regression 28/11/2026. UT: every service, per test from surefire / Jest (results/ut-round3-20261128). IT: "
     "results/round3; GoldenFlow skipped itself (not a business day). ST: run-20261128-0350 (results/st-round3). "
     f"NFR Report-5.4: {nfr_note}"
     + "; " + "; ".join(x for x in (extra(lv, 2) for lv in LEVELS) if x),
     "",
     "ST: 12 cases on DEF-15 (#663), 9 on DEF-19 (Saturday run: BF03-070 refused, BF-04 fixtures and audit reads "
     "without a contract)"],
]
summary = next_table(find(d, "3.1 Summary Table"))
data_rows = rows(summary)[1:]
assert len(data_rows) == 9
for r in range(3):
    for j, metric in enumerate(("Total", "Passed", "Failed")):
        values = [cell(lv, r, metric) for lv in LEVELS]
        write_row(data_rows[3 * r + j], [f"Round #{r + 1}" if j == 0 else None, metric, *values,
                                         plain_total(values), NOTES[r][j]])

# ---------------------------------------------------------------- II.3.2 Test Levels' Defects
SEVERITY = {"High": "Critical", "Medium": "Major", "Low": "Minor"}
level_of = lambda x: x["Level"].split("/")[0]
by_level = collections.Counter(level_of(x) for x in DEFECTS)
open_by = {s: collections.Counter(level_of(x) for x in opened if SEVERITY[x["Severity"]] == s)
           for s in ("Critical", "Major")}
levels = next_table(find(d, "3.2 Test Levels"))
_, tot, crit, major = rows(levels)
write_row(tot, ["Total Defects", "-", *(by_level[lv] or "-" for lv in LEVELS), len(DEFECTS),
                "Report-5.6 DEF-01..DEF-%02d. DEF-11 (UT/IT) is counted under UT. DEF-09/10 are Playwright failures "
                "of 25/11, filed at ST; DEF-20 was found by the load test of Report-5.4" % len(DEFECTS)])
for tr, s in ((crit, "Critical"), (major, "Major")):
    names = ", ".join(f"{x['Defect ID']} {x['Level']} ({x['Fix (issue / PR)']})" for x in opened
                      if SEVERITY[x["Severity"]] == s)
    write_row(tr, [f"Open Defects ({s})", "-", *(open_by[s][lv] for lv in LEVELS), sum(open_by[s].values()),
                   names or "None open"])

# ---------------------------------------------------------------- II.3.3 Work Product's Defects
products = next_table(find(d, "3.3 Work Product"))
modules = collections.Counter(str(x["Module"]).split(" (")[0] for x in DEFECTS)
write_row(rows(products)[1], ["Software Package", "-", *(by_level[lv] or "-" for lv in LEVELS), len(DEFECTS),
                              "By module: " + ", ".join(f"{m} {n}" for m, n in modules.most_common())])

# ---------------------------------------------------------------- Change log
fill_change_log(next_table(find(d, "Change Log", tag="w:p")),
                ["28/11/2026", "M", "Minh Cuong",
                 "Section I: CI constraint, business-day constraint of ST, performance technique and tools as run. "
                 "Section II.2: six workbooks on the school templates (5.3 ST FRs, 5.4 ST NFRs, 5.5 UAT scripts, "
                 "5.6 defect log). Section II.3: Round 3 regression of 28/11 for UT, IT and ST, AT from the UAT "
                 "scripts, defects DEF-19 and DEF-20"])

d.save(DOC)
print("saved", DOC.name)
for lv, rs in LEVELS.items():
    print(lv, [dict(c) for c in rs])
print("nfr", {k: dict(v) for k, v in NFR.items()})
print("defects", dict(by_level), "open", [x["Defect ID"] for x in opened])
