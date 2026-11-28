"""Report 3 Conceptual Data Model (figures/srs/erd_conceptual.drawio), built from the tables in the code.

Entities = business tables of the Flyway migrations of every service (copies across services merged, technical
tables such as outbox / processed_event / idempotency / lock mutex left out, join tables drawn as M:N relationships).
Relationships = the reference columns between those tables. Chen-style diamonds, crow's-foot cardinality.

    python3 docs/reports/tools/srs_conceptual_erd.py
    flatpak run com.jgraph.drawio.desktop -x -f png -s 2 -b 10 -o <png> <drawio>
"""
from pathlib import Path
from xml.sax.saxutils import escape

OUT = Path(__file__).resolve().parents[1] / "figures" / "srs" / "erd_conceptual.drawio"
BW, BH = 200, 50            # entity box
XS, YS = 360, 180           # grid step
X0, Y0 = 30, 30

E = {  # name: (col, row)
    "CATEGORY": (0, 0), "PRODUCT": (1, 0), "LISTING": (2, 0), "PLOT": (3, 0), "PLOT_GEO_RISK_CHECK": (4, 0),
    "REFERENCE_PRICE": (0, 1), "LISTING_RESERVATION": (1, 1), "USER": (3, 1), "AUDIT_RECORD": (4, 1),
    "CONSENT_EVIDENCE": (0, 2), "SIGNATURE": (1, 2), "CONTRACT": (2, 2), "ESCROW_ACCOUNT": (3, 2),
    "DEPOSIT_LEG": (4, 2), "SETTLEMENT_LEG": (5, 2),
    "SELLER_WEIGH_DECLARATION": (1, 3), "MILESTONE": (2, 3), "MILESTONE_FUNDING": (3, 3), "LEDGER_ENTRY": (4, 3),
    "FILE": (1, 4), "BREACH_CASE": (2, 4), "INSPECTION_ASSIGNMENT": (3, 4), "INSPECTION_REPORT": (4, 4),
    "REMEDY_LEG": (5, 4),
    "DISPUTE_SETTLEMENT_PROPOSAL": (1, 5), "REMEDY_DECISION": (2, 5),
    "REPUTATION_LOCK": (2, 6), "REPUTATION_ACTION": (3, 6),
}


def box(n):
    c, r = E[n]
    return X0 + c * XS, Y0 + r * YS


def pt(n, side, f=0.5):
    x, y = box(n)
    return {"t": (x + BW * f, y), "b": (x + BW * f, y + BH), "l": (x, y + BH * f), "r": (x + BW, y + BH * f)}[side]


GAP_Y = lambda r: Y0 + r * YS + BH + (YS - BH) / 2      # corridor between row r and r+1
GAP_X = lambda c: X0 + c * XS + BW + (XS - BW) / 2      # corridor between col c and c+1

ONE, ZERO_ONE, MANY, ONE_MANY = "ERmandOne", "ERzeroToOne", "ERzeroToMany", "ERoneToMany"

# (from, to, verb, card at "from", card at "to", path from->to)
R = [
    ("CATEGORY", "PRODUCT", "classifies", ONE, MANY, [pt("CATEGORY", "r"), pt("PRODUCT", "l")]),
    ("PRODUCT", "LISTING", "is offered via", ONE, MANY, [pt("PRODUCT", "r"), pt("LISTING", "l")]),
    ("LISTING", "PLOT", "is sourced from", MANY, ONE_MANY, [pt("LISTING", "r"), pt("PLOT", "l")]),
    ("PLOT", "PLOT_GEO_RISK_CHECK", "is checked by", ONE, MANY, [pt("PLOT", "r"), pt("PLOT_GEO_RISK_CHECK", "l")]),
    ("CATEGORY", "REFERENCE_PRICE", "is quoted in", ONE, MANY, [pt("CATEGORY", "b"), pt("REFERENCE_PRICE", "t")]),
    ("USER", "PLOT", "owns", ONE, MANY, [pt("USER", "t"), pt("PLOT", "b")]),
    ("USER", "LISTING", "publishes", ONE, MANY,
     [pt("USER", "l"), (pt("LISTING", "b", 0.7)[0], pt("USER", "l")[1]), pt("LISTING", "b", 0.7)]),
    ("LISTING", "LISTING_RESERVATION", "holds", ONE, MANY,
     [pt("LISTING", "b", 0.25), (pt("LISTING", "b", 0.25)[0], pt("LISTING_RESERVATION", "r")[1]),
      pt("LISTING_RESERVATION", "r")]),
    ("LISTING_RESERVATION", "CONTRACT", "reserves stock for", ZERO_ONE, ONE,
     [pt("LISTING_RESERVATION", "b"), (pt("LISTING_RESERVATION", "b")[0], GAP_Y(1)),
      (pt("CONTRACT", "t", 0.25)[0], GAP_Y(1)), pt("CONTRACT", "t", 0.25)]),
    ("USER", "CONTRACT", "is buyer / seller of", ONE_MANY, MANY,
     [pt("USER", "b", 0.3), (pt("USER", "b", 0.3)[0], GAP_Y(1) - 14), (pt("CONTRACT", "t", 0.6)[0], GAP_Y(1) - 14),
      pt("CONTRACT", "t", 0.6)]),
    ("AUDIT_RECORD", "CONTRACT", "records events of", MANY, ZERO_ONE,
     [pt("AUDIT_RECORD", "b", 0.5), (pt("AUDIT_RECORD", "b", 0.5)[0], GAP_Y(1) + 16),
      (pt("CONTRACT", "t", 0.88)[0], GAP_Y(1) + 16), pt("CONTRACT", "t", 0.88)]),
    ("CONTRACT", "SIGNATURE", "is signed with", ONE, MANY, [pt("CONTRACT", "l"), pt("SIGNATURE", "r")]),
    ("SIGNATURE", "CONSENT_EVIDENCE", "is backed by", ONE, ONE, [pt("SIGNATURE", "l"), pt("CONSENT_EVIDENCE", "r")]),
    ("CONTRACT", "ESCROW_ACCOUNT", "is secured by", ONE, ONE, [pt("CONTRACT", "r"), pt("ESCROW_ACCOUNT", "l")]),
    ("ESCROW_ACCOUNT", "DEPOSIT_LEG", "holds", ONE, MANY, [pt("ESCROW_ACCOUNT", "r"), pt("DEPOSIT_LEG", "l")]),
    ("DEPOSIT_LEG", "LEDGER_ENTRY", "is booked as", ONE, ZERO_ONE, [pt("DEPOSIT_LEG", "b"), pt("LEDGER_ENTRY", "t")]),
    ("CONTRACT", "MILESTONE", "is split into", ONE, ONE_MANY, [pt("CONTRACT", "b"), pt("MILESTONE", "t")]),
    ("MILESTONE", "SELLER_WEIGH_DECLARATION", "is weighed in", ONE, MANY,
     [pt("MILESTONE", "l"), pt("SELLER_WEIGH_DECLARATION", "r")]),
    ("MILESTONE", "MILESTONE_FUNDING", "is funded by", ONE, ZERO_ONE, [pt("MILESTONE", "r"), pt("MILESTONE_FUNDING", "l")]),
    ("MILESTONE_FUNDING", "LEDGER_ENTRY", "is locked as", ONE, ZERO_ONE,
     [pt("MILESTONE_FUNDING", "r"), pt("LEDGER_ENTRY", "l")]),
    ("LEDGER_ENTRY", "SETTLEMENT_LEG", "books", ZERO_ONE, ONE,
     [pt("LEDGER_ENTRY", "r", 0.5), (pt("SETTLEMENT_LEG", "b")[0], pt("LEDGER_ENTRY", "r")[1]), pt("SETTLEMENT_LEG", "b")]),
    ("SELLER_WEIGH_DECLARATION", "FILE", "attaches", MANY, ONE_MANY,
     [pt("SELLER_WEIGH_DECLARATION", "b"), pt("FILE", "t")]),
    ("MILESTONE", "FILE", "is evidenced by", MANY, MANY,
     [pt("MILESTONE", "b", 0.2), (pt("MILESTONE", "b", 0.2)[0], GAP_Y(3) + 18), (pt("FILE", "t", 0.85)[0], GAP_Y(3) + 18),
      pt("FILE", "t", 0.85)]),
    ("MILESTONE", "BREACH_CASE", "is disputed in", ZERO_ONE, MANY,
     [pt("MILESTONE", "b", 0.6), pt("BREACH_CASE", "t", 0.6)]),
    ("BREACH_CASE", "INSPECTION_ASSIGNMENT", "requests", ONE, MANY,
     [pt("BREACH_CASE", "r"), pt("INSPECTION_ASSIGNMENT", "l")]),
    ("INSPECTION_ASSIGNMENT", "INSPECTION_REPORT", "produces", ONE, ZERO_ONE,
     [pt("INSPECTION_ASSIGNMENT", "r"), pt("INSPECTION_REPORT", "l")]),
    ("BREACH_CASE", "DISPUTE_SETTLEMENT_PROPOSAL", "is settled by", ONE, MANY,
     [pt("BREACH_CASE", "b", 0.12), (pt("BREACH_CASE", "b", 0.12)[0], GAP_Y(4) + 30),
      (pt("DISPUTE_SETTLEMENT_PROPOSAL", "t")[0], GAP_Y(4) + 30),
      pt("DISPUTE_SETTLEMENT_PROPOSAL", "t")]),
    ("BREACH_CASE", "REMEDY_DECISION", "is resolved by", ONE, ZERO_ONE, [pt("BREACH_CASE", "b"), pt("REMEDY_DECISION", "t")]),
    ("REMEDY_DECISION", "REMEDY_LEG", "consists of", ONE, MANY,
     [pt("REMEDY_DECISION", "r"), (pt("REMEDY_LEG", "b")[0], pt("REMEDY_DECISION", "r")[1]), pt("REMEDY_LEG", "b")]),
    ("REMEDY_LEG", "LEDGER_ENTRY", "is booked as", ONE, ZERO_ONE,
     [pt("REMEDY_LEG", "t", 0.2), (pt("REMEDY_LEG", "t", 0.2)[0], GAP_Y(3)), (pt("LEDGER_ENTRY", "b", 0.75)[0], GAP_Y(3)),
      pt("LEDGER_ENTRY", "b", 0.75)]),
    ("REMEDY_DECISION", "REPUTATION_LOCK", "imposes", ONE, MANY, [pt("REMEDY_DECISION", "b"), pt("REPUTATION_LOCK", "t")]),
    ("REPUTATION_LOCK", "REPUTATION_ACTION", "is reviewed in", ONE, MANY,
     [pt("REPUTATION_LOCK", "r"), pt("REPUTATION_ACTION", "l")]),
]

cells = []
uid = [0]


def nid():
    uid[0] += 1
    return f"c{uid[0]}"


ENT_STYLE = ("rounded=1;whiteSpace=wrap;html=1;arcSize=12;fillColor=#FFFFFF;strokeColor=#000000;"
             "fontFamily=Arial;fontSize=15;")
DIA_STYLE = "rhombus;whiteSpace=wrap;html=1;fillColor=#FFFFFF;strokeColor=#000000;fontFamily=Arial;fontSize=13;"
ids = {}
geo = {}
for n in E:
    x, y = box(n)
    ids[n] = nid()
    geo[ids[n]] = (x, y, BW, BH)
    label = "".join(w.capitalize() for w in n.split("_"))
    cells.append(f'<mxCell id="{ids[n]}" value="{label}" style="{ENT_STYLE}" vertex="1" parent="1">'
                 f'<mxGeometry x="{x}" y="{y}" width="{BW}" height="{BH}" as="geometry"/></mxCell>')


def edge(pts, start, end, src, dst):
    i = nid()
    (sx, sy), (tx, ty) = pts[0], pts[-1]
    mid = "".join(f'<mxPoint x="{x}" y="{y}"/>' for x, y in pts[1:-1])
    arr = f'<Array as="points">{mid}</Array>' if mid else ""

    def rel(cell, x, y):
        cx, cy, w, h = geo[cell]
        return min(1, max(0, (x - cx) / w)), min(1, max(0, (y - cy) / h))
    ex, ey = rel(src, sx, sy)
    nx, ny = rel(dst, tx, ty)
    st = (f"edgeStyle=none;html=1;rounded=0;strokeColor=#000000;strokeWidth=1.3;endSize=12;startSize=12;"
          f"startArrow={start};startFill=0;endArrow={end};endFill=0;exitX={ex:.3f};exitY={ey:.3f};exitDx=0;exitDy=0;"
          f"entryX={nx:.3f};entryY={ny:.3f};entryDx=0;entryDy=0;exitPerimeter=0;entryPerimeter=0;")
    cells.append(f'<mxCell id="{i}" style="{st}" edge="1" parent="1" source="{src}" target="{dst}">'
                 f'<mxGeometry relative="1" as="geometry">{arr}</mxGeometry></mxCell>')


for a, b, verb, ca, cb, pts in R:
    # longest segment hosts the diamond
    segs = [(abs(pts[k + 1][0] - pts[k][0]) + abs(pts[k + 1][1] - pts[k][1]), k) for k in range(len(pts) - 1)]
    _, k = max(segs)
    (x1, y1), (x2, y2) = pts[k], pts[k + 1]
    cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
    dw, dh = max(104, 7.4 * len(verb) + 34), 46
    horiz = abs(y2 - y1) < 1
    d = nid()
    geo[d] = (cx - dw / 2, cy - dh / 2, dw, dh)
    cells.append(f'<mxCell id="{d}" value="{escape(verb)}" style="{DIA_STYLE}" vertex="1" parent="1">'
                 f'<mxGeometry x="{cx - dw / 2}" y="{cy - dh / 2}" width="{dw}" height="{dh}" as="geometry"/></mxCell>')
    sgn = lambda v: (v > 0) - (v < 0)
    if horiz:
        e1 = (cx - sgn(x2 - x1) * dw / 2, cy)
        e2 = (cx + sgn(x2 - x1) * dw / 2, cy)
    else:
        e1 = (cx, cy - sgn(y2 - y1) * dh / 2)
        e2 = (cx, cy + sgn(y2 - y1) * dh / 2)
    edge(pts[:k + 1] + [e1], ca, "none", ids[a], d)
    edge([e2] + pts[k + 1:], "none", cb, d, ids[b])

W = X0 * 2 + 5 * XS + BW
H = Y0 * 2 + 6 * YS + BH
xml = ('<?xml version="1.0" encoding="UTF-8"?>\n<mxfile host="app.diagrams.net"><diagram name="conceptual" id="conceptual">'
       f'<mxGraphModel dx="{W}" dy="{H}" grid="0" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" '
       f'page="0" pageScale="1" pageWidth="{W}" pageHeight="{H}" background="#ffffff" math="0" shadow="0"><root>'
       '<mxCell id="0"/><mxCell id="1" parent="0"/>' + "".join(cells) + '</root></mxGraphModel></diagram></mxfile>\n')
OUT.write_text(xml, encoding="utf-8")
print(OUT, len(E), "entities", len(R), "relationships")
