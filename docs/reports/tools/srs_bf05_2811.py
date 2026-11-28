"""Report 3 BF-05: step 10 says the final decision applies its money and reputation effects (a no-fault decision
applies none), re-embedded from figures/srs/bf05.png. Content only.

    python srs_bf05_2811.py
"""
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import replace_image, text_of  # noqa: E402

R = Path(__file__).resolve().parents[1]
DOC = R / "Report-3_Software Requirement Specification.docx"
d = docx.Document(DOC)
for el in d.element.body.iterchildren():
    ps = el.find(f"{qn('w:pPr')}/{qn('w:pStyle')}") if el.tag == qn("w:p") else None
    if ps is not None and ps.get(qn("w:val")).lower().startswith("heading") and text_of(el).startswith("2.5 "):
        n = el.getnext()
        while not list(n.iter(qn("a:blip"))):
            n = n.getnext()
        replace_image(d, n, R / "figures/srs/bf05.png")
        break
else:
    raise SystemExit("BF-05 heading not found")
used = {b.get(qn("r:embed")) for b in d.element.body.iter(qn("a:blip"))}
for rid, rel in list(d.part.rels.items()):
    if rel.reltype.endswith("/image") and rid not in used:
        d.part.drop_rel(rid)
d.save(DOC)
print("saved")
