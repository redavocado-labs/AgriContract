# file-service

Business-agnostic blob storage for every AgriContract service (`docs/design/file-service-phase2-design.md`):
the bytes go to S3-compatible object storage under a key file-service generates, and the technical metadata goes
to the `file` table in `file_db`. file-service never knows what a file is for: the meaning is in the field name of
the owning service (`cadastralExtractFileId`, `sellerEvidenceFileId`...).

NestJS 11 on Node 22, port **8089**. Internal only: the gateway never routes it.

## Status

#116: the scaffold, the store core and its one entrypoint so far, `POST /internal/v1/files` (`storeOnBehalfOf`,
request frozen by D60 in `contracts/events/supplementary-services-api.yaml`). #274: `GET`, `DELETE` and
`confirm-attached` on `/internal/v1/files/{fileId}`, and `store()` for `SYSTEM_GENERATED`
(`POST /internal/v1/files/system-generated`, D88). #131: the virus scan (ClamAV) and `file.ready` / `file.failed`.
#276: the lifecycle job (orphans, retention, legal hold, self-healing two-step deletion). Not done yet: email intake
(#275), and a way to set `retention_until` / `legal_hold` (the contract has none; the owning side's flows decide
them).

## `POST /internal/v1/files` (storeOnBehalfOf)

A business service (product-service, contract-service...) stores a file an end-user gave it. Service to service
only: the gateway never routes `/internal/**`. The body is the **raw file**, streamed straight to object storage;
there is no multipart and no JSON.

| Header | |
| --- | --- |
| `X-Internal-Secret` | `SERVICE_INTERNAL_SECRET`, as every `/internal` endpoint |
| `X-Acting-User-Id` | UUID of the end-user the caller acts for; stored (lower case) as `uploaded_by` |
| `Idempotency-Key` | 1 to 255 printable ASCII characters, compared byte for byte |
| `Content-Type` | The file's own type: `image/jpeg`, `image/png` or `application/pdf` |
| `Content-Length` | Required, 1 to 10485760 (10 MiB). No chunked body |

```bash
curl -H "X-Internal-Secret: $SERVICE_INTERNAL_SECRET" -H "X-Acting-User-Id: $USER_ID" \
     -H "Idempotency-Key: $KEY" -H "Content-Type: application/pdf" \
     --data-binary @extract.pdf http://localhost:8089/internal/v1/files
# 202 {"fileId":"…","status":"PROCESSING"}
```

| Status | `code` | When |
| --- | --- | --- |
| 202 | | Stored, `{fileId, status}`; or a repeat, see below |
| 400 | `VALIDATION_ERROR` | A missing or bad header, a type outside the list, an empty body, a chunked body |
| 401 | `UNAUTHORIZED` | Missing or wrong `X-Internal-Secret` |
| 413 | `FILE_TOO_LARGE` | `Content-Length` over 10 MiB |
| 503 | `FILE_STORAGE_UNAVAILABLE` / `FILE_METADATA_UNAVAILABLE` | Object storage or `file_db` down. Nothing kept; retry with the same key |

Errors use the shared `ErrorEnvelope` (`timestamp, status, error, code, message, path, traceId`). Messages name
headers, sizes and types only, never a header's value.

**Idempotency (D60).** The pair (`uploaded_by`, `idempotency_key`) is unique on `file`. A repeat of the pair answers
202 with the same `fileId` and the file's **current** status, and its body is not stored. Two concurrent requests with
the same pair both stream a blob, but only one row gets in: the other removes its blob and answers the winner's
`fileId`. The key belongs to the user, not to the calling service, since the shared secret does not say who calls.

**The unread body.** A request refused before its body is read (401, 400, a repeat), or a store that fails part way
(503), still has bytes on the way. When the declared length is within the cap, file-service reads them to the end
and throws them away **before** it answers: answering first would let Node close a `Connection: close` socket while
the caller is still sending, and the caller would get a reset instead of the answer. A body over the cap (413) or
without a length is not read; the answer carries `Connection: close`.

## `POST /internal/v1/files/system-generated` (storeSystemGenerated, `store()`)

A service stores a PDF it **generated itself**: the final contract PDF (contract-service), an EUDR report
(audit-service). Never a file that came from an end-user: that goes through `storeOnBehalfOf` and the virus scan, and
keeping that line is the calling service's job (design §2.1). Body, `Content-Length`, the 10 MiB cap, the unread
body and the `Idempotency-Key` rules are those of `storeOnBehalfOf`; what differs:

| | |
| --- | --- |
| `X-Service-Id` | `contract-service` or `audit-service`, exactly; stored as `uploaded_by`. Anything else is a 400 |
| `Content-Type` | `application/pdf` only |
| Channel | `SYSTEM_GENERATED`, fixed by the route |
| Answer | **201** `{fileId, status: "READY", storageHash, fileSize, contentType}`: no scan, so the file is `READY` at once and `GET` serves it; no `file.ready`/`file.failed` is published for it |

A repeat of (`X-Service-Id`, `Idempotency-Key`) answers 201 with the earlier file, without storing again. The shared
secret does not say which service is calling, so `X-Service-Id` is trusted like `X-Acting-User-Id`; the fixed list
only stops a caller from making up an uploader (design §9). The file still needs `confirm-attached` once the caller
kept its `fileId`, like any other.

## `/internal/v1/files/{fileId}`: read, confirm-attached, delete

`getFile`, `confirmAttached` and `deleteFile` of the contract (design §6, §7). Every route needs `X-Internal-Secret`;
a caller holding it is a trusted service. file-service does no business check: the owning service did it before it
proxies the bytes to its end-user (§6). A `{fileId}` that is not a UUID names no file: 404.

| Route | Answer |
| --- | --- |
| `GET` | 200: the exact stored bytes, with the file's `Content-Type` and `Content-Length`, `Cache-Control: no-store`. 404 `NOT_FOUND` unless the file is `READY` and not deleted: still scanning, `FAILED`, deleted and unknown look the same (inspection reads it as "not ready yet", D62). 503 `FILE_STORAGE_UNAVAILABLE` / `FILE_METADATA_UNAVAILABLE` |
| `POST …/confirm-attached` | 204: `attached = TRUE`, also on a repeat. A `PROCESSING` file can be confirmed. 404 for an unknown or deleted file. 400 without an `Idempotency-Key` |
| `DELETE` | 204: tombstone, then blob; also on a repeat. 409 `LEGAL_HOLD_ACTIVE`. 404 for an unknown file. 503 when storage did not remove the blob. 400 without an `Idempotency-Key` |

**The read is checked as it streams.** `VerifiedRead` hashes the bytes on their way out and compares length and
SHA-256 with the row (§2: `storage_hash` detects tampering in object storage). Only the end can tell, so the
latest chunk is held back until the next one comes or the hash matched: on a mismatch that last chunk never goes
out, the response is **cut** (fewer bytes than `Content-Length`, no clean end) and an error is logged. A caller that hashes what it got (inspection, D62) gets an error, never a wrong file
that looks complete. A `READY` row with no object is a 503 and an error log, not a 404: the file exists.

**confirm-attached and DELETE are idempotent by nature**, so their `Idempotency-Key` is checked but not stored.
DELETE runs the two steps of §7: (1) `deleted_at` + `deletion_reason` on the row, in one statement that also
requires `legal_hold = FALSE`, so a hold set meanwhile still wins; (2) the blob. If step 2 fails the answer is 503
and the repeat finishes it, since step 1 is already done. The row stays as the record of the deletion.

## Virus scan and `file.ready` / `file.failed`

Design §4–§5. Every `DIRECT_UPLOAD` file is `PROCESSING` until the scan decides; the owning service waits for the
event, never polls.

```
POST /internal/v1/files ── one transaction ──> file row PROCESSING + event_outbox file.uploaded.direct
OutboxPoller ──> agricontract.files (topic, routing key = eventType)
   file.uploaded.direct ──> file-service.virus-scan ──> VirusScanConsumer ──> clamd INSTREAM
   READY  + file.ready    ┐ one transaction, through the outbox,
   FAILED + file.failed   ┘ published to agricontract.files as well
```

- **Nothing is published directly.** The row change and its event commit together in `event_outbox` (V2, from
  `@agricontract/eventing`); `OutboxPoller` publishes committed rows with publisher confirms, at least once and
  with the same `eventId` on a repeat. A row written while RabbitMQ is down waits for it.
- **The scan reads the stored object back** and checks it on the way (`VerifiedRead`, same as `GET`) while clamd
  reads it, chunk by chunk under backpressure. clamd only gets the end-of-stream marker once every byte passed the
  check, so READY means "these exact bytes are clean".
- **Outcomes.** `stream: OK` → `READY` + `file.ready` (the five contract fields, no `storageHash`). A signature →
  `FAILED` + `file.failed` with `failureReason: "virus detected"` (the signature name only in the log). Bytes that
  are not the upload, or no object at all → `FAILED` too (`stored bytes do not match the upload` / `stored object
  missing`): waiting cannot fix them. Both events carry `causationId` = the scan job's `eventId`.
- **One outcome, one event.** The `UPDATE` only moves a `PROCESSING`, not deleted row, and the event is written only
  when it did. A redelivered or replayed job, or a second scan racing the first, publishes nothing.
- **A FAILED file cannot be attached**: `confirm-attached` answers 409 `FILE_FAILED`, checked again in the `UPDATE`
  so a scan failing the file meanwhile still wins. `GET` answers 404, as for any file that is not `READY`.
- **Retry.** A failure without a verdict (clamd, storage or the database down) is retried in-process 3 times, 5 s
  then 10 s apart, then the job is dead-lettered to `file-service.virus-scan.dlq` and the file stays `PROCESSING`.
  A job that can never work (not an envelope, not a scan job, no fileId) goes there at once. Replay a DLQ message
  by publishing its body and properties unchanged to `agricontract.files` with routing key `file.uploaded.direct`;
  the scan then runs again.
- **The DLQ is watched.** `DlqMonitor` checks `file-service.virus-scan.dlq` every `FILE_DLQ_CHECK_INTERVAL_MS` (5 min)
  and logs a warning with the queue name and message count while it holds messages, as notification-service does.
  The check is passive (`checkQueue`), never takes a message, and is off in the tests.

| Exchange / queue | Type | Bound with |
| --- | --- | --- |
| `agricontract.files` | topic | contract-service binds `contract-svc.file.ready` / `.failed` here |
| `agricontract.files.dlx` | direct | |
| `file-service.virus-scan` (DLX `agricontract.files.dlx`) | queue | `file.uploaded.direct` |
| `file-service.virus-scan.dlq` | queue | `file.uploaded.direct` on the DLX |

`file.uploaded.direct` is internal to file-service (design §4), not in `golden-flow-events.yaml`.
`test/contract/file-events.contract.spec.ts` checks `file.ready` / `file.failed` against `fileReady` / `fileFailed`
of the contract, both directions, so a contract change fails the build until the code follows.

## Lifecycle job (orphans, retention, legal hold)

Design §7, governance §8, matrix 21d / 21e (#276). `LifecycleScheduler` runs `FileLifecycleUseCase` a minute after
start and then every `FILE_LIFECYCLE_INTERVAL_MS` (hourly; `0` = off). One run, at most 100 rows per kind:

1. **Orphans**: not attached 7 days after the upload (nobody called confirm-attached) → tombstone
   (`ORPHAN: not attached within 7 days …`), then blob.
2. **Expired**: `retention_until` has passed → tombstone (`RETENTION_EXPIRED: …`), then blob. file-service does not
   decide retention; it applies the date the owning side set. `NULL` keeps the file.
3. **Pending blobs**: every tombstoned row whose blob is not marked gone (`blob_deleted_at`, V3), from this run, an
   earlier run or a `DELETE` whose blob step failed → blob removed and marked. So a failed step 2 heals on the
   next run, and a finished one is never redone.
4. **Stray blobs**: an object whose key is a fileId with no row at all (a store that failed after its PUT, and whose
   own clean-up failed too), older than a day → removed and logged by key. Other keys are never touched.

**A legal hold blocks every deletion**: candidates exclude held rows, and the tombstone `UPDATE` checks the hold
again together with the candidate's own condition (still unattached / still expired), so a hold or a
confirm-attached arriving meanwhile wins. Every step is idempotent, so several instances, or a run cut short, are
safe. A failed run is logged and the next one tries again.

## The store core

`StoreFileUseCase` is the one function every entrypoint will call (design §3). The entrypoint passes its own
`IngestPolicy`, so the ingest channel comes from the path the file took, never from the caller (§2.1):

| Policy | Channel | Content types | Cap |
| --- | --- | --- | --- |
| `DIRECT_UPLOAD_POLICY` | `DIRECT_UPLOAD` | `image/jpeg`, `image/png`, `application/pdf` | 10 MiB |
| `SYSTEM_GENERATED_POLICY` | `SYSTEM_GENERATED` | `application/pdf` | 10 MiB |

A policy also says whether its files are scanned (`virusScan`): a scanned file is recorded `PROCESSING`, an unscanned
one (`SYSTEM_GENERATED` only) `READY`.

For each store:

1. The content type is normalized (`Image/JPEG; x=y` -> `image/jpeg`) and checked, and the declared length must be
   a positive integer within the cap. Anything else is refused **before a byte is read**.
2. The fileId is a new UUID, and it is also the object key, in one flat bucket (§2.3).
3. The body goes to object storage as **one streamed `PutObject`** with the declared `Content-Length`. `ByteMeter`
   sits in between: it counts the bytes, computes the SHA-256 `storageHash`, and fails the stream as soon as the
   body sends a byte more than declared, or at the end if it sent fewer. Nothing is buffered whole: the meter holds
   back at most one chunk (see below).
4. Only then is the `file` row inserted: `PROCESSING` (or `READY` when the policy does not scan), `attached = FALSE`,
   `uploaded_by`, `ingest_channel`, `content_type`, the counted `file_size` and the `storage_hash`.

Failure handling:

- **A body that lies about its length** never leaves an object. The meter holds back the latest chunk until the
  next one arrives or the body ends with exactly the declared length. Storage commits an object once it has the
  declared `Content-Length`, so without the hold-back a too-long body would hand over the full length before its
  extra chunk showed up, and the object would be committed while the store fails. The int-spec caught this race on
  SeaweedFS.
- **A body that fails part way** (too short, caller gone) aborts the PUT at once. Otherwise the store would wait for
  the missing bytes until the 30 s socket timeout.
- **Blob first, row second**, because `storage_hash` is `NOT NULL` and comes from the stored bytes. If the row cannot
  be written, the blob is deleted, so a row never points at a missing blob. If that delete fails too, or the process
  dies in between, a blob is left with no row. The warning names the object key, and the lifecycle job's sweep
  removes it once it is a day old.
- Errors carry a code (`ECONNREFUSED`, `ER_...`, the S3 error name), never a message that could quote hosts, keys
  or the uploader.

## Configuration

Environment variables only (no `.env` file). The defaults match the compose services, so a local run needs none.

| Variable | Default | |
| --- | --- | --- |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `3317` / `file_db` | compose `file-db` |
| `DB_USERNAME` / `DB_PASSWORD` | `root` / `password` | |
| `S3_ENDPOINT` | `http://localhost:8333` | compose `object-storage` (SeaweedFS S3 API); `http(s)://host[:port]` |
| `S3_REGION` | `us-east-1` | |
| `S3_BUCKET` | `agricontract-files` | Created on startup when missing |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | `agricontract` / `agricontract-dev-secret` | Dev identity of `infra/seaweedfs/s3.json` |
| `SERVER_PORT` | `8089` | |
| `SERVICE_INTERNAL_SECRET` | `changeme-internal` | `X-Internal-Secret` of the callers. Compose requires it in `backend/.env` |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | compose `rabbitmq` |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `guest` / `guest` | |
| `CLAMAV_HOST` / `CLAMAV_PORT` | `localhost` / `3410` | compose `clamav` (host port 3410; `clamav:3310` inside compose) |
| `FILE_DLQ_CHECK_INTERVAL_MS` | `300000` | Period of the DLQ check, whole ms up to 24 h; `0` turns it off |
| `FILE_LIFECYCLE_INTERVAL_MS` | `3600000` | Period of the lifecycle job, 0 to 86400000; `0` turns it off |

Startup fails when MySQL or the bucket cannot be reached. `GET /actuator/health` answers 200 with
`components.db` and `components.storage` UP, or 503 with an error code for the one that is down. RabbitMQ and
clamd are not part of it: uploads and reads work without them (the outbox keeps the events, the scan job waits or
is dead-lettered). The broker connection is retried in the background (1 s … 30 s), like notification-service.

## Database

`db/migration/V1__create_file.sql` is the `file` table of design §8 in MySQL syntax, with the same columns,
defaults, CHECK constraint and indexes, including `idempotency_key` and its unique index with `uploaded_by` (D60).
`idempotency_key` is `ascii` / `ascii_bin`: MySQL's default collation ignores case, so `Key-A` and `key-a` would
otherwise be one upload. `email_intake_failure` comes with the email intake (#275). `V2__create_event_outbox.sql`
is the `event_outbox` of `@agricontract/eventing` (additive; its `processed_event` is left out, no consumer here
dedupes by eventId). `V3__add_blob_deleted_at.sql` marks step 2 of a deletion done (#276). The app does not
migrate: compose's `file-migrate` (Flyway 10.10) runs before `file-service`, and CI runs the same image.

## Run and test

```bash
# in backend/
docker compose up -d --wait file-db object-storage rabbitmq clamav   # clamav: ~1 GB RAM, healthy in ~40 s
docker compose up file-migrate
docker compose up -d --build file-service      # optional: the service itself on :8089
```

| Command (in `backend/file-service`) | |
| --- | --- |
| `npm run lint:check` | ESLint with Prettier |
| `npm test` | Unit tests, no Docker |
| `npm run test:int` | The store core, every `/internal/v1/files` route and the scan (EICAR ends FAILED, a clean file READY with one `file.ready`) on the compose `object-storage`, `file-db` (migrated), `clamav` and `rabbitmq`; `scan-pipeline.int-spec.ts` runs the whole app through the broker. Uses a bucket of its own per run and deletes it. Stop a compose `file-service` first, or it takes the scan jobs |
| `npm run build` | |

In Git Bash on Windows, put `MSYS_NO_PATHCONV=1` in front of a `docker run` that passes container paths such as
`-dir=/data`; otherwise they are rewritten to Windows paths.

## Layout

```
src/domain/stored-file.ts            the File aggregate (§2): channels, statuses, a newly stored file
src/application/store-file.use-case  the store core shared by every entrypoint
src/application/byte-meter.ts        count + SHA-256 + length enforcement while streaming
src/application/ingest-policy.ts     what each entrypoint accepts
src/application/read-file.use-case   GET; verified-read.ts checks the bytes on the way out
src/application/confirm-attached.use-case, delete-file.use-case
src/application/scan-file.use-case   the scan decision: READY / FAILED + its event
src/application/file-events.ts       file.uploaded.direct, file.ready, file.failed envelopes
src/application/file-lifecycle.use-case  one lifecycle run (§7); infrastructure/lifecycle/ schedules it
src/application/ports/               ObjectStoragePort, FileRepository, VirusScannerPort
src/infrastructure/messaging/        AMQP connection, topology, outbox poller, VirusScanConsumer
src/infrastructure/scan/             clamd INSTREAM client
src/infrastructure/http/             the /internal/v1/files routes: guard, headers, error envelope, unread body
src/infrastructure/storage/          S3 client and adapter, bucket creation on startup
src/infrastructure/persistence/      mysql2 pool, `file` repository
src/infrastructure/health/           /actuator/health
```
