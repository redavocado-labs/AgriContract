"""Report 2 brought in line with the code and Report 5 (external review 28/11): SeaweedFS instead of MinIO, the test
tools the project uses (Jest, Bruno CLI, Playwright; no Newman, no JaCoCo or c8 yet), Spring Cloud Gateway, the
acceptance row no longer claims a run that has not happened. Content only, styles kept.

    python r2_tools_2811.py
"""
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import append_row, find, set_cell_text, set_par_text, text_of  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-2_Project Management Plan_v1.docx"
d = docx.Document(DOC)


def cell_after(label: str, col: int = 1):
    for tr in d.element.body.iter(qn("w:tr")):
        tcs = tr.findall(qn("w:tc"))
        if tcs and text_of(tcs[0]).strip() == label:
            return tcs[col]
    raise KeyError(label)


def cell_with(start: str):
    for tc in d.element.body.iter(qn("w:tc")):
        if text_of(tc).strip().startswith(start):
            return tc
    raise KeyError(start)


set_par_text(find(d, "– Hanoi, Oct 2026 –"), "– Hanoi, Nov 2026 –")

set_cell_text(cell_with("The complete demonstration environment must run on a single machine"),
              "The complete demonstration environment must run on a single machine using Docker Compose, including "
              "the application services, API Gateway, MySQL, RabbitMQ, SeaweedFS, Keycloak, Redis, and ClamAV. The "
              "environment must therefore remain within the available machine resources.")

set_cell_text(cell_after("Application Technology"),
              "Web client: ReactJS / Vite; Backend: Java / Spring Boot microservices and Node.js services; Spring Cloud "
              "Gateway as the API gateway; Keycloak-based identity and roles")
set_cell_text(cell_after("Database & Object Storage"),
              "MySQL database/schema per service; SeaweedFS for managed file/evidence objects; Redis for supporting "
              "service functions")
set_cell_text(cell_after("Testing / Quality"),
              "JUnit 5, Mockito and Spring Boot Test for Java; Jest for Node.js; Testcontainers for MySQL/RabbitMQ "
              "integration testing; Bruno CLI for API tests; Playwright for tests through the web client")
set_cell_text(cell_after("Deployment / Runtime"),
              "Docker / Docker Compose for reproducible local/demo environments; RabbitMQ, MySQL, SeaweedFS, Keycloak, "
              "Redis and ClamAV as supporting infrastructure; API gateway at the system edge")

set_cell_text(cell_with("0 (no defect found at acceptance)"),
              "Not run yet: the 11 acceptance test scripts (Report 5.5) are run on 30/11/2026")

log = d.tables[0]._tbl
append_row(log, ["28/11/2026", "M", "Minh Cuong",
                 "Replaced MinIO with SeaweedFS and listed the test tools in use (Jest, Bruno CLI, Playwright); "
                 "acceptance test row marked as not run yet."])
d.save(DOC)
print("saved", DOC.name)
