"""Report 5.0: fills section II.1 Test Data and II.2 Test Cases, and corrects section I where it does not match the code.

Run once on the version where II.1 still holds the template's HR sample data (admin, hr_manager, candidate_01...).

Section I corrections (checked against the repository on 24/11/2026):
* IT is not mock-free: the HTTP ports to other services are @MockBean in the *IntegrationTest classes; only the
  database and the broker are never mocked.
* "REST calls through MockMvc on the real schema" is true only for @SpringBootTest + MockMvc; the @WebMvcTest
  controller slices mock the use case and are unit-level.
* Report-5.1 types 265 unit test cases as Boundary, many of them replays, races or shutdown cases with no limit; the
  Input validation item now says what Boundary means.
* Every Bruno request is an integration test (mentor review R01), not a system test: it calls one service directly
  (X-Gateway-Secret / X-Internal-Secret, or the broker) or goes through the gateway with a Keycloak token. System
  tests follow a user flow on the FE (BF-01..BF-07).
* JMeter and Playwright are planned: there is no .jmx file and no Playwright dependency in the repository yet.
"""
import copy
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import fill_change_log, find, next_table, set_cell_text, set_par_text, set_table_rows, text_of  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-5.0_Test Documentation.docx"
d = docx.Document(DOC)


def plain(p):
    """Drops the guidance look (italic, blue) from the paragraph's runs."""
    for rpr in p.iter(qn("w:rPr")):
        for tag in ("w:i", "w:iCs", "w:color"):
            for el in rpr.findall(qn(tag)):
                rpr.remove(el)
    return p


def replace(starts_with, text):
    p = find(d, starts_with)
    set_par_text(p, text)
    return p


def remove(p):
    p.getparent().remove(p)


# ---------------------------------------------------------------- I.2 Test Strategy
replace("Every rule is tested at the lowest level",
        "Every rule is tested at the lowest level that can prove it: business rules in unit tests, anything that "
        "depends on SQL, transactions or the message broker in integration tests against real MySQL and RabbitMQ "
        "containers, and the cooperation of several services in system tests on the full stack. At unit level only "
        "ports are mocked; integration tests never mock the database or the broker, because idempotency and "
        "concurrency bugs only show up there, and replace only the HTTP ports to other services (@MockBean).")

replace("Functional testing. Objective:",
        "Functional testing. Objective: each use case and state transition behaves as the SRS and the state machines "
        "define. Technique: equivalence partitioning and state-transition testing on domain objects and use cases, "
        "and controller slices (@WebMvcTest, use case mocked) at UT; REST calls through MockMvc in a @SpringBootTest "
        "on the real schema and Bruno requests, to the service directly or through the gateway, at IT; the FE user "
        "flows BF-01..BF-07 at ST. Completion: every allowed transition and every forbidden one has a test; all "
        "pass.")

replace("Input validation testing. Objective:",
        "Input validation testing. Objective: invalid requests and payloads are rejected with the frozen error code "
        "and never change state. Technique: boundary value analysis on money (2 decimals, DECIMAL(18,2)), "
        "quantities, dates, page size (1 to 100), OTP (6 digits, 300 s, 5 attempts, 60 s cooldown, 5 codes per hour) "
        "and Bean Validation on request DTOs. A test case is typed Boundary only when its input sits on a limit or "
        "right next to it (min - 1, min, max, max + 1); a replayed, late, duplicate or concurrent call is Abnormal. "
        "Completion: each field limit in the SRS has a below/at/above case.")

# ---------------------------------------------------------------- I.2.3 Supporting Tools
tools = next_table(find(d, "Tools used to write, run and report the tests"))
for tr in tools.findall(qn("w:tr")):
    tcs = tr.findall(qn("w:tc"))
    first, second = text_of(tcs[0]).strip(), text_of(tcs[1]).strip()
    if second == "Bruno CLI":
        set_cell_text(tcs[0], "API test collections: the integration test cases of Report-5.2, each calling one "
                              "service (or the broker) directly or going through the gateway with a Keycloak token")
    elif second == "Apache JMeter":
        set_cell_text(tcs[0], "Load and response-time testing (planned: no test plan in the repository yet)")
    elif second == "Playwright":
        set_cell_text(tcs[0], "Automated browser tests of the web client (planned: not yet a dependency of "
                              "frontend/)")
    elif first == "Test case and statistics workbook":
        set_cell_text(tcs[0], "Unit test case and statistics workbook")
last = tools.findall(qn("w:tr"))[-1]
row = copy.deepcopy(last)
for tc, v in zip(row.findall(qn("w:tc")), ["Integration test case and statistics workbook (built from backend/bruno)",
                                           "Report-5.2_Test_Report.xlsx", "In-house", "v1.0"]):
    set_cell_text(tc, v)
last.addnext(row)

# ---------------------------------------------------------------- II.1 Test Data
intro = find(d, "[Define the minimum data required")
n = intro.getnext()
while n is not None and n.tag == qn("w:p") and not text_of(n).startswith("1.1"):
    nxt = n.getnext()
    remove(n)
    n = nxt
set_par_text(plain(intro),
             "Test data is synthetic. UT and IT create what they need inside the test (in-memory fakes, or a fresh "
             "Testcontainers schema migrated by Flyway) and never depend on rows left by another test. The Bruno "
             "integration tests, ST and AT run on the seeded stack: backend/scripts/run-e2e.sh reloads the seed before each Bruno run, "
             "backend/bruno/notification-service/reset.sh clears notification logs, queues and MailHog, and every "
             "request that creates data sends a fresh Idempotency-Key or a time-stamped id, so a rerun never collides "
             "with an earlier one.")

heading = find(d, "1.1 Minium Test Account")
set_par_text(heading, "1.1 Minimum Test Account")
set_par_text(plain(find(d, "[One row per test persona")),
             "Keycloak realm agricontract (backend/infra/keycloak/agricontract-realm.json) has one account per role; "
             "the Bruno environment backend/bruno/environments/local.bru holds the same credentials.")
accounts = next_table(heading)
set_table_rows(accounts, [
    ["buyer1", "Buyer", "pass123", "ACTIVE, profile VERIFIED (seed)",
     "Buyer side of every contract test: create offer, list and filter contracts, signing refused without a fresh "
     "login"],
    ["seller1", "Seller", "pass123", "ACTIVE, profile VERIFIED (seed)",
     "Seller side: categories, products, plots, listings; counterparty of buyer1's contracts; profile registration "
     "(the seeded profile is removed first)"],
    ["seller2", "Seller", "pass123", "ACTIVE",
     "Stranger: party of no test contract, proves 403 on another party's contract"],
    ["operator1", "Operator", "pass123", "ACTIVE", "Approve or reject categories, assign the commodity"],
    ["inspector1", "Inspector", "pass123", "ACTIVE", "Level 1.5 inspection of a contested milestone (BF-04)"],
    ["arbitrator1", "Arbitrator", "pass123", "ACTIVE", "Breach case decisions (BF-05)"],
    ["admin1", "Admin", "pass123", "ACTIVE",
     "Admin-only reads (any contract detail) and approval of a failed-message replay"],
])

set_par_text(plain(find(d, "[List the non-account entities")),
             "Seeded by backend/scripts/seed/ (01-product-db.sql, 02-user-db.sql) unless the row says otherwise.")
domain = next_table(find(d, "Seeded by backend/scripts/seed/"))
set_table_rows(domain, [
    ["User profile", "2", "buyer1 (BUYER) and seller1 (SELLER), both VERIFIED",
     "Contract and listing tests need verified parties"],
    ["Category", "4", "APPROVED: Ngũ cốc, Cà phê (commodity COFFEE), Gia vị, Trái cây",
     "Product creation; duplicate-name check (case and diacritics ignored)"],
    ["Product", "4", "One per seeded category, unit kg", "Listing browse and search"],
    ["Listing (seed)", "13", "6 AVAILABLE and 7 CLOSED, all of seller1; legacy rows with quantity_available NULL",
     "Browse and search only: a reservation on a legacy row fails closed"],
    ["Registry plot", "1", "POINT, MANUAL_PIN, geo check passed; created by the product-service Bruno run",
     "A listing must name the plot its goods come from"],
    ["Coffee listing for contract tests", "1",
     "AVAILABLE, COFFEE, at least 2,500.5 kg available; created by the product-service Bruno run or by hand, its id "
     "in listing_id and its listingVersionToken in listing_version_token",
     "Create-offer tests: the seeded listing 51ef… is 500 kg of rice and cannot be reserved"],
])

# ---------------------------------------------------------------- II.2 Test Cases
tc_intro = find(d, "[Prepare the details on the test cases")
set_par_text(plain(tc_intro), "The test cases are kept in two workbooks that follow the provided template:")
set_par_text(plain(find(d, "Unit Test Cases: Report5_Unit Test.xls")), "Unit Test Cases: Report-5.1_Unit_Test.xlsx")
set_par_text(plain(find(d, "Other Test Cases (IT, ST, AT)")),
             "Other Test Cases (IT, ST): Report-5.2_Test_Report.xlsx, the same template as Report-5.1 filled from "
             "the Bruno collection backend/bruno (scripts/unit-test-report/data_bruno.py), so far for api-gateway, "
             "user-service and product-service. Every Bruno case is an integration test (ITC); Pre-conditions say "
             "whether it calls the service directly or goes through the gateway, Note names the Bruno request and "
             "an empty Note means the case has no request yet. The other services add their sheets; ST cases follow "
             "the FE flows BF-01..BF-07, and AT cases are added after the mentor demo.")

# ---------------------------------------------------------------- Change log
fill_change_log(next_table(find(d, "Change Log", tag="w:p")),
                ["24/11/2026", "M", "Hai Long",
                 "Section I: test strategy and tools corrected to match the code (IT mocks, MockMvc levels, Boundary "
                 "type, Bruno cases are IT, JMeter and Playwright planned). Section II: test data, test case workbooks "
                 "(Report-5.2 added)"])

d.save(DOC)
print("saved", DOC.name)
