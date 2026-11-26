# Security

AgriContract is a capstone project. It holds no real money and no real users, but it is built as if it did: the point is that neither party, and not the platform's own administrator, can quietly change what was agreed or where the money went.

## Reporting a vulnerability

Please do not open a public issue.

Report it privately through GitHub: **Security → Report a vulnerability** on this repository. Include what you found, how to reproduce it, and which service or endpoint it affects. You will get an answer within a few days; a confirmed issue is fixed on `main` and credited in the fix unless you prefer otherwise.

Only `main` is supported. The project runs locally and in demos; there is no public deployment to test against, so please test on your own stack (see the [README](README.md#getting-started)).

## Threat model in one paragraph

The parties may lie about what was agreed or delivered. The administrator may be tempted to edit the database. Messages may be delivered twice or lost. The design answer is to make every important fact either impossible to change in place or detectable when changed, to keep copies of the key hashes outside the platform, and to let only recorded decisions move money. The full reasoning is in [`docs/design/hash-chain-phase2-design.md`](docs/design/hash-chain-phase2-design.md) and [`docs/design/bank-service-phase2-design.md`](docs/design/bank-service-phase2-design.md) §3.5.

## Mechanisms

| Mechanism | What it protects | Status |
|---|---|---|
| **Signed terms hash.** Both signatures, each confirmed by an OTP, cover one SHA-256 hash of the same terms; terms cannot change after signing | What was agreed | Built |
| **Signed contract by email.** The final PDF and its hash are mailed to both parties | A copy of the contract outside the platform | Built |
| **Append-only ledger.** Every money movement is one immutable row; balances are derived; duplicates are rejected by the request's `eventId` | Where the money went | Built |
| **Ledger database account with INSERT/SELECT only** | The ledger against an edit through the service's own account | Designed ([#525](https://github.com/redavocado-labs/AgriContract/issues/525)) |
| **Inspection report hash.** Content, source and timestamp hashed when the report is confirmed | Inspection evidence | Built |
| **Hash-chained audit trail.** Each record includes the hash of the previous one; parties and admins can verify a contract's chain | Who did what, and when | Built (party route through the gateway: [#630](https://github.com/redavocado-labs/AgriContract/issues/630)) |
| **Weekly chain check with digest and failure emails** to the admin and the software buyer's contacts, with no approval step | That someone outside notices a broken chain | Email side built; audit job designed |
| **OpenTimestamps anchor** of the chain head per settled milestone | A timestamp the platform does not control | Email side built; anchoring designed |
| **External Verifier kill switch.** A signed lock stops every lock, release, seizure and refund; only a signed unlock lifts it, never the admin | Money while tampering is investigated | Designed ([#252](https://github.com/redavocado-labs/AgriContract/issues/252)) |
| **Gateway and service secrets.** Downstream services accept only requests carrying the internal secret; no default secrets in Compose | Services reached around the gateway | Built |
| **Login and OTP hardening.** Keycloak brute-force protection and password policy; OTP and email codes stored as peppered hashes | Accounts and signatures | Built |
| **Roles without fallback.** Admin cannot act as operator or arbitrator; read rights follow the data-governance matrix | Separation of duties | Built |

## What is not done yet

These are known and tracked:

- Database, RabbitMQ and storage ports are open on the host in the default Compose file ([#526](https://github.com/redavocado-labs/AgriContract/issues/526)).
- Services share one RabbitMQ account; per-service accounts limited to their own exchanges are planned ([#544](https://github.com/redavocado-labs/AgriContract/issues/544)), mTLS later.
- Replaying a dead-lettered money message has no maker-checker yet ([#258](https://github.com/redavocado-labs/AgriContract/issues/258)).
- The gateway's 401 and 403 responses have an empty body instead of the error envelope ([#663](https://github.com/redavocado-labs/AgriContract/issues/663)).
- Email goes to MailHog; there is no real SMTP provider, so no SPF/DKIM.
- Signatures are OTP-based, not CA-backed digital signatures. This is a deliberate scope limit.
