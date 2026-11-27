# Testing rounds

| Round | Flow | TC ID | Description | Kind | Result | Evidence | Issue |
| --- | --- | --- | --- | --- | --- | --- | --- |
| run-20261127-1630 | BF01 | BF01-010 | Register buyer profile | IT-GW | Passed | bruno-bf01.json |  |
| run-20261127-1630 | BF01 | BF01-020 | Confirm buyer email | IT-GW | Passed | bruno-bf01.json |  |
| run-20261127-1630 | BF01 | BF01-030 | Operator opens buyer KYC profile | IT-GW | Passed | bruno-bf01.json |  |
| run-20261127-1630 | BF01 | BF01-040 | Operator marks buyer KYC VERIFIED | IT-GW | Passed | bruno-bf01.json |  |
| run-20261127-1630 | BF01 | BF01-060 | Register seller profile | IT-GW | Passed | bruno-bf01.json |  |
| run-20261127-1630 | BF01 | BF01-070 | Confirm seller email | IT-GW | Passed | bruno-bf01.json |  |
| run-20261127-1630 | BF01 | BF01-080 | Operator opens seller KYC profile | IT-GW | Passed | bruno-bf01.json |  |
| run-20261127-1630 | BF01 | BF01-090 | Operator marks seller KYC REJECTED | IT-GW | Passed | bruno-bf01.json |  |
| run-20261127-1630 | BF01 | BF01-DLQ | RabbitMQ dead-letter queues are empty | IT-DIRECT | Passed | queues-bf01.json |  |
| run-20261127-1630 | BF02 | BF02-010 | Resolve an approved coffee category | IT-GW | Passed | bruno-bf02.json |  |
| run-20261127-1630 | BF02 | BF02-020 | Create a Robusta product | IT-GW | Passed | bruno-bf02.json |  |
| run-20261127-1630 | BF02 | BF02-030 | Register one manual land plot | IT-GW | Passed | bruno-bf02.json |  |
| run-20261127-1630 | BF02 | BF02-040 | Wait for the plot geo check | IT-GW | Passed | bruno-bf02.json |  |
| run-20261127-1630 | BF02 | BF02-050 | Create an available listing | IT-GW | Passed | bruno-bf02.json |  |
| run-20261127-1630 | BF02 | BF02-060 | Update quantity and price | IT-GW | Passed | bruno-bf02.json |  |
| run-20261127-1630 | BF02 | BF02-070 | Read public listing through the gateway | IT-GW | Passed | bruno-bf02.json |  |
| run-20261127-1630 | BF02 | BF02-DLQ | RabbitMQ dead-letter queues are empty | IT-DIRECT | Passed | queues-bf02.json |  |
| run-20261127-1630 | BF03 | BF03-010 | Create a coffee offer | IT-GW | Passed | bruno-bf03.json |  |
| run-20261127-1630 | BF03 | BF03-020 | Confirm listing quantity is reserved | IT-GW | Passed | bruno-bf03.json |  |
| run-20261127-1630 | BF03 | BF03-030 | Revise the offered unit price | IT-GW | Passed | bruno-bf03.json |  |
| run-20261127-1630 | BF03 | BF03-040 | Request signing code as buyer | IT-GW | Passed | bruno-bf03.json |  |
| run-20261127-1630 | BF03 | BF03-050 | Sign offer as buyer | IT-GW | Passed | bruno-bf03.json |  |
| run-20261127-1630 | BF03 | BF03-060 | Request signing code as seller | IT-GW | Passed | bruno-bf03.json |  |
| run-20261127-1630 | BF03 | BF03-070 | Sign offer as seller | IT-GW | Passed | bruno-bf03.json |  |
| run-20261127-1630 | BF03 | BF03-080 | Wait for ACTIVE | IT-GW | Passed | bruno-bf03.json |  |
| run-20261127-1630 | BF03 | BF03-090 | Verify the two deposit locks | IT-DIRECT | Passed | bruno-bf03.json |  |
| run-20261127-1630 | BF03 | BF03-110 | Create a coffee offer | IT-GW | Passed | bruno-bf03.json |  |
| run-20261127-1630 | BF03 | BF03-120 | Confirm listing quantity is reserved | IT-GW | Passed | bruno-bf03.json |  |
| run-20261127-1630 | BF03 | BF03-130 | Withdraw the unsigned offer | IT-GW | Passed | bruno-bf03.json |  |
| run-20261127-1630 | BF03 | BF03-140 | Confirm WITHDRAWN | IT-GW | Passed | bruno-bf03.json |  |
| run-20261127-1630 | BF03 | BF03-150 | Verify the reservation is released | IT-GW | Passed | bruno-bf03.json |  |
| run-20261127-1630 | BF03 | BF03-160 | Confirm withdrawal left no locked money | IT-DIRECT | Passed | bruno-bf03.json |  |
| run-20261127-1630 | BF03 | BF03-DLQ | RabbitMQ dead-letter queues are empty | IT-DIRECT | Passed | queues-bf03.json |  |
| run-20261127-1630 | BF04 | BF04-010 | Wait for a locked milestone | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-015 | Confirm the batch lock | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-017 | Assert audit record contract.signed | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-020 | Upload evidence as seller | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-030 | Record seller weight | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-035 | Assert audit record milestone.seller_weighed | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-040 | Upload evidence as buyer | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-050 | Record buyer weight | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-055 | Assert audit record milestone.buyer_received | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-060 | Buyer confirms clean receipt | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-065 | Assert audit record milestone.buyer_confirmed | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-070 | Wait for clean settlement | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-085 | Assert audit record milestone.settled | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-090 | Verify WEIGHED and CONFIRMED mail recipients | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-091 | Record the final ledger before retrying confirmation | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-092 | Retry clean confirmation with its original idempotency key | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-093 | Confirm the exact retry leaves the bank ledger intact | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-110 | Wait for a locked milestone | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-115 | Confirm the batch lock | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-117 | Assert audit record contract.signed | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-120 | Upload evidence as seller | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-130 | Record seller weight | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-135 | Assert audit record milestone.seller_weighed | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-140 | Upload evidence as buyer | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-150 | Record buyer weight | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-155 | Assert audit record milestone.buyer_received | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-160 | Buyer flags a quality issue | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-170 | Seller contests the reported quality defect | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-175 | Assert audit record breach.reported | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-180 | Assigned inspector uploads report evidence | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-190 | Inspector submits a Level 1.5 report | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-195 | Assert audit record inspection.report_confirmed | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-200 | Arbitrator reads the computed quality remedy | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-210 | Arbitrator opens the breach review | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-220 | Arbitrator finalizes the no-fault remedy | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-225 | Assert audit record remedy.finalized | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-230 | Confirm the disputed contract settled without a clean settlement event | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-240 | Verify seller proceeds and buyer refund | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-250 | Advance a separate fixture receipt and wait for system confirmation | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-310 | Wait for final contract settlement | IT-GW | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-320 | Verify the seller was paid and both deposits returned | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-330 | Verify completion events in the party audit trail | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-340 | Verify the final chain is intact | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-350 | Check anchor queue topology and empty DLQ | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-360 | Check anchor routing and retired flag binding | IT-DIRECT | Passed | bruno-bf04.json |  |
| run-20261127-1630 | BF04 | BF04-390 | Read the final party audit trail through the gateway | IT-GW | Pending | bruno-bf04.json | #630 |
| run-20261127-1630 | BF04 | BF04-DLQ | RabbitMQ dead-letter queues are empty | IT-DIRECT | Passed | queues-bf04.json |  |
| run-20261127-1630 | BF05 | BF05-010 | Read the Arbitrator breach queue | IT-GW | Passed | bruno-bf05.json |  |
| run-20261127-1630 | BF05 | BF05-DLQ | RabbitMQ dead-letter queues are empty | IT-DIRECT | Passed | queues-bf05.json |  |
| run-20261127-1630 | BF06 | BF06-DLQ | RabbitMQ dead-letter queues are empty | IT-DIRECT | Passed | queues-bf06.json |  |
| run-20261127-1630 | BF07 | BF07-010 | Read a fixture account lock summary | IT-GW | Passed | bruno-bf07.json |  |
| run-20261127-1630 | BF07 | BF07-DLQ | RabbitMQ dead-letter queues are empty | IT-DIRECT | Passed | queues-bf07.json |  |
| run-20261127-1630 | Security | SEC-010 | A legitimate Buyer session gets its own profile | IT-GW | Passed | bruno-security.json |  |
| run-20261127-1630 | Security | SEC-020 | A protected profile requires an authenticated session | IT-GW | Failed | bruno-security.json | #663 |
| run-20261127-1630 | Security | SEC-040 | BUYER reads kyc queue | IT-GW | Failed | bruno-security.json | #663 |
| run-20261127-1630 | Security | SEC-041 | SELLER reads kyc queue | IT-GW | Failed | bruno-security.json | #663 |
| run-20261127-1630 | Security | SEC-042 | INSPECTOR reads kyc queue | IT-GW | Failed | bruno-security.json | #663 |
| run-20261127-1630 | Security | SEC-043 | OPERATOR reads kyc queue | IT-GW | Passed | bruno-security.json |  |
| run-20261127-1630 | Security | SEC-044 | ARBITRATOR reads kyc queue | IT-GW | Failed | bruno-security.json | #663 |
| run-20261127-1630 | Security | SEC-045 | ADMIN reads kyc queue | IT-GW | Failed | bruno-security.json | #663 |
| run-20261127-1630 | Security | SEC-060 | BUYER reads arbitration queue | IT-GW | Failed | bruno-security.json | #663 |
| run-20261127-1630 | Security | SEC-061 | SELLER reads arbitration queue | IT-GW | Failed | bruno-security.json | #663 |
| run-20261127-1630 | Security | SEC-062 | INSPECTOR reads arbitration queue | IT-GW | Failed | bruno-security.json | #663 |
| run-20261127-1630 | Security | SEC-063 | OPERATOR reads arbitration queue | IT-GW | Failed | bruno-security.json | #663 |
| run-20261127-1630 | Security | SEC-064 | ARBITRATOR reads arbitration queue | IT-GW | Passed | bruno-security.json |  |
| run-20261127-1630 | Security | SEC-065 | ADMIN reads arbitration queue | IT-GW | Failed | bruno-security.json | #663 |
| run-20261127-1630 | Security | SEC-080 | Retry the same valid offer with the original key | IT-GW | Passed | bruno-security.json |  |
| run-20261127-1630 | Security | SEC-090 | Read inventory after the retried offer | IT-GW | Passed | bruno-security.json |  |
| run-20261127-1630 | Security | SEC-100 | Admin verifies the global audit chain | IT-GW | Passed | bruno-security.json |  |
| run-20261127-1630 | Security | SEC-110 | Operator role is denied global verification | IT-GW | Failed | bruno-security.json | #663 |
| run-20261127-1630 | Security | SEC-120 | Repeated incorrect passwords temporarily lock a new Keycloak account | IT-DIRECT | Passed | bruno-security.json |  |
| run-20261127-1630 | Security | SEC-130 | Party reads the minimized trail | IT-DIRECT | Passed | bruno-security.json |  |
| run-20261127-1630 | Security | SEC-140 | Admin reads the same contract trail | IT-GW | Passed | bruno-security.json |  |
| run-20261127-1630 | Security | SEC-150 | Admin read logging records one system event per read | IT-GW | Passed | bruno-security.json |  |
| run-20261127-1630 | Security | SEC-160 | Admin reads preserve the party trail | IT-DIRECT | Passed | bruno-security.json |  |
| run-20261127-1630 | Security | SEC-170 | Verify exactly two audit records were appended | IT-GW | Passed | bruno-security.json |  |
| run-20261127-1630 | Security | SEC-180 | Reject a negative contract page index | IT-GW | Passed | bruno-security.json |  |
| run-20261127-1630 | Security | SEC-190 | Validation errors omit system internals | IT-GW | Passed | bruno-security.json |  |
| run-20261127-1630 | Security | Security-DLQ | RabbitMQ dead-letter queues are empty | IT-DIRECT | Passed | queues-security.json |  |
| run-20261127-1630 | BF01 | BF01-100 | Purge unverified Keycloak accounts | IT-DIRECT | Pending | Keycloak creation timestamp drives the purge; no time-shift fixture is wired. |  |
| run-20261127-1630 | BF03 | BF03-170 | Failed deposit lock, operator retry, and bilateral activation cancellation | IT-GW | Pending | No deterministic insufficient-funds fixture was exercised. |  |
| run-20261127-1630 | BF04 | BF04-260 | Seller silence and fault-based contest expiry | IT-GW | Pending | Time-dependent cases not exercised. |  |
| run-20261127-1630 | BF04 | BF04-270 | OTP concession and agreed dispute settlement | IT-GW | Pending | D92 subflows not exercised. |  |
| run-20261127-1630 | BF04 | BF04-280 | Funding reminder and funding failure mail | IT-GW | Pending | Future funding schedule and failure fixture not exercised. | #239 |
| run-20261127-1630 | BF04 | BF04-290 | Pre-receipt reminder email | IT-GW | Pending | Time-dependent case not exercised. |  |
| run-20261127-1630 | BF04 | BF04-370 | Milestone settlement email with .ots | IT-GW | N/A | Audit-service has not implemented publishing notification.milestone_anchor_requested. |  |
| run-20261127-1630 | BF04 | BF04-380 | Other audit and security notifications | IT-GW | Pending | Notification consumer work is pending. | #278 |
| run-20261127-1630 | BF05 | BF05-020 | Funding cure and buyer funding breach | IT-GW | N/A | Implementation is pending. | #239 |
| run-20261127-1630 | BF05 | BF05-030 | Basket A timeout breaches and Delta 1 | IT-GW | N/A | Implementation is pending. | #240 |
| run-20261127-1630 | BF05 | BF05-040 | Termination for breach | IT-GW | N/A | Implementation is pending. | #242 |
| run-20261127-1630 | BF05 | BF05-050 | Force majeure claim, review and contest | IT-GW | N/A | Implementation is pending. | #244 |
| run-20261127-1630 | BF06 | BF06-010 | Mutual termination | IT-GW | N/A | No matching controller. | #242 |
| run-20261127-1630 | BF06 | BF06-020 | Mutual replacement and supersession | IT-GW | N/A | No matching controller. | #243 |
| run-20261127-1630 | BF07 | BF07-020 | External Verifier emergency lock and unlock | IT-GW | N/A | Bank has no controller for the frozen lock and unlock paths. |  |
| run-20261127-1630 | BF07 | BF07-030 | User lock propagation | IT-GW | Pending | Reputation read exists; a lock-producing fixture was not exercised. |  |

## Totals

| Flow | Passed | Failed | Pending | N/A |
| --- | ---: | ---: | ---: | ---: |
| BF01 | 9 | 0 | 1 | 0 |
| BF02 | 8 | 0 | 0 | 0 |
| BF03 | 16 | 0 | 1 | 0 |
| BF04 | 46 | 0 | 6 | 1 |
| BF05 | 2 | 0 | 0 | 4 |
| BF06 | 1 | 0 | 0 | 2 |
| BF07 | 2 | 0 | 1 | 1 |
| Security | 15 | 12 | 0 | 0 |
| Total | 99 | 12 | 9 | 8 |
