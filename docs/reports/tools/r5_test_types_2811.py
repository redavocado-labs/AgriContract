"""Report 5.0 I.2.1 / I.2.2: testing types by the usual names (mentor review). Input validation goes into functional
testing, event contract testing becomes interface testing, background job testing becomes reliability testing, and
regression and acceptance testing are added. Content only, styles kept.

    python r5_test_types_2811.py
"""
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import fill_change_log, find, insert_copy_after, next_table, set_par_text, set_table_rows  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-5.0_Test_Documentation.docx"
d = docx.Document(DOC)

functional = find(d, "Functional testing. Objective")
set_par_text(functional,
             "Functional testing. Objective: each use case and state transition behaves as the SRS and the state "
             "machines define, and an invalid request is rejected with the frozen error code without changing state. "
             "Technique: equivalence partitioning and state-transition testing on domain objects and use cases, "
             "boundary value analysis on money (2 decimals, DECIMAL(18,2)), quantities, dates, page size (1 to 100) "
             "and OTP (6 digits, 300 s, 5 attempts, 60 s cooldown, 5 codes per hour), and Bean Validation on request "
             "DTOs; controller slices (@WebMvcTest, use case mocked) at UT, REST calls through MockMvc in a "
             "@SpringBootTest on the real schema and Bruno requests at IT, the user flows BF-01..BF-07 at ST, the UAT "
             "scripts at AT. A test case is typed Boundary only when its input sits on a limit or right next to it "
             "(min - 1, min, max, max + 1); a replayed, late, duplicate or concurrent call is Abnormal. Completion: "
             "every allowed transition and every forbidden one has a test, each field limit in the SRS has a "
             "below/at/above case; all pass.")
d.element.body.remove(find(d, "Input validation testing. Objective"))
set_par_text(find(d, "Event contract testing. Objective"),
             "Interface testing. Objective: the components stay compatible through their frozen contracts: REST "
             "endpoints as contracts/openapi defines them, events as contracts/events defines them, and the gateway "
             "in front of every service. Technique: responses checked against the OpenAPI schemas and error catalog, "
             "every emitted event validated against its JSON Schema, malformed payloads sent to consumers (dead-letter "
             "queue, no retry), duplicates replayed with the same eventId; a CI job validates all contract files. "
             "Completion: every endpoint and every produced and consumed event type is covered.")
set_par_text(find(d, "Background job testing. Objective"),
             "Reliability testing. Objective: no money movement or event is lost or applied twice, and scheduled work "
             "recovers after a failure. Technique: integration tests with Awaitility for the transactional outbox, the "
             "deposit and funding lock retry schedule (5 min, 30 min, 2 h), idempotent bank commands, the dead-letter "
             "queue monitor and business-day deadlines; at ST every dead-letter queue is checked empty after each flow, "
             "and the web client's retry and recovery paths are run by Playwright. Completion: each job and consumer "
             "has a success, retry and no-duplicate case. Results in Report-5.4, sheet Reliability.")
find(d, "Security testing. Objective").addnext(find(d, "Reliability testing. Objective"))  # table order
ui = find(d, "UI testing. Objective")
set_par_text(ui,
             "UI testing. Objective: each screen of the web client shows the right data and actions for each role and "
             "meets the usability requirements of the SRS. Technique: Playwright browser scripts (frontend/tests/qa) on "
             "the role gates, command recovery and evidence upload of the business flows, axe accessibility scans, "
             "keyboard use and 320 px reflow; the other usability requirements are checked by hand on the demo stack. "
             "Completion: every in-scope screen covered for each role that can open it. Results in Report-5.4, sheet "
             "Usability.")
regression = insert_copy_after(ui, ui)
set_par_text(regression,
             "Regression testing. Objective: a change does not break what already worked. Technique: after a change, "
             "every existing unit, integration and system test is run again on the new code, not only the tests of the "
             "change; each case keeps its ID and gets a new round. Round 3 of 28/11/2026 in Report-5.1, 5.2 and 5.3 is "
             "such a run. Completion: every case that passed before passes again, or its failure is a defect of "
             "Report-5.6.")
acceptance = insert_copy_after(regression, regression)
set_par_text(acceptance,
             "Acceptance testing. Objective: the business users can finish their flows and accept the system. "
             "Technique: the UAT scripts of Report-5.5, one per business scenario of BF-01..BF-07 in business "
             "language, run by a business user on the demo stack, plus exploratory sessions logged in the same "
             "workbook. Completion: every Critical and High script passes and the mentor accepts the demo of each "
             "flow.")

Y = "✓"
table = next_table(find(d, "2.2 Test Levels"))
set_table_rows(table, [
    ["Functional Testing", Y, Y, Y, Y],
    ["Interface Testing", Y, Y, "", ""],
    ["Security Testing", Y, Y, Y, ""],
    ["Reliability Testing", "", Y, Y, ""],
    ["Performance Testing", "", "", Y, ""],
    ["UI Testing", "", "", Y, Y],
    ["Regression Testing", Y, Y, Y, ""],
    ["Acceptance Testing", "", "", "", Y],
], header_rows=2, template_row=2)

fill_change_log(next_table(find(d, "Change Log", tag="w:p")),
                ["28/11/2026", "M", "Minh Cuong",
                 "Section I.2.1 and I.2.2 (mentor review): testing types by their usual names; input validation is "
                 "part of functional testing, event contract testing becomes interface testing, background job "
                 "testing becomes reliability testing, regression and acceptance testing added"])
d.save(DOC)
print("saved", DOC.name)
