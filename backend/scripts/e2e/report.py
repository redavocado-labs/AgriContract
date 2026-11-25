"""Write Report 5 rows from the Bruno JSON reporter and status manifest."""
import argparse
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

OUTCOMES = ("Passed", "Failed", "Pending", "N/A")


def md(value):
    return str(value).replace("|", r"\|").replace("\n", " ")


def parse_cases(bruno, folder):
    cases = {}
    for file in sorted((bruno / folder).rglob("*.bru")):
        source = file.read_text()
        case_id, title = re.search(r"^  name: (\S+) (.+)$", source, re.M).groups()
        kind, reference = re.search(r"^docs \{\n  (IT-GW|IT-DIRECT)\n\n  (.+)$", source, re.M).groups()
        cases[case_id] = (title, kind, reference, file)
    return cases


def load_results(path):
    if not path.exists():
        return {}
    return {row["name"].split()[0]: row for iteration in json.loads(path.read_text())
            for row in iteration["results"]}


def outcome(result):
    if not result or result.get("skipped"):
        return "Pending"
    if result.get("error") or result.get("status") == "fail":
        return "Failed"
    checks = result.get("testResults") or []
    return "Passed" if checks and all(check["status"] == "pass" for check in checks) else "Failed"


# The gateway answers 401/403 with an empty body instead of an ErrorEnvelope; tracked, owner api-gateway.
GATEWAY_EMPTY_DENIAL_ISSUE = "#663"


def empty_auth_denial(result):
    response = (result or {}).get("response") or {}
    return response.get("data") in (None, "") and response.get("status") in (401, 403)


def failure_text(case_id, title, reference, location, result, reporter):
    request = result.get("request") or {}
    response = result.get("response") or {}
    data = response.get("data")
    stripped_envelope = empty_auth_denial(result)
    if isinstance(data, dict):
        data = {key: data[key] for key in ("code", "message", "correlationId", "status") if key in data}
    checks = result.get("testResults") or []
    error = result.get("error") or next((check.get("error") for check in checks if check["status"] == "fail"), "")
    suspect = "api-gateway authentication or authorization filter" if stripped_envelope else "See service logs for the endpoint owner"
    spec_response = {401: "Unauthorized", 403: "Forbidden"}.get(response.get("status"))
    tracked = f"- Tracked in {GATEWAY_EMPTY_DENIAL_ISSUE}\n" if stripped_envelope else ""
    contract = (f" and contracts/openapi/golden-flow-api.yaml components.responses.{spec_response}"
                if stripped_envelope else "")
    return (
        f"## {case_id} {title}\n\n"
        f"- Request `{request.get('method', '?')} {request.get('url', '?')}`\n"
        f"- Expected behavior in [{location.name}]({location.as_uri()}) and {reference}{contract}\n"
        f"- Actual HTTP response `{response.get('status', 'none')}` with `{md(json.dumps(data, ensure_ascii=False))}`\n"
        f"- Assertion or runner error `{md(error)}`\n"
        f"- Severity {('security' if case_id.startswith('SEC-') else 'business flow')}\n"
        f"- Suspected source {suspect}\n"
        f"{tracked}"
        f"- Evidence [{reporter.name}]({reporter.name}) and the corresponding service logs in `logs/`\n"
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("round", type=Path)
    parser.add_argument("bruno", type=Path)
    parser.add_argument("--setup-failed", action="store_true")
    parser.add_argument("suites", nargs="+")
    args = parser.parse_args()
    rows, findings = [], []
    totals = defaultdict(Counter)
    suites = dict(suite.split("=", 1) for suite in args.suites)
    manifest = Path(__file__).with_name("status.tsv")

    def add(flow, case_id, title, kind, status, evidence, issue=""):
        rows.append((args.round.name, flow, case_id, title, kind, status, evidence, issue))
        totals[flow][status] += 1

    if args.setup_failed:
        add("Setup", "SETUP", "Compose readiness or seeding", "IT-DIRECT", "Failed", "stack.log")
        findings.append("## SETUP\n\nCompose startup, readiness or seeding failed. See [stack.log](stack.log).\n")

    for flow, folder in suites.items():
        reporter = args.round / f"bruno-{flow.lower()}.json"
        results = load_results(reporter)
        for case_id, (title, kind, reference, location) in sorted(parse_cases(args.bruno, folder).items()):
            result = results.get(case_id)
            status = outcome(result)
            evidence = reporter.name if result else "No result. See runner log."
            issue = ""
            if case_id == "BF04-390" and status == "Passed" and result["response"]["status"] == 404:
                status, issue = "Pending", "#630"
            if status == "Failed" and empty_auth_denial(result):
                issue = GATEWAY_EMPTY_DENIAL_ISSUE
            add(flow, case_id, title, kind, status, evidence, issue)
            if status == "Failed":
                findings.append(failure_text(case_id, title, reference, location.resolve(), result, reporter))
        if not args.setup_failed and folder != "e2e/bf06-mutual-termination-replacement" and not results:
            add(flow, f"{flow}-RUN", "Bruno produced no result", "IT-DIRECT", "Failed", f"bruno-{flow.lower()}.log")
        snapshot = args.round / f"queues-{flow.lower()}.json"
        if snapshot.exists():
            try:
                queues = json.loads(snapshot.read_text())
            except json.JSONDecodeError:
                queues = []
            dlqs = [row for row in queues if row["name"].endswith(".dlq")]
            dirty = [row["name"] for row in dlqs if row.get("messages") != 0]
            status = "Passed" if dlqs and not dirty else "Failed"
            add(flow, f"{flow}-DLQ", "RabbitMQ dead-letter queues are empty", "IT-DIRECT", status, snapshot.name)
            if status == "Failed":
                findings.append(f"## {flow}-DLQ\n\nExpected all DLQs present and empty. Nonempty queues: {', '.join(dirty) or 'none captured'}. See [{snapshot.name}]({snapshot.name}).\n")
        elif not args.setup_failed:
            add(flow, f"{flow}-DLQ", "RabbitMQ queue snapshot unavailable", "IT-DIRECT", "Failed", "stack.log")

    for line in manifest.read_text().splitlines():
        flow, case_id, title, kind, status, issue, reason = line.split("\t")
        if flow in suites:
            add(flow, case_id, title, kind, status, reason, issue)

    report = [
        "# Testing rounds", "",
        "| Round | Flow | TC ID | Description | Kind | Result | Evidence | Issue |",
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
        *("| " + " | ".join(map(md, row)) + " |" for row in rows),
        "", "## Totals", "",
        "| Flow | Passed | Failed | Pending | N/A |", "| --- | ---: | ---: | ---: | ---: |",
    ]
    total = sum(totals.values(), Counter())
    for flow in (*totals, "Total"):
        counts = total if flow == "Total" else totals[flow]
        report.append(f"| {flow} | " + " | ".join(str(counts[key]) for key in OUTCOMES) + " |")
    (args.round / "TESTING-ROUNDS.md").write_text("\n".join(report) + "\n")
    (args.round / "FINDINGS.md").write_text("# Findings\n\n" + "\n".join(findings or ["No failed cases in this round.\n"]))
    return int(bool(total["Failed"]))


if __name__ == "__main__":
    raise SystemExit(main())
