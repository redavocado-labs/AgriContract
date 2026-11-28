"""SRS (Report-3) Part III: the 64 screen mockups replaced by the wireframes in figures/srs/screens/sNN_*.png.

Screen N of the I.5.1 Screen List is the N-th Heading 4 of Part III (1.1.1 Register Account ... 9.1.3 Force Majeure
Trends). The first picture under each heading points at the new wireframe (new image part, so screens that shared one
picture, 2.1.1/2.1.2 and 7.2.1/7.2.2, now have their own); any other picture under the same heading is removed.
All pictures are full text width. Content only; paragraph formatting and the change log row formatting are kept.
"""
from pathlib import Path

import docx
from docx.oxml.ns import qn

from docxedit import append_row, find, next_table, replace_image, text_of

REPO = Path(__file__).resolve().parents[3]
DOC = REPO / "docs/reports/Report-3_Software_Requirement_Specification.docx"
SCREENS = REPO / "docs/reports/figures/srs/screens"
d = docx.Document(DOC)

body = list(d.element.body.iterchildren())
start = next(i for i, el in enumerate(body)
             if el.tag == qn("w:p") and text_of(el).strip() == "III. Functional Requirements"
             and "Heading" in (el.find(f"{qn('w:pPr')}/{qn('w:pStyle')}").get(qn("w:val")) if el.find(
                 f"{qn('w:pPr')}/{qn('w:pStyle')}") is not None else ""))


def style(el):
    ps = el.find(f"{qn('w:pPr')}/{qn('w:pStyle')}") if el.tag == qn("w:p") else None
    return ps.get(qn("w:val")) if ps is not None else ""


def heading_level(el):
    s = style(el)
    if not text_of(el).strip():
        return None
    for lvl in ("1", "2", "3", "4"):
        if s in (f"Heading{lvl}", f"heading {lvl}", f"Heading {lvl}"):
            return int(lvl)
    return None


# group the body of Part III by its Heading 4 screens
groups, cur = [], None
for el in body[start + 1:]:
    lvl = heading_level(el)
    if lvl is not None:
        if text_of(el).strip().startswith("10. "):
            break
        if lvl == 4:
            cur = (text_of(el).strip(), [])
            groups.append(cur)
            continue
    if cur is not None and el.tag == qn("w:p"):
        cur[1].append(el)
assert len(groups) == 65, f"expected 65 screens, found {len(groups)}"

files = sorted(SCREENS.glob("s[0-9][0-9]_*.png"))
assert len(files) == 65, len(files)
for (title, pars), png in zip(groups, files):
    pics = [p for p in pars if p.find(f".//{qn('w:drawing')}") is not None]
    if not pics:
        raise SystemExit(f"{title}: no picture to replace")
    first = pics[0]
    # several pictures in the first paragraph (5.1.1): keep the first run's drawing only
    runs = [r for r in first.findall(qn("w:r")) if r.find(qn("w:drawing")) is not None]
    for r in runs[1:]:
        first.remove(r)
    w, h = replace_image(d, first, png, full_width=True)
    for p in pics[1:]:
        for r in [r for r in p.findall(qn("w:r")) if r.find(qn("w:drawing")) is not None]:
            p.remove(r)
    print(f"{png.name:45s} -> {title}  ({w:.1f} x {h:.1f} cm, removed {len(runs) - 1 + len(pics) - 1})")

append_row(next_table(find(d, "Change Log")), [
    "27/11/2026", "M", "Group 6",
    "III Functional Requirements: the 64 screen mockups redrawn as wireframes from the running frontend "
    "(screens not built yet follow the screen description); each screen now has its own picture "
    "(source and screenshots in docs/reports/figures/srs/screens).",
])
# drop image parts no longer referenced by the document body (the old mockups)
used = {b.get(qn("r:embed")) for b in d.element.body.iter(qn("a:blip"))}
for rid, rel in list(d.part.rels.items()):
    if rel.reltype.endswith("/image") and rid not in used:
        d.part.drop_rel(rid)
d.save(DOC)
print("saved")
