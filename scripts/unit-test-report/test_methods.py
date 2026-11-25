# Lists the test methods of a JUnit test class in source order, nested classes included as Outer$Inner.method,
# the way surefire names them. Used to tie each Report-5.1 row to the tests it stands for.
import glob
import os
import re

TEST = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest)\b[^\n]*\n(?:\s*@[^\n]*\n)*\s*(?:public\s+|protected\s+)?"
                  r"(?:static\s+)?void\s+(\w+)\s*\(")
NESTED = re.compile(r"@Nested\s*(?:\n\s*@[^\n]*)*\n\s*(?:public\s+|private\s+|protected\s+)?(?:static\s+)?class\s+(\w+)")


def find_test_file(backend, service, test_class):
    hits = glob.glob(os.path.join(backend, service, "src", "test", "java", "**", test_class + ".java"), recursive=True)
    return hits[0] if hits else None


def methods(path):
    src = open(path, encoding="utf-8").read()
    nested = [(m.start(), m.group(1)) for m in NESTED.finditer(src)]
    out = []
    for m in TEST.finditer(src):
        owner = [name for start, name in nested if start < m.start()]
        out.append((owner[-1] + "." if owner else "") + m.group(1))
    return out
