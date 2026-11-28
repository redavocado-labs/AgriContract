# Translate Vietnamese lines in Report 5.1 / 5.2 workbooks to English through LibreOffice UNO so charts,
# logo images, comments, data validations and hyperlinks stay intact.
#
# IMPORTANT: Round-update scripts (update_51_round3_xml.py, update_52_round_xml.py, ...) still emit Vietnamese
# text when re-run. Always run this script (and then autofit_rows.py) after every round update.
#
#   soffice --headless --invisible --norestore --accept="pipe,name=agc52;urp;" &
#   python3 translate_vi_en.py ../../docs/reports/Report-5.2_Test_Report.xlsx ...
#   python3 autofit_rows.py ../../docs/reports/Report-5.2_Test_Report.xlsx ...
import os
import re
import shutil
import sys
import tempfile

import uno
from com.sun.star.beans import PropertyValue
from com.sun.star.table import CellAddress, CellRangeAddress

VI_RE = re.compile(r"[ăâđêôơưàáảãạằắẳẵặầấẩẫậèéẻẽẹềếểễệìíỉĩịòóỏõọồốổỗộờớởỡợùúủũụừứửữựỳýỷỹỵ]", re.I)

CHANGELOG_DATE = "28/11/2026"
CHANGELOG_ITEM = "Whole document"
CHANGELOG_KIND = "M"
CHANGELOG_DESC = "Translated into English"


def prop(name, value):
    p = PropertyValue()
    p.Name, p.Value = name, value
    return p


def load_translations(path):
    base = os.path.basename(path)
    merged = {}
    if "5.2" in base:
        from translations_52 import TRANSLATIONS
        return TRANSLATIONS
    if "5.1" in base:
        from translations_51 import TRANSLATIONS
        return TRANSLATIONS
    for mod_name in ("translations_52", "translations_51"):
        try:
            mod = __import__(mod_name)
            merged.update(mod.TRANSLATIONS)
        except ImportError:
            pass
    return merged


def append_changelog_row(doc):
    if "Cover" not in doc.Sheets.ElementNames:
        return False
    cover = doc.Sheets.getByName("Cover")
    # Find last populated change-log row starting at row 11 (0-based index 10)
    last_idx = 10
    for r in range(10, 200):
        a = cover.getCellByPosition(0, r).String.strip()
        c = cover.getCellByPosition(2, r).String.strip()
        if not a and not c:
            break
        last_idx = r

    # Idempotency check: skip if the English-translation entry is already present
    for r in range(10, last_idx + 1):
        if (
            cover.getCellByPosition(0, r).String.strip() == CHANGELOG_DATE
            and cover.getCellByPosition(2, r).String.strip() == CHANGELOG_ITEM
            and cover.getCellByPosition(3, r).String.strip() == CHANGELOG_KIND
            and cover.getCellByPosition(4, r).String.strip() == CHANGELOG_DESC
        ):
            return False

    new_idx = last_idx + 1
    sheet_idx = cover.RangeAddress.Sheet
    src = CellRangeAddress(sheet_idx, 0, last_idx, 5, last_idx)
    dst = CellAddress(sheet_idx, 0, new_idx)
    cover.copyRange(dst, src)
    cover.Rows.getByIndex(new_idx).Height = cover.Rows.getByIndex(last_idx).Height

    version = cover.getCellByPosition(1, last_idx).String
    for col, val in enumerate((CHANGELOG_DATE, version, CHANGELOG_ITEM, CHANGELOG_KIND, CHANGELOG_DESC, "")):
        cover.getCellByPosition(col, new_idx).String = val
    return True


def translate_workbook(desktop, path, translations):
    path = os.path.abspath(path)
    doc = desktop.loadComponentFromURL(uno.systemPathToFileUrl(path), "_blank", 0, (prop("Hidden", True),))
    changed_cells = 0
    translated_lines = 0
    remaining = []

    for i in range(doc.Sheets.Count):
        sheet = doc.Sheets.getByIndex(i)
        cursor = sheet.createCursor()
        cursor.gotoEndOfUsedArea(False)
        last_row, last_col = cursor.RangeAddress.EndRow, cursor.RangeAddress.EndColumn
        used = sheet.getCellRangeByPosition(0, 0, last_col, last_row)
        data = used.getDataArray()
        formulas = used.getFormulaArray()

        for r, (row_vals, row_fms) in enumerate(zip(data, formulas)):
            for c, (val, fm) in enumerate(zip(row_vals, row_fms)):
                if not isinstance(val, str) or not val:
                    continue
                if isinstance(fm, str) and fm.startswith("="):
                    continue
                lines = val.split("\n")
                new_lines = []
                cell_changed = False
                for line in lines:
                    repl = translations.get(line)
                    if repl is not None and repl != line:
                        new_lines.append(repl)
                        cell_changed = True
                        translated_lines += 1
                    else:
                        new_lines.append(line)

                if cell_changed:
                    cell = sheet.getCellByPosition(c, r)
                    if cell.TextFields.Count > 0:
                        continue
                    cell.String = "\n".join(new_lines)
                    changed_cells += 1

                # Check if any line still has Vietnamese (skipping hyperlink cells)
                if any(VI_RE.search(line) for line in new_lines):
                    cell = sheet.getCellByPosition(c, r)
                    if cell.TextFields.Count > 0:
                        continue
                    for line in new_lines:
                        if VI_RE.search(line):
                            remaining.append((sheet.Name, r + 1, c + 1, line))

    added_log = append_changelog_row(doc)

    fd, tmp = tempfile.mkstemp(suffix=".xlsx", dir=os.path.dirname(path))
    os.close(fd)
    doc.storeToURL(uno.systemPathToFileUrl(tmp), (prop("FilterName", "Calc MS Excel 2007 XML"),))
    doc.close(True)
    shutil.move(tmp, path)

    print(
        f"{os.path.basename(path)}: changed_cells={changed_cells}, "
        f"translated_lines={translated_lines}, changelog_added={added_log}, "
        f"remaining_vi_lines={len(remaining)}"
    )
    for item in remaining[:15]:
        print("  remaining:", item)


def main():
    if len(sys.argv) < 2:
        sys.exit("Usage: python3 translate_vi_en.py <file.xlsx> ...")
    ctx = uno.getComponentContext()
    resolver = ctx.ServiceManager.createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver", ctx)
    remote = resolver.resolve("uno:pipe,name=agc52;urp;StarOffice.ComponentContext")
    desktop = remote.ServiceManager.createInstanceWithContext("com.sun.star.frame.Desktop", remote)

    for path in sys.argv[1:]:
        translations = load_translations(path)
        translate_workbook(desktop, path, translations)


if __name__ == "__main__":
    main()
