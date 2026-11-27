"""Minimal grayscale wireframe renderer for the SRS Part III screen mockups.

Each screen is a list of blocks stacked in the content area of a shell:
  app     - signed-in layout of the real frontend: left sidebar (org + menu), top bar, breadcrumb
  public  - guest layout: top navigation + footer
  auth    - centered card (Keycloak login / register / password pages)
A dialog screen draws its base shell dimmed and a modal on top.
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

S = 2  # render scale, PNG is 2x for the Word report
FONT_DIR = Path("C:/Windows/Fonts")
INK, MUTED, LINE, FILL, SOFT, WHITE = "#222222", "#707070", "#9a9a9a", "#e6e6e6", "#f4f4f4", "#ffffff"


# Segoe UI where it exists (Windows); elsewhere Open Sans, the closest free face
FALLBACK_DIR = Path("/usr/share/fonts/open-sans")


def font(size, bold=False):
    segoe = FONT_DIR / ("segoeuib.ttf" if bold else "segoeui.ttf")
    path = segoe if segoe.exists() else FALLBACK_DIR / ("OpenSans-Bold.ttf" if bold else "OpenSans-Regular.ttf")
    return ImageFont.truetype(str(path), size * S)


F = {k: font(*v) for k, v in {"t": (20, True), "h": (14, True), "b": (12, False), "bb": (12, True),
                               "s": (10, False), "logo": (16, True)}.items()}

NAV = {
    "BUYER": ["My Contracts", "Marketplace", "Market Prices", "Escrow Statement", "Notifications", "Profile"],
    "SELLER": ["My Contracts", "My Plots", "My Products", "My Listings", "Marketplace", "Escrow Statement",
               "Notifications", "Profile"],
    "OPERATOR": ["KYC Review", "Reputation Requests", "Plot Risk Review", "Moderation", "Manual Prices",
                 "Activation Failures", "Funding Failures", "Level 2 Reports", "Email Intake", "Profile"],
    "ARBITRATOR": ["Breach Cases", "Force Majeure", "Level 2 Commissions", "Reputation Requests", "Profile"],
    "INSPECTOR": ["Assigned Cases", "Profile"],
    "ADMIN": ["Audit Chain", "Evidence Export", "Security Lock", "Analytics", "Profile"],
}
ORG = {"BUYER": "Mekong Export JSC", "SELLER": "Ea Kar Coffee Coop", "OPERATOR": "AgriContract Operations",
       "ARBITRATOR": "AgriContract Arbitration", "INSPECTOR": "Level 1.5 Inspector", "ADMIN": "AgriContract Admin"}


class Canvas:
    def __init__(self, w, h):
        self.img = Image.new("RGB", (w * S, h * S), WHITE)
        self.d = ImageDraw.Draw(self.img)

    def rect(self, x, y, w, h, fill=WHITE, line=LINE, r=6, width=1):
        self.d.rounded_rectangle([x * S, y * S, (x + w) * S, (y + h) * S], r * S, fill=fill,
                                 outline=line, width=width * S)

    def text(self, x, y, s, f="b", fill=INK, anchor="la"):
        self.d.text((x * S, y * S), s, font=F[f], fill=fill, anchor=anchor)

    def line(self, x0, y0, x1, y1, fill=LINE, width=1):
        self.d.line([x0 * S, y0 * S, x1 * S, y1 * S], fill=fill, width=width * S)

    def tw(self, s, f="b"):
        return self.d.textlength(s, font=F[f]) / S

    def button(self, x, y, label, primary=False, disabled=False, h=28):
        w = self.tw(label, "bb") + 24
        fill = INK if primary else (SOFT if disabled else WHITE)
        self.rect(x, y, w, h, fill=fill, line=LINE if disabled else INK, r=5)
        self.text(x + w / 2, y + h / 2, label, "bb", WHITE if primary else (LINE if disabled else INK), "mm")
        return w

    def badge(self, x, y, label, anchor_right=False):
        w = self.tw(label, "s") + 14
        if anchor_right:
            x -= w
        self.rect(x, y, w, 18, fill=FILL, line=LINE, r=9)
        self.text(x + w / 2, y + 9, label, "s", INK, "mm")
        return w

    def wrap(self, s, width, f="b"):
        out = []
        for para in s.split("\n"):
            cur = ""
            for word in para.split(" "):
                t = (cur + " " + word).strip()
                if self.tw(t, f) > width and cur:
                    out.append(cur)
                    cur = word
                else:
                    cur = t
            out.append(cur)
        return out


# ---------------------------------------------------------------- blocks
# Every block renderer takes (c, x, y, w, *args) and returns the height it used.

def b_crumb(c, x, y, w, s):
    c.text(x, y, s, "s", MUTED)
    return 20


def b_title(c, x, y, w, s, badge=None, buttons=()):
    c.text(x, y, s, "t")
    if badge:
        c.badge(x + c.tw(s, "t") + 12, y + 6, badge)
    bx = x + w
    for i, lb in enumerate(reversed(buttons)):
        bw = c.tw(lb.strip("*~"), "bb") + 24
        bx -= bw
        c.button(bx, y, lb.strip("*~"), primary=lb.startswith("*"), disabled=lb.startswith("~"))
        bx -= 8
    return 40


def b_text(c, x, y, w, s, f="b", fill=MUTED):
    lines = c.wrap(s, w, f)
    for i, ln in enumerate(lines):
        c.text(x, y + i * 18, ln, f, fill)
    return len(lines) * 18 + 6


def b_banner(c, x, y, w, s):
    lines = c.wrap(s, w - 40)
    h = len(lines) * 18 + 16
    c.rect(x, y, w, h, fill=SOFT, line=LINE)
    c.text(x + 12, y + 8, "(i)", "bb", MUTED)
    for i, ln in enumerate(lines):
        c.text(x + 36, y + 8 + i * 18, ln, "b", INK)
    return h + 12


def b_tabs(c, x, y, w, tabs, active=0):
    cx = x
    for i, t in enumerate(tabs):
        tw = c.tw(t, "bb")
        c.text(cx, y, t, "bb" if i == active else "b", INK if i == active else MUTED)
        if i == active:
            c.line(cx, y + 22, cx + tw, y + 22, INK, 2)
        cx += tw + 28
    c.line(x, y + 24, x + w, y + 24)
    return 36


def b_filters(c, x, y, w, items, button=None):
    n = len(items)
    gap = 10
    bw = (c.tw(button, "bb") + 34) if button else 0
    fw = (w - bw - gap * (n - 1)) / n
    for i, it in enumerate(items):
        fx = x + i * (fw + gap)
        c.rect(fx, y, fw, 30, r=5)
        c.text(fx + 10, y + 15, it, "b", MUTED, "lm")
        if it.endswith("v"):
            pass
    if button:
        c.button(x + w - bw + 10, y + 1, button)
    return 42


def b_table(c, x, y, w, headers, rows, actions=None):
    n = len(headers)
    colw = w / n
    rh = 30
    h = rh * (len(rows) + 1)
    c.rect(x, y, w, h, r=4)
    c.d.rectangle([x * S + S, y * S + S, (x + w) * S - S, (y + rh) * S], fill=FILL)
    for j, hd in enumerate(headers):
        c.text(x + 10 + j * colw, y + rh / 2, hd, "bb", INK, "lm")
    for i, row in enumerate(rows):
        ry = y + rh * (i + 1)
        c.line(x, ry, x + w, ry)
        for j, cell in enumerate(row):
            cx = x + 10 + j * colw
            if isinstance(cell, str) and cell.startswith("[") and cell.endswith("]"):
                c.badge(cx, ry + 6, cell[1:-1])
            elif isinstance(cell, str) and cell.startswith("<") and cell.endswith(">"):
                c.button(cx, ry + 4, cell[1:-1], h=22)
            else:
                c.text(cx, ry + rh / 2, str(cell), "b", INK, "lm")
    return h + 8 + b_pager(c, x, y + h + 4, w)


def b_pager(c, x, y, w):
    c.text(x, y + 6, "Page 1 / 1", "s", MUTED)
    c.text(x + w, y + 6, "<   >", "bb", MUTED, "ra")
    return 24


def b_fields(c, x, y, w, fields, cols=2):
    """fields: (label, value) pairs; value '' = empty input, 'v:' prefix = select, 'ta:' = textarea."""
    gap = 16
    fw = (w - gap * (cols - 1)) / cols
    yy, col, hmax = y, 0, 0
    for label, val in fields:
        full = label.startswith("!")  # '!' label = full-width row
        if full:
            label = label[1:]
        if col and (full or col >= cols):
            yy, col, hmax = yy + hmax, 0, 0
        fx = x + col * (fw + gap)
        span = w if full else fw
        c.text(fx, yy, label, "s", MUTED)
        ih = 56 if val.startswith("ta:") else 30
        c.rect(fx, yy + 16, span, ih, r=5)
        v = val.replace("ta:", "").replace("v:", "")
        c.text(fx + 10, yy + 16 + (14 if ih > 30 else 15), v, "b", INK if v else MUTED, "lm")
        if val.startswith("v:"):
            c.text(fx + span - 14, yy + 16 + 15, "v", "b", MUTED, "mm")
        hmax = max(hmax, ih + 28)
        col = cols if full else col + 1
    return yy - y + hmax


def b_card(c, x, y, w, title, body, badge=None):
    """body: list of blocks (tuples) drawn inside the card, or a list of 'k: v' / plain strings."""
    pad = 14
    top = y + pad
    h =_measure(c, w - 2 * pad, body) + (28 if title else 0) + 2 * pad - 6
    c.rect(x, y, w, h)
    if title:
        c.text(x + pad, top, title, "h")
        if badge:
            c.badge(x + w - pad, top, badge, anchor_right=True)
        top += 28
    _draw(c, x + pad, top, w - 2 * pad, body)
    return h + 12


def b_cols(c, x, y, w, columns, ratios=None):
    ratios = ratios or [1] * len(columns)
    gap = 14
    tot = sum(ratios)
    avail = w - gap * (len(columns) - 1)
    cx = x
    hmax = 0
    for col, r in zip(columns, ratios):
        cw = avail * r / tot
        hmax = max(hmax, _draw(c, cx, y, cw, col))
        cx += cw + gap
    return hmax


def b_kv(c, x, y, w, pairs, cols=1):
    gap = 20
    cw = (w - gap * (cols - 1)) / cols
    rows = (len(pairs) + cols - 1) // cols
    for i, (k, v) in enumerate(pairs):
        cx = x + (i % cols) * (cw + gap)
        ry = y + (i // cols) * 24
        c.text(cx, ry, k, "b", MUTED)
        if isinstance(v, str) and v.startswith("["):
            c.badge(cx + cw, ry - 1, v[1:-1], anchor_right=True)
        else:
            c.text(cx + cw, ry, v, "bb", INK, "ra")
        c.line(cx, ry + 20, cx + cw, ry + 20, FILL)
    return rows * 24 + 4


def b_buttons(c, x, y, w, labels, align="left"):
    """'*x' primary, '~x' disabled."""
    total = sum(c.tw(l.strip("*~"), "bb") + 24 for l in labels) + 8 * (len(labels) - 1)
    bx = x + w - total if align == "right" else x
    for lb in labels:
        bx += c.button(bx, y, lb.strip("*~"), primary=lb.startswith("*"), disabled=lb.startswith("~")) + 8
    return 40


def b_upload(c, x, y, w, label, hint="JPG, PNG or PDF, up to 10 MB"):
    h = 64
    c.d.rounded_rectangle([x * S, y * S, (x + w) * S, (y + h) * S], 6 * S, fill=SOFT, outline=LINE, width=S)
    c.text(x + w / 2, y + 22, "[+]  " + label, "bb", INK, "mm")
    c.text(x + w / 2, y + 44, hint, "s", MUTED, "mm")
    return h + 12


def b_placeholder(c, x, y, w, h, label):
    c.rect(x, y, w, h, fill=SOFT, r=4)
    c.line(x, y, x + w, y + h, FILL, 1)
    c.line(x, y + h, x + w, y, FILL, 1)
    tw = c.tw(label, "bb") + 16
    c.rect(x + w / 2 - tw / 2, y + h / 2 - 12, tw, 24, fill=WHITE, r=4)
    c.text(x + w / 2, y + h / 2, label, "bb", MUTED, "mm")
    return h + 12


def b_bars(c, x, y, w, h, labels, values, title=""):
    c.rect(x, y, w, h, r=4)
    n = len(values)
    mx = max(values)
    bw = (w - 40) / n * 0.55
    for i, (lb, v) in enumerate(zip(labels, values)):
        bx = x + 20 + i * (w - 40) / n + ((w - 40) / n - bw) / 2
        bh = (h - 60) * v / mx
        c.d.rectangle([bx * S, (y + h - 30 - bh) * S, (bx + bw) * S, (y + h - 30) * S], fill="#b5b5b5")
        c.text(bx + bw / 2, y + h - 22, lb, "s", MUTED, "mt")
    c.line(x + 16, y + h - 30, x + w - 16, y + h - 30, LINE)
    if title:
        c.text(x + 12, y + 10, title, "s", MUTED)
    return h + 12


def b_steps(c, x, y, w, steps, active=0):
    n = len(steps)
    for i, s in enumerate(steps):
        cx = x + (w - 120) * i / max(n - 1, 1) + 60
        if i < n - 1:
            c.line(cx + 12, y + 10, x + (w - 120) * (i + 1) / (n - 1) + 60 - 12, y + 10, LINE, 2)
        filled = i <= active
        c.d.ellipse([(cx - 10) * S, y * S, (cx + 10) * S, (y + 20) * S], fill=INK if filled else WHITE,
                    outline=INK, width=S)
        c.text(cx, y + 10, str(i + 1), "s", WHITE if filled else INK, "mm")
        c.text(cx, y + 28, s, "s", INK if i == active else MUTED, "mt")
    return 54


def b_timeline(c, x, y, w, items, done=0):
    for i, (t, sub) in enumerate(items):
        yy = y + i * 40
        if i < len(items) - 1:
            c.line(x + 6, yy + 14, x + 6, yy + 40, FILL, 2)
        c.d.ellipse([x * S, (yy + 2) * S, (x + 12) * S, (yy + 14) * S], fill=INK if i < done else WHITE,
                    outline=INK, width=S)
        c.text(x + 22, yy, t, "bb" if i == done else "b", INK)
        c.text(x + 22, yy + 17, sub, "s", MUTED)
    return len(items) * 40


def b_check(c, x, y, w, label, checked=False):
    c.rect(x, y + 2, 14, 14, r=3, line=INK)
    if checked:
        c.text(x + 7, y + 9, "x", "bb", INK, "mm")
    lines = c.wrap(label, w - 24)
    for i, ln in enumerate(lines):
        c.text(x + 22, y + i * 18, ln, "b", INK)
    return len(lines) * 18 + 8


def b_radio(c, x, y, w, options, sel=0):
    cx = x
    for i, o in enumerate(options):
        c.d.ellipse([cx * S, (y + 2) * S, (cx + 14) * S, (y + 16) * S], outline=INK, width=S, fill=WHITE)
        if i == sel:
            c.d.ellipse([(cx + 4) * S, (y + 6) * S, (cx + 10) * S, (y + 12) * S], fill=INK)
        c.text(cx + 20, y, o, "b", INK)
        cx += c.tw(o) + 44
    return 28


def b_otp(c, x, y, w, n=6):
    for i in range(n):
        c.rect(x + i * 46, y, 38, 44, r=5)
    return 56


def b_space(c, x, y, w, h=8):
    return h


def b_divider(c, x, y, w):
    c.line(x, y + 6, x + w, y + 6, FILL)
    return 14


BLOCKS = {k[2:]: v for k, v in globals().items() if k.startswith("b_")}


def _draw(c, x, y, w, blocks):
    yy = y
    for blk in blocks:
        if isinstance(blk, str):
            if ": " in blk and not blk.startswith("-"):
                k, v = blk.split(": ", 1)
                yy += b_kv(c, x, yy, w, [(k, v)])
            else:
                yy += b_text(c, x, yy, w, blk.lstrip("- "), fill=INK)
            continue
        name, *args = blk
        yy += BLOCKS[name](c, x, yy, w, *args)
    return yy - y


def _measure(c, w, blocks):
    scratch = Canvas(int(w) + 10, 4000)
    return _draw(scratch, 0, 0, w, blocks)


# ---------------------------------------------------------------- shells

def _app_shell(c, W, H, role, active, crumb):
    c.d.rectangle([0, 0, W * S, H * S], fill="#fafafa")
    c.rect(16, 16, 200, H - 32, r=8)
    c.text(32, 32, ORG[role], "bb")
    c.text(32, 50, role, "s", MUTED)
    for i, item in enumerate(NAV[role]):
        yy = 80 + i * 38
        if item == active:
            c.d.rectangle([17 * S, yy * S, 215 * S, (yy + 34) * S], fill=FILL)
            c.line(17, yy, 17, yy + 34, INK, 3)
        c.text(36, yy + 17, item, "bb" if item == active else "b", INK, "lm")
    c.rect(232, 16, W - 248, 48, r=8)
    c.rect(246, 26, 240, 28, r=5)
    c.text(256, 40, "Search contracts", "b", MUTED, "lm")
    name_x = W - 112
    c.text(name_x, 40, ORG[role], "b", INK, "rm")
    c.text(name_x - c.tw(ORG[role]) - 16, 40, "( ! )", "b", MUTED, "rm")
    c.button(W - 100, 26, "Log out")
    c.text(W - 30, 40, "", "b")
    return 232, 80, W - 248  # content x, y, w


def _public_shell(c, W, H, active):
    c.d.rectangle([0, 0, W * S, H * S], fill="#fafafa")
    c.d.rectangle([0, 0, W * S, 56 * S], fill=WHITE)
    c.line(0, 56, W, 56)
    c.text(32, 28, "AgriContract", "logo", INK, "lm")
    x = 190
    for item in ["Marketplace", "Market Prices", "How it works", "About escrow"]:
        c.text(x, 28, item, "bb" if item == active else "b", INK if item == active else MUTED, "lm")
        if item == active:
            c.line(x, 50, x + c.tw(item, "bb"), 50, INK, 2)
        x += c.tw(item, "bb") + 28
    c.text(W - 120, 28, "Log in", "b", INK, "lm")
    c.button(W - 70, 14, "Register")
    return 120, 80, W - 240


def render(spec: dict, out: Path):
    W = spec.get("w", 1200)
    kind = spec["shell"]
    blocks = spec.get("blocks", [])
    probe = Canvas(W, 4000)
    if kind == "auth":
        cw = 440
        content_h = _draw(probe, 0, 0, cw - 64, blocks)
        H = max(560, content_h + 220)
        c = Canvas(W, H)
        c.d.rectangle([0, 0, W * S, H * S], fill="#fafafa")
        x0, y0 = (W - cw) / 2, (H - content_h - 100) / 2
        c.rect(x0, y0, cw, content_h + 100, r=10)
        c.text(x0 + 32, y0 + 30, "AgriContract", "logo")
        _draw(c, x0 + 32, y0 + 70, cw - 64, blocks)
    else:
        base = spec.get("base", [])
        dialog = spec.get("dialog")
        if kind == "app":
            cx, cy, cw = 232, 80, W - 248
        else:
            cx, cy, cw = 120, 80, W - 240
        body = ([("crumb", spec["crumb"])] if spec.get("crumb") else []) + (base if dialog else blocks)
        content_h = _draw(probe, cx, cy, cw, body)
        dh = 0
        if dialog:
            dw = dialog.get("w", 560)
            dh = _draw(probe, 0, 0, dw - 48, dialog["blocks"]) + 110
        H = max(720, cy + content_h + 40, dh + 120)
        if kind == "public":
            H += 120
        c = Canvas(W, H)
        if kind == "app":
            _app_shell(c, W, H, spec["role"], spec.get("active"), spec.get("crumb"))
        else:
            _public_shell(c, W, H, spec.get("active"))
            c.d.rectangle([0, (H - 110) * S, W * S, H * S], fill="#d9d9d9")
            c.text(120, H - 80, "AgriContract", "bb")
            c.text(120, H - 58, "Coffee trade contracts with escrow  ·  Marketplace · Prices · Escrow · Disputes · EUDR",
                   "s", MUTED)
        _draw(c, cx, cy, cw, body)
        if dialog:
            ov = Image.new("RGBA", c.img.size, (0, 0, 0, 90))
            c.img = Image.alpha_composite(c.img.convert("RGBA"), ov).convert("RGB")
            c.d = ImageDraw.Draw(c.img)
            dw = dialog.get("w", 560)
            dx, dy = (W - dw) / 2, max(60, (H - dh) / 2)
            c.rect(dx, dy, dw, dh, r=10, line=INK)
            c.text(dx + 24, dy + 22, dialog["title"], "h")
            c.text(dx + dw - 24, dy + 22, "X", "bb", MUTED, "ra")
            c.line(dx, dy + 52, dx + dw, dy + 52, FILL)
            used = _draw(c, dx + 24, dy + 66, dw - 48, dialog["blocks"])
            c.line(dx, dy + 66 + used + 4, dx + dw, dy + 66 + used + 4, FILL)
            b_buttons(c, dx + 24, dy + 66 + used + 14, dw - 48, dialog.get("buttons", ["Cancel", "*Confirm"]),
                      align="right")
    out.parent.mkdir(parents=True, exist_ok=True)
    c.img.save(out, optimize=True)
