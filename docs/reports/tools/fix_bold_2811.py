"""Bold labels lost by the 28/11 review edits: set_par_text copies the first run's format, and the first run of a
screen action or job line is its bold label, so the whole rewritten line became bold (Report 3), or the inline bold
of the SDS architecture paragraph was dropped (Report 4). Only one-run paragraphs (the rewritten ones) are touched.

    python fix_bold_2811.py
"""
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import emphasize, text_of  # noqa: E402

R = Path(__file__).resolve().parents[1]
LABELS = ("Track funding", "Confirm clean", "Flag an issue", "Trigger", "Purpose")
d = docx.Document(R / "Report-3_Software_Requirement_Specification.docx")
n = 0
for p in d.element.body.iter(qn("w:p")):
    s = text_of(p)
    if len(p.findall(qn("w:r"))) == 1:
        for lab in LABELS:
            if s.startswith(lab + ":"):
                emphasize(p, [lab])
                n += 1
print("Report 3 labels restored:", n)
d.save(R / "Report-3_Software_Requirement_Specification.docx")

d = docx.Document(R / "Report-4_SDS.docx")
p = next(p for p in d.element.body.iter(qn("w:p")) if text_of(p).startswith("AgriContract is a B2B forward-contract"))
emphasize(p, ["12 business microservices", "database-per-service", "transactional outbox"])
d.save(R / "Report-4_SDS.docx")
print("Report 4 architecture paragraph restored")
