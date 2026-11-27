"""Report 2 I.5: integration, system and acceptance test defect rows recounted from Report-5.6 after the system test
through the web client (DEF-19 is an IT defect of the Bruno collection; DEF-21..DEF-23 are new ST defects) and the
UAT run of Report-5.5. Content only.

    python r2_defects_2811.py
"""
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import set_cell_text, text_of  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-2_Project Management Plan_v1.docx"
d = docx.Document(DOC)
ACTUAL = {
    "Integration Test Defects": "0 unresolved High (9 found, 4 High closed; 3 Medium open: DEF-03, DEF-15, DEF-19)",
    "System Test Defects": "0 unresolved High (8 found, 2 High closed; open: DEF-20 Medium, DEF-23 Low)",
    "Acceptance Test Defects": "0 (11 UAT scripts run on 28/11/2026: 1 Pass, 4 Partial, 6 Not Executed)",
}
obj = next(t for t in d.element.body.iter(qn("w:tbl")) if "Effort Usage" in text_of(t))
done = set()
for tr in obj.findall(qn("w:tr")):
    tcs = tr.findall(qn("w:tc"))
    if text_of(tcs[1]).strip() in ACTUAL:
        set_cell_text(tcs[4], ACTUAL[text_of(tcs[1]).strip()])
        done.add(text_of(tcs[1]).strip())
assert done == set(ACTUAL), set(ACTUAL) - done
log = d.tables[0]._tbl.findall(qn("w:tr"))[-1].findall(qn("w:tc"))
set_cell_text(log[3], text_of(log[3]).strip() + " Test defect rows recounted from Report-5.6 (DEF-01..DEF-23) and the "
              "UAT run of Report-5.5.")
d.save(DOC)
print("saved", DOC.name)
