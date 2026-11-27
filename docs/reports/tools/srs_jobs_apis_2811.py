"""Report 3 III.10 and III.11 in business terms (external review 28/11): JOB-01, JOB-13, JOB-18, JOB-22, JOB-24,
JOB-28 and JOB-29 without outbox rows, conditional updates, nonces or dead-letter queues; the external APIs keep URL,
purpose, caller and auth as the template asks, without HTTP status codes, queue names, internal endpoints, service
names or the development mail server. Text inside a single run is replaced in that run; a table cell is rewritten.

    python srs_jobs_apis_2811.py
"""
import sys
from pathlib import Path

import docx
from docx.oxml.ns import qn

sys.path.insert(0, str(Path(__file__).parent))
from docxedit import set_cell_text, set_par_text, text_of  # noqa: E402

DOC = Path(__file__).resolve().parents[1] / "Report-3_Software Requirement Specification.docx"
d = docx.Document(DOC)
body = d.element.body
start = next(p for p in body.iter(qn("w:p")) if text_of(p).strip() == "10. Background Job Inventory")
end = next(p for p in body.iter(qn("w:p")) if text_of(p).strip() == "IV. Non-Functional Requirements"
           and p.getprevious() is not None)
section = []
el = start
while el is not None and el is not end:
    section.append(el)
    el = el.getnext()


def in_section(tag):
    for top in section:
        yield from top.iter(qn(tag))


def par(old: str, new: str, exact: bool = False):
    """Paragraph (outside tables) starting with old, or whose whole text is old when exact."""
    hits = [p for p in in_section("w:p") if p.getparent().tag != qn("w:tc")
            and (text_of(p).strip() == old if exact else text_of(p).strip().startswith(old))]
    if len(hits) != 1:
        raise ValueError(f"{old!r}: {len(hits)} paragraphs")
    set_par_text(hits[0], new)


def cell(old: str, new: str):
    hits = [tc for tc in in_section("w:tc") if text_of(tc).strip() == old]
    if len(hits) != 1:
        raise ValueError(f"{old!r}: {len(hits)} cells")
    set_cell_text(hits[0], new)


def cells(old: str, new: str):
    hits = [tc for tc in in_section("w:tc") if text_of(tc).strip() == old]
    if not hits:
        raise ValueError(f"{old!r}: no cell")
    for tc in hits:
        set_cell_text(tc, new)


# ---- JOB-01
par("10.1 Transactional Outbox Relay (JOB-01)", "10.1 Pending Event Delivery (JOB-01)", exact=True)
par("Purpose: Publish domain events and notification commands",
    "Purpose: Deliver to the other parts of the system the events and notification requests saved together with a "
    "business change, so that nothing is lost if the system stops right after the change.")
par("1. Read outbox rows not yet confirmed as published.", "1. Find saved events not yet confirmed as delivered.")
par("2. Publish each row to the message broker with its original event ID.",
    "2. Send each event with its original event ID.")
par("3. Mark the row as published after the broker confirms it.",
    "3. Mark the event as delivered once its receipt is confirmed.")
cell("Broker unavailable", "Messaging unavailable")
cell("The row stays unpublished and is retried on the next run; the business change is not rolled back",
     "The event stays pending and is sent on the next run; the business change is kept")
cell("Duplicate publish after a crash", "Event sent twice after a crash")
cell("Consumers skip a repeated event ID, so the side effect happens once",
     "A receiver ignores an event ID it has already processed, so the effect happens once")
cell("Unconfirmed outbox rows are sent again after the restore",
     "Events not confirmed as delivered are sent again after the restore")

# ---- JOB-13
par("2. Change each from RESERVED to RELEASED with a conditional update and restore the quantity.",
    "2. Change each from RESERVED to RELEASED and return the quantity to the listing; a reservation is released only "
    "once.")
cell("The conditional update restores the quantity only once", "The quantity is returned only once")

# ---- JOB-18, JOB-22, JOB-28
cell("3 retries with fixed backoff, then the dead-letter queue and a technical alert",
     "3 retries at fixed intervals, then the file is set aside for review and a technical alert is raised")
cell("Retried by JOB-28; a dead-lettered security or evidence email raises an alert",
     "Retried by JOB-28; a security or evidence email that still fails raises an alert")
par("1. Check the command and reserve its de-duplication key (PENDING).",
    "1. Check the request and record it as PENDING, so the same email is never sent twice.")
cell("FAILED and moved to the dead-letter queue", "FAILED and set aside for review (JOB-29)")
cell("Moved to the dead-letter queue at once", "Set aside for review at once (JOB-29)")
cell("Security or evidence email dead-lettered", "Security or evidence email set aside")

# ---- JOB-24
par("10.24 Used Nonce Cleanup (JOB-24)", "10.24 Expired Verifier Request Code Cleanup (JOB-24)", exact=True)
par("Purpose: Remove External Verifier nonces older than the 300-second timestamp window (GB-42).",
    "Purpose: Remove the one-time codes of External Verifier requests once they are older than the 5-minute window "
    "(GB-42).")
par("1. Delete nonce records older than the allowed window.", "1. Delete the codes older than the allowed window.")
cell("Retried on the next run; replay protection is not affected, because requests outside the window are rejected "
     "anyway",
     "Retried on the next run; a replayed request is still refused, because a request older than the window is "
     "rejected anyway")

# ---- JOB-29
par("10.29 Dead-Letter Queue Monitoring and Replay (JOB-29)", "10.29 Failed Message Review and Replay (JOB-29)",
    exact=True)
par("Trigger: Event-driven — when any message is dead-lettered;",
    "Trigger: Event-driven — when a message still fails after its retries; replay is started by the Operator, with "
    "Admin approval for money, security and evidence messages")
cell("The consumer skips it by event ID", "The receiver ignores it by its event ID")

# ---- API-01..API-03
cell("Yes: ES256 signature over the canonical signed payload {action: LOCK, reason, timestamp, nonce}; the key ID "
     "must match the registered public key; timestamp within ±300 seconds; single-use nonce (GB-42); no user login "
     "and no staff bypass; body max 64 KB; rate limit per IP (10 requests per minute)",
     "Yes: the request is signed with the External Verifier's private key (ES256) and checked with its registered "
     "public key; the request time must be within 5 minutes of the server time and each request code can be used "
     "once (GB-42); no user login and no staff bypass; at most 10 requests per minute from one address")
cells("ExternalVerifierRequest {keyId, signedPayload, signature}",
      "Key ID, signed content (action, reason, time, one-time code) and signature")
cell("204 locked; 401 MSG106 / MSG107 / MSG108; 409 MSG109 / MSG110",
     "Lock in place; refused with MSG106-MSG109 when the signature, key, time or code is not valid, MSG110 when "
     "already locked")
cell("204 unlocked; 401 MSG106 / MSG107 / MSG108; 409 MSG109 / MSG111",
     "Lock lifted; refused with MSG106-MSG109 when the signature, key, time or code is not valid, MSG111 when not "
     "locked")
cell("Yes: X-Api-Key header and rate limit; read-only",
     "Yes: an API key issued to the External Verifier; read-only; rate limited")
cell("200 {contractId, recordHash}; 401 MSG112; 404 MSG105",
     "The contract's current audit hash; MSG112 when the key is not valid, MSG105 when the contract does not exist")

# ---- API-04, API-05 (Custodian Bank, simulated in this version)
cell("Message queue (RabbitMQ): bank.lock_requested, bank.release_requested, bank.refund_requested, "
     "bank.seize_requested",
     "Asynchronous messages to the Custodian Bank: lock, release, refund and seize requests")
cell("AgriContract (escrow-service); the Custodian Bank is simulated by the mock bank-service",
     "AgriContract; the Custodian Bank is simulated in this version (Report 1, LI-01)")
cell("Yes: broker credentials of the internal service; every command carries a unique source event ID so the bank "
     "never executes it twice; amounts are always positive",
     "Yes: credentials issued to the platform; each command carries a unique ID so the bank never executes it "
     "twice; amounts are always positive")
cell("{contractId, milestoneId (optional), userId, amount, entryType, sourceEventId}",
     "Contract, milestone (optional), party, amount, type of movement and command ID")
cell("Asynchronous, see API-05; while the emergency lock is ACTIVE every command is answered as failed",
     "Answered later through API-05; while the emergency lock is active every command is answered as failed")
cell("Message queue (RabbitMQ): bank.lock_completed / bank.lock_failed, bank.release_completed / _failed, "
     "bank.refund_completed / _failed, bank.seize_completed / _failed; ledger read GET: "
     "/internal/v1/bank/ledger?contractId= or ?participantId=&from=&to=",
     "Asynchronous result messages from the Custodian Bank (completed or failed, for each command); ledger query "
     "by contract, or by party and period")
cell("Custodian Bank (mock bank-service)", "Custodian Bank (simulated in this version)")
cell("Yes: broker credentials; the ledger read uses the internal service secret and is not exposed through the API "
     "gateway",
     "Yes: credentials issued to the bank; the ledger query is open to the platform only, never to users")
cell("Result: {sourceEventId, result, reason}; ledger read: contract or participant and period",
     "Result: command ID, result and reason; ledger query: contract, or party and period")
cell("Ledger entries {entryType, fundType, contractId, milestoneId, amount, createdAt} (append-only)",
     "Ledger entries (type of movement, fund, contract, milestone, amount, time), never changed once written")

# ---- API-06..API-10: callers and providers without internal names
cell("SMTP to the configured mail provider (MailHog in development)", "SMTP to the platform's mail provider")
cell("AgriContract (notification-service)", "AgriContract")
cell("AgriContract (file-service) reads the mailbox; the Level 2 Inspection Organization sends the email",
     "AgriContract reads the mailbox; the Level 2 Inspection Organization sends the email")
cell("AgriContract (audit-service)", "AgriContract")
cell("AgriContract (pricing-service)", "AgriContract")
cell("AgriContract (product-service)", "AgriContract")

d.save(DOC)
print("saved", DOC.name)


# ---- the same wording elsewhere in the document (job list in I.5, data rules, actors, NFR)
def any_cell(old: str, new: str):
    hits = [tc for tc in body.iter(qn("w:tc")) if text_of(tc).strip() == old]
    if len(hits) != 1:
        raise ValueError(f"{old!r}: {len(hits)} cells")
    set_cell_text(hits[0], new)


any_cell("Transactional Outbox Relay (JOB-01)", "Pending Event Delivery (JOB-01)")
any_cell("Publish domain events and notification commands that were saved together with a business change, so no "
         "event is lost if the service stops right after the change",
         "Deliver the events and notification requests saved together with a business change, so nothing is lost if "
         "the system stops right after the change")
any_cell("Used Nonce Cleanup (JOB-24)", "Expired Verifier Request Code Cleanup (JOB-24)")
any_cell("Remove External Verifier nonces older than the 300-second timestamp window (GB-42)",
         "Remove the one-time codes of External Verifier requests older than the 5-minute window (GB-42)")
any_cell("Dead-Letter Queue Monitoring and Replay (JOB-29)", "Failed Message Review and Replay (JOB-29)")
any_cell("Monetary calculations use exact decimal arithmetic, rounded once to whole VND using HALF_UP and represented "
         "with two fractional digits. Remainders are derived by subtraction. Escrow and Bank do not round again.",
         "Money is calculated exactly, rounded once to the nearest whole VND with halves rounded up, and shown with "
         "two decimal places. A remainder is the total minus the parts, so the parts always add up. Payments and "
         "refunds are never rounded a second time (GB-51).")
any_cell("The bank that holds escrow funds, simulated by the mock bank-service. It executes lock, release, refund and "
         "seize commands and returns command results and ledger entries.",
         "The bank that holds escrow funds, simulated in this version. It executes lock, release, refund and seize "
         "commands and returns their results and the ledger entries.")
any_cell("Pending events are published by the outbox relay within 5 seconds of being written",
         "An event saved with a business change is delivered within 5 seconds")
any_cell("Scheduled relay running in each service that writes events", "Pending event delivery (JOB-01) running")
any_cell("Every bank command is published from the transactional outbox of the service that recorded the decision, "
         "so a command is never lost when a service restarts and never published twice for the same decision.",
         "Every bank command is saved together with the decision that causes it and delivered afterwards, so a "
         "command is never lost when the system restarts and never sent twice for the same decision.")
any_cell("Dead-letter monitoring and replay", "Failed message review and replay")
any_cell("A message that exhausts its retries is moved to a dead-letter queue that the Operator monitors daily, a "
         "money, security or evidence message raises an operational alert immediately, a replay keeps the original "
         "event identifier so consumers can skip what they already processed, and a message that cannot be recovered "
         "is parked with a reason.",
         "A message that still fails after its retries is set aside for the Operator, who reviews them daily; a money, "
         "security or evidence message raises an alert at once; a replay keeps the original event ID so nothing is "
         "processed twice; a message that cannot be recovered is closed with a reason.")
for p in body.iter(qn("w:p")):
    if text_of(p).startswith("3. System calculates the batch amount from the committed quantity and effective unit "
                             "price, rounding once to whole VND using HALF_UP."):
        set_par_text(p, "3. System calculates the batch amount from the committed quantity and effective unit price, "
                        "rounded once to the nearest whole VND (GB-51).")
        break
else:
    raise KeyError("HALF_UP step")

d.save(DOC)
print("saved", DOC.name, "(second pass)")
