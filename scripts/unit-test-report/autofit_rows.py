# Make every row of a report as tall as its text, so no cell hides part of its content. The templates give each row a
# fixed height, and the fill scripts write long text (steps, expected results, notes) into those rows. Through
# LibreOffice UNO, which keeps the charts, comments and validation: wrap the text of every cell whose right-hand
# neighbour is not empty (only those get cut off; a group title beside empty cells keeps overflowing to the right),
# then let LibreOffice compute each used row's height from its content. Excel does not grow a row by itself when its text
# changes later, so run this last, after any script that writes a report.
#
#   soffice --headless --invisible --norestore --accept="pipe,name=agc52;urp;" &
#   python3 autofit_rows.py ../../docs/reports/Report-5.1_Unit_Test.xlsx ...   (each file is rewritten in place)
import os
import shutil
import sys
import tempfile

import uno
from com.sun.star.beans import PropertyValue


def prop(name, value):
    p = PropertyValue()
    p.Name, p.Value = name, value
    return p


ctx = uno.getComponentContext()
resolver = ctx.ServiceManager.createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver", ctx)
remote = resolver.resolve("uno:pipe,name=agc52;urp;StarOffice.ComponentContext")
desktop = remote.ServiceManager.createInstanceWithContext("com.sun.star.frame.Desktop", remote)

for path in sys.argv[1:]:
    path = os.path.abspath(path)
    doc = desktop.loadComponentFromURL(uno.systemPathToFileUrl(path), "_blank", 0, (prop("Hidden", True),))
    fitted = 0
    for i in range(doc.Sheets.Count):
        sheet = doc.Sheets.getByIndex(i)
        cursor = sheet.createCursor()
        cursor.gotoEndOfUsedArea(False)
        last_row, last_col = cursor.RangeAddress.EndRow, cursor.RangeAddress.EndColumn
        used = sheet.getCellRangeByPosition(0, 0, last_col, last_row)
        data = used.getDataArray()
        for r, row in enumerate(data):
            for c, value in enumerate(row):
                if isinstance(value, str) and value and (c == last_col or row[c + 1] != ""):
                    sheet.getCellByPosition(c, r).IsTextWrapped = True
        rows = used.Rows
        for r in range(rows.Count):
            if rows.getByIndex(r).IsVisible:
                rows.getByIndex(r).OptimalHeight = True
        fitted += last_row + 1
    fd, tmp = tempfile.mkstemp(suffix=".xlsx", dir=os.path.dirname(path))
    os.close(fd)
    doc.storeToURL(uno.systemPathToFileUrl(tmp), (prop("FilterName", "Calc MS Excel 2007 XML"),))
    doc.close(True)
    shutil.move(tmp, path)
    print(f"{os.path.basename(path)}: {fitted} rows fitted")
