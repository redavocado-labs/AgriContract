"""Report 2 I.5 Actual column: effort, review defects and timeliness as estimates (no timesheets kept), unit test
coverage measured on 28/11 with JaCoCo (Java) and Jest (Node.js) on the domain and application layers. Requirement
completeness and the verification gate wait for the 30/11 run. Content only, styles kept.

    python r2_metrics_2811.py
"""
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import set_cell_text, text_of  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-2_Project_Management_Plan_v1.docx"
d = docx.Document(DOC)

obj = next(t for t in d.element.body.iter(qn("w:tbl")) if "Effort Usage" in text_of(t))
ACTUAL = {
    "Effort Usage": ("≈ 300 pds",
                     "Effective capacity budget; variance >10% requires replanning. No timesheets were kept; the figure "
                     "is estimated from the weekly plan and the work done, about 7% over budget."),
    "Review Defects": ("0",
                       "Critical/High requirement and design review findings must be resolved before the document is "
                       "baselined. Reviews raised 102 change requests on 66 of 362 pull requests; all were resolved "
                       "before merge."),
    "Timeliness": ("≈ 85%",
                   "Total on-time deliverables / Total project deliverables. Estimate. Report 5 was finished after its planned "
                   "week because the system tests were redone through the web client."),
    "Automated Test Coverage": ("55%",
                                "Line coverage of business logic (domain and application layers) by unit tests, "
                                "28/11/2026: domain 89%, application 40%; JaCoCo for Java services, Jest coverage for Node.js services. Application "
                                "use cases are mostly covered by integration tests, which are not counted."),
}
done = set()
for tr in obj.findall(qn("w:tr")):
    tcs = tr.findall(qn("w:tc"))
    name = text_of(tcs[1]).strip()
    if name in ACTUAL:
        actual, note = ACTUAL[name]
        set_cell_text(tcs[4], actual)
        if note:
            set_cell_text(tcs[5], note)
        done.add(name)
assert done == set(ACTUAL), set(ACTUAL) - done

for tr in d.element.body.iter(qn("w:tr")):
    tcs = tr.findall(qn("w:tc"))
    if tcs and text_of(tcs[0]).strip() == "Testing / Quality":
        set_cell_text(tcs[1], "JUnit 5, Mockito and Spring Boot Test for Java; Jest for Node.js; JaCoCo and Jest "
                              "coverage; Testcontainers for MySQL/RabbitMQ integration testing; Bruno CLI for API "
                              "tests; Playwright for tests through the web client")

log = d.tables[0]._tbl.findall(qn("w:tr"))[-1].findall(qn("w:tc"))
set_cell_text(log[3], text_of(log[3]).strip() + " Project objectives: actual effort, review defects, timeliness "
              "and test coverage filled in.")
d.save(DOC)
print("saved", DOC.name)
