# Writes cell values straight into the sheet XML of an .xlsx, so a report workbook can be updated on any OS without
# Excel (the fill_* / update_51_* scripts drive Excel through COM and need Windows). Only the cells named are touched;
# charts, images, comments, styles and every other part of the package are copied byte for byte, which openpyxl does
# not do (it drops charts and images on save). A written cell keeps its style; strings are stored inline. The
# workbook is flagged to recalculate on open, so COUNTIF totals of the statistics sheets follow the new values.
#
#   book = Book("Report-5.3_System_Test_FRs.xlsx")
#   book.set("Security", "L13", "Passed"); book.set("Security", "M13", "28/11/2026")
#   book.save()
import re
import zipfile

from lxml import etree

NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
XML_SPACE = "{http://www.w3.org/XML/1998/namespace}space"


def q(tag):
    return f"{{{NS}}}{tag}"


def split_ref(ref):
    col, row = re.fullmatch(r"([A-Z]+)(\d+)", ref).groups()
    return col, int(row)


def col_index(col):
    n = 0
    for ch in col:
        n = n * 26 + ord(ch) - 64
    return n


def col_letter(n):
    s = ""
    while n:
        n, r = divmod(n - 1, 26)
        s = chr(65 + r) + s
    return s


class Book:
    def __init__(self, path):
        self.path = path
        with zipfile.ZipFile(path) as z:
            self.infos = z.infolist()
            self.parts = {i.filename: z.read(i.filename) for i in self.infos}
        wb = etree.fromstring(self.parts["xl/workbook.xml"])
        rels = etree.fromstring(self.parts["xl/_rels/workbook.xml.rels"])
        target = {r.get("Id"): r.get("Target") for r in rels.iter(f"{{{PKG_REL}}}Relationship")}
        self.sheet_part = {}
        for s in wb.iter(q("sheet")):
            t = target[s.get(f"{{{REL}}}id")].lstrip("/")
            self.sheet_part[s.get("name")] = t if t.startswith("xl/") else "xl/" + t
        self.trees = {}

    def _tree(self, sheet):
        part = self.sheet_part[sheet]
        if part not in self.trees:
            self.trees[part] = etree.fromstring(self.parts[part])
        return self.trees[part]

    def _cell(self, sheet, ref):
        col, row = split_ref(ref)
        data = self._tree(sheet).find(q("sheetData"))
        tr = None
        for r in data.iterchildren(q("row")):
            n = int(r.get("r"))
            if n == row:
                tr = r
                break
            if n > row:
                tr = etree.Element(q("row"), r=str(row))
                r.addprevious(tr)
                break
        if tr is None:
            tr = etree.SubElement(data, q("row"), r=str(row))
        for c in tr.iterchildren(q("c")):
            cref = c.get("r")
            if cref == ref:
                return c
            if col_index(split_ref(cref)[0]) > col_index(col):
                new = etree.Element(q("c"), r=ref)
                c.addprevious(new)
                return self._style_like_row(tr, new)
        new = etree.SubElement(tr, q("c"), r=ref)
        return self._style_like_row(tr, new)

    @staticmethod
    def _style_like_row(tr, c):
        if tr.get("s") and tr.get("customFormat") == "1":
            c.set("s", tr.get("s"))
        return c

    def set(self, sheet, ref, value, style_from=None):
        """value: str (inline string; "" clears the cell), int/float, or "=FORMULA"; style_from copies the style of
        another cell of the same sheet when the target has none"""
        c = self._cell(sheet, ref)
        if style_from and not c.get("s"):
            src = self._cell(sheet, style_from)
            if src.get("s"):
                c.set("s", src.get("s"))
        for child in list(c):
            c.remove(child)
        c.attrib.pop("t", None)
        if value is None or value == "":
            return
        if isinstance(value, bool):
            c.set("t", "b")
            etree.SubElement(c, q("v")).text = "1" if value else "0"
        elif isinstance(value, (int, float)):
            etree.SubElement(c, q("v")).text = repr(value)
        elif value.startswith("="):
            etree.SubElement(c, q("f")).text = value[1:]
        else:
            c.set("t", "inlineStr")
            t = etree.SubElement(etree.SubElement(c, q("is")), q("t"))
            t.text = value
            t.set(XML_SPACE, "preserve")

    def refresh_cached_values(self):
        """Give every formula cell the value LibreOffice computes for the workbook as it now stands. A reader that
        does not recalculate (LibreOffice by default, openpyxl, a viewer) otherwise shows the old totals. The
        workbook is saved to a temporary copy, recalculated there through UNO, and only the <v> of formula cells is
        copied back; nothing else of the package changes."""
        import os
        import subprocess
        import tempfile
        import time

        import openpyxl

        tmp = tempfile.mkdtemp(prefix="xlsx-recalc-")
        src = os.path.join(tmp, "in.xlsx")
        out = os.path.join(tmp, "out.xlsx")
        self.save(src)
        pipe = f"xlsxrecalc{os.getpid()}"
        office = subprocess.Popen(["soffice", "--headless", "--invisible", "--norestore", "--nologo",
                                   f"-env:UserInstallation=file://{tmp}/profile",
                                   f"--accept=pipe,name={pipe};urp;"], stdout=subprocess.DEVNULL,
                                  stderr=subprocess.DEVNULL)
        try:
            import uno
            from com.sun.star.beans import PropertyValue

            def prop(name, value):
                p = PropertyValue()
                p.Name, p.Value = name, value
                return p

            ctx = uno.getComponentContext()
            resolver = ctx.ServiceManager.createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver", ctx)
            for _ in range(120):
                try:
                    remote = resolver.resolve(f"uno:pipe,name={pipe};urp;StarOffice.ComponentContext")
                    break
                except Exception:
                    time.sleep(0.5)
            desktop = remote.ServiceManager.createInstanceWithContext("com.sun.star.frame.Desktop", remote)
            doc = desktop.loadComponentFromURL(uno.systemPathToFileUrl(src), "_blank", 0, (prop("Hidden", True),))
            doc.calculateAll()
            doc.storeToURL(uno.systemPathToFileUrl(out), (prop("FilterName", "Calc MS Excel 2007 XML"),))
            doc.close(True)
            try:
                desktop.terminate()
            except Exception:
                pass
        finally:
            if office.poll() is None:
                office.kill()
            office.wait()
        computed = openpyxl.load_workbook(out, data_only=True)
        for sheet, part in self.sheet_part.items():
            tree = self._tree(sheet)
            ws = computed[sheet]
            for c in tree.iter(q("c")):
                if c.find(q("f")) is None:
                    continue
                value = ws[c.get("r")].value
                v = c.find(q("v"))
                if v is not None:
                    c.remove(v)
                c.attrib.pop("t", None)
                if value is None:
                    continue
                if isinstance(value, bool):
                    c.set("t", "b")
                    text = "1" if value else "0"
                elif isinstance(value, (int, float)):
                    text = repr(value)
                else:
                    c.set("t", "str")
                    text = str(value)
                etree.SubElement(c, q("v")).text = text

    def save(self, path=None):
        for part, tree in self.trees.items():
            self.parts[part] = etree.tostring(tree, xml_declaration=True, encoding="UTF-8", standalone=True)
        wb = etree.fromstring(self.parts["xl/workbook.xml"])
        calc = wb.find(q("calcPr"))
        if calc is None:
            calc = etree.SubElement(wb, q("calcPr"))
        calc.set("fullCalcOnLoad", "1")
        self.parts["xl/workbook.xml"] = etree.tostring(wb, xml_declaration=True, encoding="UTF-8", standalone=True)
        with zipfile.ZipFile(path or self.path, "w", zipfile.ZIP_DEFLATED) as z:
            for info in self.infos:
                z.writestr(info, self.parts[info.filename], compress_type=zipfile.ZIP_DEFLATED)
