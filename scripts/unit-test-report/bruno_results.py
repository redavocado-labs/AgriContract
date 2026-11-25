# Reads one round of Bruno JSON reports (written by run_bruno_report52.sh) and gives each Report-5.2 row its result.
#
# A row passes when every .bru request in its Note ran with all its tests passing and answered with a status the
# row expects ("Return: 201 ..." or "201 (lần đầu) hoặc 200 ..."). The same request may run more than once in a
# round (user-service runs with and without seller1's seeded profile), so a row looks for a run whose status it
# expects; a request that only ran with other statuses leaves the row Pending. No report at all = Pending.
import glob
import json
import os
import re

STATUS = re.compile(r"\b([1-5]\d\d)\b")


def load_round(folder):
    meta_path = os.path.join(folder, "run.json")
    meta = {"date": "", "tester": "", "commit": "", "bruno_cli": ""}
    if os.path.exists(meta_path):
        with open(meta_path, encoding="utf-8") as f:
            meta.update(json.load(f))
    runs = {}  # "folder/file.bru" -> [(http status, all tests passed, failure text)]
    for path in sorted(glob.glob(os.path.join(folder, "*.json"))):
        if os.path.basename(path) == "run.json":
            continue
        with open(path, encoding="utf-8") as f:
            report = json.load(f)
        for iteration in report if isinstance(report, list) else [report]:
            for r in iteration.get("results", []):
                name = (r.get("test") or {}).get("filename", "").replace("\\", "/")
                tests = (r.get("testResults") or []) + (r.get("assertionResults") or [])
                failed = [t for t in tests if t.get("status") != "pass"]
                ok = not r.get("error") and not failed
                text = r.get("error") or "; ".join(f"{t.get('description')}: {t.get('error', '')}" for t in failed)
                runs.setdefault(name, []).append(((r.get("response") or {}).get("status"), ok, text))
    meta["runs"] = runs
    return meta


def note_files(note):
    # "product-service/03-propose-category-for-reject.bru, 04-reject-category.bru": later names share the folder
    files, folder = [], ""
    for part in (p.strip() for p in note.split(",") if p.strip()):
        if "/" in part:
            folder = part.rsplit("/", 1)[0]
        else:
            part = f"{folder}/{part}"
        files.append(part)
    return files


def expected_statuses(ret):
    head = ret.split(",")[0].split(";")[0]
    return {int(s) for s in STATUS.findall(head)}


def row_result(round_data, note, ret):
    if not note or not round_data["runs"]:
        return "Pending"
    want = expected_statuses(ret)
    verdicts = []
    for name in note_files(note):
        runs = round_data["runs"].get(name, [])
        matching = [r for r in runs if not want or r[0] in want]
        if matching:
            verdicts.append(all(ok for _, ok, _ in matching))
        elif runs:
            # every run answered with a status this row does not expect
            verdicts.append(False if len(want) == 1 and _single_purpose(name, round_data) else None)
        else:
            verdicts.append(None)
    if any(v is False for v in verdicts):
        return "Failed"
    if any(v is None for v in verdicts):
        return "Pending"
    return "Passed"


def _single_purpose(name, round_data):
    # a request that ran only once has one purpose: a status the row does not expect is a failure, not "not run"
    return len(round_data["runs"].get(name, [])) == 1


def failures(round_data):
    return {name: [t for _, ok, t in runs if not ok] for name, runs in round_data["runs"].items()
            if any(not ok for _, ok, _ in runs)}
