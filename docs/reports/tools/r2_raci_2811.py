"""Report 2 RACI and training plan follow who did the work: Khanh wrote the PMP under the leader, the leader and Long
wrote the test reports, there is no dedicated tester (Report 5.0). Content only, styles kept.

    python r2_raci_2811.py
"""
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import set_cell_text, text_of  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-2_Project Management Plan_v1.docx"
d = docx.Document(DOC)

# columns: Cuong, Long, Khanh, Hung, Minh
RACI = {
    "Project Management Plan v1.0": ["A", "C", "R", "C", "C"],
    "Test Planning": ["A/R", "R", "C", "C", "C"],
    "Iteration 1 – Testing & Package v1": ["A/R", "R", "R", "R", "R"],
    "Iteration 2 – Testing, Report & Package v2": ["A/R", "R", "R", "R", "R"],
    "Full-Scope Integration/System Testing & Package v3": ["A/R", "R", "R", "R", "R"],
    "System Test Report": ["A/R", "R", "C", "C", "C"],
}
raci = next(t for t in d.element.body.iter(qn("w:tbl")) if "CuongNMHE187382" in text_of(t))
done = set()
for tr in raci.findall(qn("w:tr")):
    tcs = tr.findall(qn("w:tc"))
    name = text_of(tcs[0]).strip()
    if name in RACI:
        for tc, v in zip(tcs[1:], RACI[name]):
            set_cell_text(tc, v)
        done.add(name)
assert done == set(RACI), set(RACI) - done

for tc in d.element.body.iter(qn("w:tc")):
    t = text_of(tc).strip()
    if t == "All members; coordinated by Tester":
        set_cell_text(tc, "All members; coordinated by the team leader")
    elif t == "Developers and QA members":
        set_cell_text(tc, "All members")

log = d.tables[0]._tbl.findall(qn("w:tr"))[-1].findall(qn("w:tc"))
set_cell_text(log[3], text_of(log[3]).strip() + " RACI and training plan follow who did the work; no dedicated "
              "tester role.")
d.save(DOC)
print("saved", DOC.name)
