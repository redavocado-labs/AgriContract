# Contributing to AgriContract

AgriContract is a capstone project with a fixed team, so these are the rules the team works by. They are written down so a reviewer, a new teammate or anyone reading the history can see why the code looks the way it does.

## The short version

1. Every change starts from an issue.
2. One branch, one pull request, exactly one `Closes #N`.
3. Code follows the frozen contracts in `contracts/**`; contracts and design change first, in their own pull request.
4. `ci-gate` green and one approval, then a rebase merge.

## Issues

Open an issue before writing code, using the **Task** or **Bug** template. Titles carry priority and area, for example `[P1][bank] Run bank-service with the append-only runtime account`. Add one `priority:*`, one `type:*` and one `service:*` label.

A task issue states the outcome, the owner, references (design section, decision number, related issues), acceptance criteria and a definition of done. Business choices that are still open are settled in the issue before anyone codes, and the decision is written down there.

## Branches and commits

Branch from `main` as `<type>/<issue>-<short-name>`: `feat/145-public-reputation-summary`, `fix/638-drop-milestone-flagged`, `docs/670-defect-log`, `test/527-ledger-duplicate-mysql`, `chore/523-require-peppers`, `ci/288-pricing-mysql-docker`.

Commits use [Conventional Commits](https://www.conventionalcommits.org/) with the service as scope:

```
feat(escrow): retry a failed remedy leg with its original command id
fix(contract): stop writing milestone.flagged, it stalled the outbox (#638)
docs(design): notification command payloads match the frozen contract (#660)
```

Keep commits small and meaningful on their own: one use case, endpoint or consumer per commit is a good default. Each commit should build.

## Pull requests

- Fill in [the template](.github/pull_request_template.md): summary, changes, what you ran, definition of done.
- Exactly one `Closes #N` in the description. The `pr-policy` job fails otherwise.
- One concern per pull request. No unrelated refactors, no second service.
- When `main` moves, the author rebases their own branch. Stacked pull requests are rebased onto `main` only after the one below has merged.
- Merges are **rebase merges** after one approval and a green `ci-gate`. Branch protection requires the branch to be up to date.

CI runs only the jobs whose paths changed. `ci-gate` is the one required check; it fails if any job that ran failed.

## Contracts and design are changed first

`contracts/**` (OpenAPI and event schemas) is frozen. `docs/design/**` and `docs/contracts/**` are the source of truth for behaviour. Only the maintainers edit these paths (decision D56), and CI enforces it.

If your work needs a contract or design change, say so in your issue with the proposal. A maintainer writes a separate docs pull request, and it merges **before** your code pull request. A code pull request never touches these paths.

When the code and a design document disagree, the design wins until a docs pull request changes it. When a design document and `contracts/**` disagree, the contract wins.

## Code rules

These hold in every service and are checked in CI by ArchUnit (Java) and dependency-cruiser (NestJS).

**Layers.** `domain` has no framework imports at all. `application` (use cases) may use annotations that only register a bean (`@Service`, `@Component`) and nothing else from Spring: no `@Transactional`, `@Async`, `@Scheduled`, no `TransactionTemplate` or `Pageable`. Transactions live in `infrastructure`, behind a port the use case calls, so the use case still decides what is atomic (decisions D44, D102). Dependencies point inwards only.

**Events.**

- Every event follows `contracts/events/event-envelope.yaml`. `eventId` is the dedup key in every consumer.
- Events leave through the transactional outbox, polled with `@Scheduled`. Never publish directly inside a business transaction.
- A consumer receives the raw message (`Map<String, Object>` in Java, a `Buffer` in NestJS), parses it by hand in infrastructure and maps it to an application command. A payload that cannot be parsed throws the non-retryable invalid-payload error and goes straight to the DLQ; every other error is retried.
- Each queue dead-letters to its consuming service's own DLX, with a `.dlq` queue on the same routing key and a `DlqMonitor` that warns while it holds messages.

**Money.** Only `escrow-service` calls `bank-service`. Retries reuse the original `eventId`. Nothing is marked locked or paid until the confirmation arrives. A contract reaches a terminal state only after its locked total is back to zero.

**Decisions, not accusations.** A flag, dispute or claim never moves money or reputation by itself. Only an attribution decision leads to `remedy.finalized`, which is the one trigger for penalty money and reputation locks.

**Roles.** `BUYER`, `SELLER`, `INSPECTOR`, `OPERATOR`, `ARBITRATOR` and `ADMIN` are separate. An admin never stands in for an operator or arbitrator.

**Database.** Flyway migrations are additive (`V<n>__<what>.sql`, next free number in that service). Never edit a migration that has merged.

## Tests

- Domain rules: unit tests, no Spring context.
- Persistence, messaging and anything that depends on MySQL behaviour (locks, unique keys, JSON columns): integration tests with Testcontainers.
- Event and API boundaries: contract tests against `contracts/**`, from both the producer and the consumer side.
- Business flows: a Bruno case under `backend/bruno/e2e/bf0x-*` for each new endpoint a flow uses.
- A change that touches money re-runs the BF-03 and BF-04 end-to-end flows before merge.

Say in the pull request which commands you ran and what they showed.

## Review

Review checks the diff against `docs/design/**` and `contracts/**`, reads the tests as well as the code, and runs them when the change needs it. Every finding is fixed before approval, minor ones included. Re-reviews check each earlier finding against the new diff.

## Security issues

Do not open a public issue for a vulnerability. See [SECURITY.md](SECURITY.md).
