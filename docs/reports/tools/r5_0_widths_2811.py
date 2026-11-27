"""Report 5.0 II.3 tables: number columns wide enough for 4-digit counts (they wrapped one digit per line), Note
column narrower. Grid and every cell width set in dxa; total width unchanged.

    python r5_0_widths_2811.py
"""
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import text_of  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-5.0_Test Documentation.docx"
d = docx.Document(DOC)
WIDTHS = {"RoundMetrics": [1250, 1000, 820, 640, 600, 600, 760, 3397],
          "MetricReview": [2050, 900, 600, 600, 600, 600, 700, 3017],
          "ProductReview": [2050, 900, 600, 600, 600, 600, 700, 3017]}
for t in d.element.body.iter(qn("w:tbl")):
    key = next((k for k in WIDTHS if text_of(t).startswith(k)), None)
    if not key:
        continue
    w = WIDTHS[key]
    for g, v in zip(t.find(qn("w:tblGrid")).findall(qn("w:gridCol")), w):
        g.set(qn("w:w"), str(v))
    for tr in t.findall(qn("w:tr")):
        for tc, v in zip(tr.findall(qn("w:tc")), w):
            tcw = tc.find(qn("w:tcPr")).find(qn("w:tcW"))
            tcw.set(qn("w:w"), str(v))
            tcw.set(qn("w:type"), "dxa")
d.save(DOC)
print("saved", DOC.name)
