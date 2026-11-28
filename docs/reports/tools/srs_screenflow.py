"""Report 3 screen flow (figures/srs/screenflow.svg), drawn as a grid with straight/orthogonal arrows.

Replaces the Graphviz screenflow.dot. Public and account screens on top, the main menu bar, then one column per
role; inside a column a child screen is indented under the screen that opens it.

    python3 docs/reports/tools/srs_screenflow.py
    # PNG: render the SVG at 2x (see the PR for the Playwright command)
"""
from pathlib import Path
from xml.sax.saxutils import escape

OUT = Path(__file__).resolve().parents[1] / "figures" / "srs" / "screenflow.svg"
W = 2200
FONT = "Arial, Helvetica, sans-serif"
svg = []


def text_lines(x, y, lines, size=15, anchor="middle"):
    """lines: list of (text, style) with style in '', 'b', 'i'."""
    lh = size * 1.25
    y0 = y - (len(lines) - 1) * lh / 2 + size * 0.35
    for k, (t, st) in enumerate(lines):
        w = ' font-weight="bold"' if st == "b" else ""
        it = ' font-style="italic"' if st == "i" else ""
        svg.append(f'<text x="{x}" y="{y0 + k * lh:.1f}" font-family="{FONT}" font-size="{size}" '
                   f'text-anchor="{anchor}"{w}{it}>{escape(t)}</text>')


def box(x, y, w, h, lines, bold=False, dashed=False, fill="#ffffff"):
    sw = 2.6 if bold else 1.3
    da = ' stroke-dasharray="7 5"' if dashed else ""
    svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="{fill}" stroke="#000" '
               f'stroke-width="{sw}"{da}/>')
    text_lines(x + w / 2, y + h / 2, lines)
    return dict(x=x, y=y, w=w, h=h, cx=x + w / 2, cy=y + h / 2, b=y + h, r=x + w)


def arrow(pts, label=None, lx=None, ly=None, anchor="start"):
    d = "M " + " L ".join(f"{a} {b}" for a, b in pts)
    svg.append(f'<path d="{d}" fill="none" stroke="#333" stroke-width="1.4" marker-end="url(#arr)"/>')
    if label:
        svg.append(f'<text x="{lx}" y="{ly}" font-family="{FONT}" font-size="13" text-anchor="{anchor}" '
                   f'fill="#222">{escape(label)}</text>')


def cluster(x, y, w, h, title, fill, stroke, tx=14):
    svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{fill}" stroke="{stroke}" stroke-width="1.5"/>')
    svg.append(f'<text x="{x + tx}" y="{y + 26}" font-family="{FONT}" font-size="18" font-weight="bold" '
               f'fill="#000">{escape(title)}</text>')


def L(*parts):
    """'text' plain, '*text' bold, '_text' italic."""
    out = []
    for p in parts:
        out.append((p[1:], "b") if p[0] == "*" else (p[1:], "i") if p[0] == "_" else (p, ""))
    return out


# ---- Public / identity provider --------------------------------------------------------------------------
cluster(20, 20, W - 40, 140, "Public / Identity Provider", "#f2f2f2", "#888888")
login = box(40, 76, 150, 56, L("Login"))
forgot = box(270, 76, 200, 56, L("Forgot Password"))
upd = box(550, 76, 230, 56, L("Update Password", "(valid reset link)"))
reg = box(880, 76, 220, 56, L("Register Account", "(Buyer / Seller)"))
browse = box(1260, 76, 220, 56, L("*Browse Listings"), bold=True)
ldetail = box(1550, 76, 220, 56, L("Listing Detail"))
mpb = box(1880, 76, 260, 56, L("*Market Price Board"), bold=True)
arrow([(login["r"], 104), (forgot["x"], 104)])
arrow([(forgot["r"], 104), (upd["x"], 104)], "email link", (forgot["r"] + upd["x"]) / 2, 96, "middle")
arrow([(browse["r"], 104), (ldetail["x"], 104)])
# Update Password -> Login returns along the top of the row.
arrow([(upd["cx"], upd["y"]), (upd["cx"], 60), (login["cx"], 60), (login["cx"], login["y"])],
      "new password set", (upd["cx"] + login["cx"]) / 2, 55, "middle")

# ---- Authenticated account -------------------------------------------------------------------------------
cluster(20, 190, W - 40, 130, "Authenticated account", "#eaf5ea", "#4a9c4a", tx=80)
prof = box(250, 230, 400, 72, L("*My Profile", "(Buyer, Seller, Inspector)", "_Email verification / resend (if applicable)"),
           bold=True)
onb = box(860, 236, 260, 60, L("Organization Onboarding", "(Buyer / Seller)"))
pub = box(1550, 236, 260, 60, L("Public User Profile", "(authenticated Buyer / Seller)"))
arrow([(reg["cx"] + 40, reg["b"]), (reg["cx"] + 40, onb["y"])], "auto sign-in", reg["cx"] + 48, 182)
arrow([(login["cx"] + 40, login["b"]), (login["cx"] + 40, 174), (reg["cx"] - 40, 174), (reg["cx"] - 40, onb["y"])],
      "profile absent", login["cx"] + 48, 168)
arrow([(onb["x"], onb["cy"]), (prof["r"], onb["cy"])], "profile created", (onb["x"] + prof["r"]) / 2, onb["cy"] - 8,
      "middle")
arrow([(ldetail["cx"], ldetail["b"]), (ldetail["cx"], pub["y"])], "sign-in required", ldetail["cx"] + 8, 182)

# ---- Main menu --------------------------------------------------------------------------------------------
menu = box(40, 370, W - 80, 50, L("Main menu (authenticated; items depend on role and eligibility)"),
           fill="#e6d9f0")
arrow([(login["cx"] - 40, login["b"]), (login["cx"] - 40, menu["y"])], "existing profile / staff", login["cx"] - 32, 352)
arrow([(prof["cx"], prof["b"]), (prof["cx"], menu["y"])], "role-based access", prof["cx"] + 8, 352)

# ---- Role columns -----------------------------------------------------------------------------------------
TOP = 470
COLS = [
    ("Contracts & Milestones (Buyer / Seller)", 400, "#eaf5ea", "#4a9c4a", [
        (0, L("*Create Offer (Buyer)", "_sent offer opens Contract Detail"), dict(bold=True)),
        (0, L("*My Contracts"), dict(bold=True)),
        (1, L("Contract Detail"), {}),
        (2, L("Edit Contract Terms"), {}),
        (2, L("Sign Contract"), {}),
        (3, L("OTP Verification"), {}),
        (2, L("Milestone Detail"), {}),
        (3, L("Record Weighing (Seller)"), {}),
        (3, L("Record Receipt (Buyer)"), {}),
        (3, L("Respond to Flag (Seller)"), {}),
        (3, L("Claim Force Majeure (Seller)"), {}),
        (3, L("Milestone Price Adjustment"), {}),
        (3, L("Dispute Exit"), {}),
        (2, L("Propose Replacement Contract"), {}),
        (2, L("Export Escrow Statement"), {}),
        (2, L("Report Breach Dialog"), dict(dashed=True)),
        (3, L("Breach Case Detail (Party View)", "_Terminate Contract"), {}),
    ]),
    ("Catalog (Seller)", 330, "#eaf5ea", "#4a9c4a", [
        (0, L("*My Plots (Seller)", "_Import KML · Add Plot by GPS Pin", "_Upload Cadastral Extract",
              "_Request Geo Re-evaluation"), dict(bold=True)),
        (0, L("*My Products (Seller)"), dict(bold=True)),
        (0, L("*My Listings (Seller)"), dict(bold=True)),
        (1, L("Listing Form (Seller)"), {}),
    ]),
    ("Inspector", 300, "#fff6de", "#c9971f", [
        (0, L("*Assigned Cases"), dict(bold=True)),
        (1, L("Submit Inspection Result"), {}),
    ]),
    ("Operator", 330, "#e4edfa", "#3063a8", [
        (0, L("*KYC Review Queue", "(email verified; pending)"), dict(bold=True)),
        (1, L("KYC Detail", "_Verify KYC · Reject KYC"), {}),
        (0, L("*Plot Risk Review Queue"), dict(bold=True)),
        (0, L("*Category & Listing Moderation"), dict(bold=True)),
        (0, L("*Manual Price Entry"), dict(bold=True)),
        (0, L("*Reputation Action Requests", "_Propose Reputation Action"), dict(bold=True)),
        (0, L("*Level 2 Commissions"), dict(bold=True)),
        (0, L("*Pending External Report", "*Review"), dict(bold=True)),
        (0, L("*Manual External Report", "*Upload"), dict(bold=True)),
        (0, L("*Email Intake Failures"), dict(bold=True)),
        (0, L("*Activation Failures"), dict(bold=True)),
        (0, L("*Milestone Funding Failures"), dict(bold=True)),
    ]),
    ("Arbitrator", 330, "#efe3f5", "#9673A6", [
        (0, L("*Breach Case Queue"), dict(bold=True)),
        (1, L("Breach Case Detail (Arbitrator)", "_Request Inspection · Resolve", "_Breach Case · Terminate Contract"), {}),
        (0, L("*Force Majeure Review Queue"), dict(bold=True)),
        (1, L("Force Majeure Claim Detail"), {}),
        (0, L("*Reputation Action Requests", "_Approve / Reject"), dict(bold=True)),
    ]),
    ("Admin", 300, "#fbe7e7", "#b33a3a", [
        (0, L("*Audit Chain", "*Verification Status"), dict(bold=True)),
        (0, L("*Contract Evidence Export"), dict(bold=True)),
        (0, L("*Security Lock Status"), dict(bold=True)),
        (0, L("*Termination Report", "*by Commodity"), dict(bold=True)),
        (0, L("*Escrow Effectiveness Report"), dict(bold=True)),
        (0, L("*Force Majeure Trends"), dict(bold=True)),
    ]),
]
GAP = 22
INDENT = 30
x = 20 + (W - 40 - sum(c[1] for c in COLS) - GAP * (len(COLS) - 1)) / 2
bottoms = []
for title, cw, fill, stroke, items in COLS:
    # height first, so the cluster is drawn under the boxes
    y = TOP + 48
    layout = []
    for lvl, lines, kw in items:
        h = 22 + 19 * len(lines)
        layout.append((lvl, lines, kw, y, h))
        y += h + 16
    height = y - TOP + 6
    bottoms.append(TOP + height)
    cluster(x, TOP, cw, height, title, fill, stroke)
    arrow([(x + cw / 2, menu["b"]), (x + cw / 2, TOP)])
    parents = {}
    for lvl, lines, kw, by, h in layout:
        bx = x + 14 + lvl * INDENT
        b = box(bx, by, cw - 28 - lvl * INDENT, h, lines, **kw)
        if lvl > 0:
            p = parents[lvl - 1]
            sx = p["x"] + 15
            arrow([(sx, p["b"]), (sx, b["cy"]), (b["x"], b["cy"])])
        parents[lvl] = b
    x += cw + GAP

# ---- Legend -----------------------------------------------------------------------------------------------
H = max(bottoms) + 20
ly = H + 10
box(40, ly, 230, 40, L("*Menu screen"), bold=True)
box(290, ly, 230, 40, L("Screen opened from another"))
box(540, ly, 230, 40, L("Dialog"), dashed=True)
H = ly + 60

head = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'
        '<defs><marker id="arr" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="9" markerHeight="9" '
        'orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="#333"/></marker></defs>'
        f'<rect width="{W}" height="{H}" fill="#ffffff"/>')
OUT.write_text(head + "".join(svg) + "</svg>\n", encoding="utf-8")
print(OUT, W, H)
