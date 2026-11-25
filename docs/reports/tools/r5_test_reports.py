"""Report 5.0: fills section II.3 Test Reports from the runs recorded so far (content only, styles kept).

Numbers as of 25/11/2026:
* UT, Report-5.1_Unit_Test.xlsx (with PR #650): Round 1 = 1035 cases, 1034 Passed, 1 Failed (AnalyticsTopology
  UTC004, #449); Round 2 = 464 Passed (modules merged by 31/10, re-run 07/11); Round 3 = 450 Passed (api-gateway,
  product-service, user-service, per-test surefire results on 614e6ab6).
* IT, Report-5.2_Test_Report.xlsx (api-gateway, user-service, product-service): Round 1 = 48 cases, 31 Passed,
  17 Failed (#647, seed without verified_email); Round 2 = 48 Passed after the fix in PR #648.
* ST and AT have not run: "-".
Also: II.2 and the Report-5.2 tool row follow Report-5.2 v1.1, the cover date says Nov 2026, change log row.
"""
import copy
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import fill_change_log, find, next_table, set_cell_text, set_par_text  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-5.0_Test Documentation.docx"
d = docx.Document(DOC)


def plain(p):
    """Drops the guidance look (italic, blue) from the paragraph's runs."""
    for rpr in p.iter(qn("w:rPr")):
        for tag in ("w:i", "w:iCs", "w:color"):
            for el in rpr.findall(qn(tag)):
                rpr.remove(el)
    return p


def rows(tbl):
    return tbl.findall(qn("w:tr"))


def write_row(tr, values):
    for tc, v in zip(tr.findall(qn("w:tc")), values):
        if v is not None:
            set_cell_text(tc, v)


def total(*xs):
    nums = [x for x in xs if isinstance(x, int)]
    return sum(nums) if nums else "-"


def fmt(x):
    return str(x) if isinstance(x, int) else x


# ---------------------------------------------------------------- cover
set_par_text(find(d, "– Hanoi, Aug 2026 –"), "– Hanoi, Nov 2026 –")

# ---------------------------------------------------------------- II.2 and the tools table follow Report-5.2 v1.1
set_par_text(find(d, "Other Test Cases (IT, ST): Report-5.2_Test_Report.xlsx"),
             "Other Test Cases (IT, ST): Report-5.2_Test_Report.xlsx, the same template as Report-5.1 filled from "
             "the Bruno collection backend/bruno (scripts/unit-test-report/data_bruno.py), so far for api-gateway, "
             "user-service and product-service. Every Bruno case is an integration test (ITC); Pre-conditions say "
             "whether it calls the service directly or goes through the gateway, and Note names its Bruno request. "
             "Each round is a real run of run_bruno_report52.sh whose Bruno JSON reports are kept in "
             "scripts/unit-test-report/results/. The other services add their sheets; ST cases follow the FE flows "
             "BF-01..BF-07, and AT cases are added after the mentor demo.")
tools = next_table(find(d, "Tools used to write, run and report the tests"))
for tr in rows(tools):
    cells = tr.findall(qn("w:tc"))
    if "".join(t.text or "" for t in cells[1].iter(qn("w:t"))).strip() == "Report-5.2_Test_Report.xlsx":
        set_cell_text(cells[3], "v1.1")

# ---------------------------------------------------------------- II.3 intro
set_par_text(plain(find(d, "[Provide the test result, statistics")),
             "Results as of 25/11/2026. UT numbers come from Report-5.1_Unit_Test.xlsx and IT numbers from "
             "Report-5.2_Test_Report.xlsx, which so far covers api-gateway, user-service and product-service. System "
             "tests (FE flows BF-01..BF-07) and acceptance tests have not been run yet, so their columns are \"-\". "
             "A defect is a GitHub issue opened from a failed case; its severity follows the issue priority "
             "(P0 Critical, P1 Major, P2 Minor). #449 has no priority and is classed Minor because only test code "
             "is affected.")

# ---------------------------------------------------------------- II.3.1 Summary Table
summary = next_table(find(d, "3.1 Summary Table"))
head, r1_total, r1_pass, r1_fail, dots = rows(summary)
ROUNDS = [
    ("Round #1", (1035, 1034, 1), (48, 31, 17),
     ["UT: Report-5.1 Round 1 (31/10/2026, later modules on their merge date). IT: Report-5.2 Round 1 (25/11/2026, "
      "commit 36caa3c9)", "",
      "UT: AnalyticsTopology UTC004 (#449). IT: 17 cases failed on #647 (seed profiles without verified_email)"]),
    ("Round #2", (464, 464, 0), (48, 48, 0),
     ["UT: re-run on 07/11/2026 of the modules merged by 31/10. IT: whole collection re-run after the #647 fix "
      "(PR #648), confirmation and regression", "", ""]),
    ("Round #3", (450, 450, 0), ("-", "-", "-"),
     ["UT: api-gateway, product-service, user-service on 614e6ab6, every row read from its own tests' surefire "
      "results (scripts/unit-test-report/results/unit-round3)", "", ""]),
]
block = [r1_total, r1_pass, r1_fail]
anchor = r1_fail
for i, (name, ut, it, notes) in enumerate(ROUNDS):
    if i == 0:
        trs = block
    else:
        trs = [copy.deepcopy(tr) for tr in block]
        for tr in trs:
            anchor.addnext(tr)
            anchor = tr
    for j, (tr, metric) in enumerate(zip(trs, ("Total", "Passed", "Failed"))):
        write_row(tr, [name if j == 0 else None, metric, fmt(ut[j]), fmt(it[j]), "-", "-",
                       fmt(total(ut[j], it[j])), notes[j]])
summary.remove(dots)

# ---------------------------------------------------------------- II.3.2 Test Levels' Defects
levels = next_table(find(d, "3.2 Test Levels"))
_, tot, crit, major = rows(levels)
write_row(tot, ["Total Defects", "-", "1", "1", "-", "-", "2",
                "#449 (UT, Minor: test code only, the topology test matches a comment), #647 (IT, P1: seed without "
                "verified_email)"])
write_row(crit, ["Open Defects (Critical)", "-", "0", "0", "-", "-", "0", ""])
write_row(major, ["Open Defects (Major)", "-", "0", "0", "-", "-", "0", "#647 fixed in PR #648 (closed 25/11/2026)"])

# ---------------------------------------------------------------- II.3.3 Work Product's Defects
products = next_table(find(d, "3.3 Work Product"))
_, package, report1, report2, dots = rows(products)
write_row(package, ["Software Package", "-", "1", "1", "-", "-", "2",
                    "#449: test code of analytics-service; #647: backend/scripts/seed/02-user-db.sql"])
for tr in (report1, report2):
    write_row(tr, [None, "-", "-", "-", "-", "-", "-", "Report reviews are not counted as defects here"])
products.remove(dots)

# ---------------------------------------------------------------- Change log
fill_change_log(next_table(find(d, "Change Log", tag="w:p")),
                ["25/11/2026", "M", "Hai Long",
                 "Section II.3: summary table (UT rounds 1-3 from Report-5.1, IT rounds 1-2 from Report-5.2), defects "
                 "by level and by work product (#449, #647). II.2 and tools: Report-5.2 v1.1. Cover date"])

d.save(DOC)
print("saved", DOC.name)
