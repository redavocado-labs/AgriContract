"""Report 3 BF-03 and BF-04: the embedded pictures are swapped for the new exports of bf03.drawio and bf04.drawio
(decision numbers D37, D67, D85/D91 replaced by what they mean). The new PNG has the same pixel size as the old one,
so the picture part is replaced in place and the frame stays. Run before copying the exports over the repo PNGs.

    python srs_bf_labels_2811.py <dir with the new bf03.png and bf04.png>
"""
import sys
from pathlib import Path

import docx

SRC = Path(__file__).resolve().parents[1] / "figures" / "srs"
DOC = Path(__file__).resolve().parents[1] / "Report-3_Software_Requirement_Specification.docx"
new_dir = Path(sys.argv[1])
d = docx.Document(DOC)
for name in ("bf03", "bf04"):
    old = (SRC / f"{name}.png").read_bytes()
    parts = [r.target_part for r in d.part.rels.values() if "image" in r.reltype and r.target_part.blob == old]
    assert len(parts) == 1, (name, len(parts))
    parts[0]._blob = (new_dir / f"{name}.png").read_bytes()
d.save(DOC)
print("saved", DOC.name)
