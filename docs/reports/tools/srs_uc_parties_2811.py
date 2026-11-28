"""Report 3 4.3.4 Contract Parties use case diagram: UC54 Terminate Contract added (the aggrieved Buyer or Seller
starts it, UC54 in 4.2), redrawn by figures/srs/uc_diagrams.py. The picture is found by the bytes of the old PNG and
re-pointed with docxedit.replace_image, which keeps the frame width and takes the new aspect ratio.

    python srs_uc_parties_2811.py <old uc_parties.png>
"""
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import replace_image  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "Report-3_Software_Requirement_Specification.docx"
old = Path(sys.argv[1]).read_bytes()
d = docx.Document(DOC)
hits = []
for p in d.element.body.iter(qn("w:p")):
    for blip in p.iter(qn("a:blip")):
        if d.part.related_parts[blip.get(qn("r:embed"))].blob == old:
            hits.append(p)
assert len(hits) == 1, len(hits)
print(replace_image(d, hits[0], ROOT / "figures/srs/uc_parties.png"))
d.save(DOC)
print("saved", DOC.name)
