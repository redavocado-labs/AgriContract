# file-service

Business-agnostic blob storage for every AgriContract service (`docs/design/file-service-phase2-design.md`):
the bytes go to S3-compatible object storage under a key file-service generates, and the technical metadata goes
to the `file` table in `file_db`. file-service never knows what a file is for: the meaning is in the field name of
the owning service (`cadastralExtractFileId`, `sellerEvidenceFileId`...).

NestJS 11 on Node 22, port **8089**. Internal only: the gateway never routes it.

## Status

#116: the scaffold, the store core and its one entrypoint so far, `POST /internal/v1/files` (`storeOnBehalfOf`,
request frozen by D60 in `contracts/events/supplementary-services-api.yaml`). #274: `GET`, `DELETE` and
`confirm-attached` on `/internal/v1/files/{fileId}`. Not done yet: virus scan and `file.ready`/`file.failed` (#131,
so every file stays `PROCESSING` and `GET` answers 404 for now), `store()` for `SYSTEM_GENERATED` (no path in the
contract yet), email intake (#275), the retention and orphan-cleanup jobs (#276).

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

## The store core

`StoreFileUseCase` is the one function every entrypoint will call (design §3). The entrypoint passes its own
`IngestPolicy`, so the ingest channel comes from the path the file took, never from the caller (§2.1):

| Policy | Channel | Content types | Cap |
| --- | --- | --- | --- |
| `DIRECT_UPLOAD_POLICY` | `DIRECT_UPLOAD` | `image/jpeg`, `image/png`, `application/pdf` | 10 MiB |

For each store:

1. The content type is normalized (`Image/JPEG; x=y` -> `image/jpeg`) and checked, and the declared length must be
   a positive integer within the cap. Anything else is refused **before a byte is read**.
2. The fileId is a new UUID, and it is also the object key, in one flat bucket (§2.3).
3. The body goes to object storage as **one streamed `PutObject`** with the declared `Content-Length`. `ByteMeter`
   sits in between: it counts the bytes, computes the SHA-256 `storageHash`, and fails the stream as soon as the
   body sends a byte more than declared, or at the end if it sent fewer. Nothing is buffered whole: the meter holds
   back at most one chunk (see below).
4. Only then is the `file` row inserted: `PROCESSING`, `attached = FALSE`, `uploaded_by`, `ingest_channel`,
   `content_type`, the counted `file_size` and the `storage_hash`.

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
  dies in between, a blob is left with no row. §7's orphan cleanup works from rows and cannot see it, so the
  warning names the object key for a manual delete.
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

Startup fails when MySQL or the bucket cannot be reached. `GET /actuator/health` answers 200 with
`components.db` and `components.storage` UP, or 503 with an error code for the one that is down.

## Database

`db/migration/V1__create_file.sql` is the `file` table of design §8 in MySQL syntax, with the same columns,
defaults, CHECK constraint and indexes, including `idempotency_key` and its unique index with `uploaded_by` (D60).
`idempotency_key` is `ascii` / `ascii_bin`: MySQL's default collation ignores case, so `Key-A` and `key-a` would
otherwise be one upload. `email_intake_failure` comes with the email intake (#275). The app does not
migrate: compose's `file-migrate` (Flyway 10.10) runs before `file-service`, and CI runs the same image.

## Run and test

```bash
# in backend/
docker compose up -d --wait file-db object-storage
docker compose up file-migrate
docker compose up -d --build file-service      # optional: the service itself on :8089
```

| Command (in `backend/file-service`) | |
| --- | --- |
| `npm run lint:check` | ESLint with Prettier |
| `npm test` | Unit tests, no Docker |
| `npm run test:int` | The store core and every `/internal/v1/files` route (whole app) on the compose `object-storage` and `file-db` (migrated). Uses a bucket of its own per run and deletes it |
| `npm run build` | |

In Git Bash on Windows, put `MSYS_NO_PATHCONV=1` in front of a `docker run` that passes container paths such as
`-dir=/data`; otherwise they are rewritten to Windows paths.

## Layout

```
src/domain/stored-file.ts            the File aggregate (§2): channels, statuses, a new PROCESSING file
src/application/store-file.use-case  the store core shared by every entrypoint
src/application/byte-meter.ts        count + SHA-256 + length enforcement while streaming
src/application/ingest-policy.ts     what each entrypoint accepts
src/application/read-file.use-case   GET; verified-read.ts checks the bytes on the way out
src/application/confirm-attached.use-case, delete-file.use-case
src/application/ports/               ObjectStoragePort, FileRepository
src/infrastructure/http/             the /internal/v1/files routes: guard, headers, error envelope, unread body
src/infrastructure/storage/          S3 client and adapter, bucket creation on startup
src/infrastructure/persistence/      mysql2 pool, `file` repository
src/infrastructure/health/           /actuator/health
```
