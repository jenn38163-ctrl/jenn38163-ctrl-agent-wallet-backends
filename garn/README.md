# Global Asset Registry Network — backend

Flask API with PostgreSQL persistence, an append-only hash-chained application ledger, registry/collateral records, a trust/card subledger, a general ledger, and optional partner bridges.

**This repository is not production-certified.** The code-level hardening in this revision does not establish live PostgreSQL behavior, partner certification, real-wallet compatibility, regulatory compliance, or financial-statement correctness. The release gates at the end of this document remain mandatory.

## Hardening changes in this revision

- Bearer tokens carry identity and expiry only; the current user role is loaded from the database on every protected request.
- Monetary values are validated as whole cents within the signed 64-bit database range. Event, card, and loan payment replays are checked for payload conflicts; overflowing disbursements are rejected.
- Simulated `/events` traffic is disabled in production. Webhook parsing rejects unknown event types, invalid amounts, and missing stable event IDs.
- Loan terms, software project names/useful lives, and journal entries receive stricter validation. Closed accounting periods cannot be backfilled.
- REMIC principal losses allocated to certificate holders now reduce the general-ledger certificate payable as well as the tranche subledger.
- The wallet bridge requires HTTPS outside local loopback development and will not follow redirects with the wallet bearer credential.
- SQLite foreign keys are enabled; PostgreSQL ledger row updates/deletes are guarded by an append trigger.

## Local development

1. Copy `.env.example` to `.env` and replace the local database passwords, `JWT_SECRET`, and initial admin password. Generate a signing key with:

   ```bash
   python -c "import secrets; print(secrets.token_urlsafe(48))"
   ```

2. Start the local-only databases and install the dependencies:

   ```bash
   docker compose up -d db
   pip install -r requirements.txt
   ```

3. Load the environment and start the API:

   ```bash
   set -a && . ./.env && set +a
   python app.py
   # or: gunicorn -w 1 'app:create_app()'
   ```

4. Run automated tests:

   ```bash
   python -m unittest -v
   ```

The local Docker Compose ports bind to loopback only. The Compose file and its credentials are for development, not production.

## Authentication and roles

`POST /auth/login` returns an HS256 bearer token with a one-hour lifetime. The token contains a subject and time claims, not an authorization role. Each protected request looks up the current user record and evaluates the current database role, so a role downgrade takes effect on the user's next request. A token for a deleted user is rejected.

Roles are `auditor` (read), `operator` (write), and `admin` (user management and selected administrative actions). The first administrator is bootstrapped only when the user table is empty; `ADMIN_USER` and a 12–1024 character `ADMIN_PASSWORD` must be supplied. Keep `JWT_SECRET` and all bootstrap/provider secrets in a managed secret store, not in source control.

This application does not currently provide distributed login throttling, MFA, delegated tenant administration, or a production-grade account recovery workflow. Put it behind a properly configured TLS ingress, WAF, and rate limiter before exposing it externally.

## API overview

| Area | Routes | Minimum role |
|---|---|---|
| Authentication | `POST /auth/login` | public |
| Users | `POST /users` | admin |
| Assets | `GET /assets`, `GET /assets/{id}` | auditor |
| Assets | `POST /assets`, `POST /assets/{id}/collateral`, `/channel`, `/anchor` | operator |
| Simulated events | `POST /events` | operator, development only |
| Webhooks | `POST /webhooks/{cross-river\|celtic\|marqeta}` | partner signature/authentication |
| Ledger | `GET /ledger`, `GET /ledger/verify` | auditor |

Amounts are integer cents and must fit a signed 64-bit database integer. `event_id` is required for simulated monetary events and partner webhooks. Reusing an ID with a different asset, event type, amount, or simulated/live flag returns a conflict instead of silently treating the changed request as a duplicate.

`POST /events` is disabled when `APP_ENV=prod` or `APP_ENV=production`. It is a development/test facility, not a way to record production bank or card activity.

Example asset request:

```json
{
  "issuer": "JMG",
  "asset_class": "RE",
  "vehicle": "TRUST",
  "series": "A",
  "year": 2026,
  "pool": "C1",
  "name": "Pool",
  "principal_cents": 250000000
}
```

## Trust registry, collateral, tokens, and cards

Workflow: register asset → add collateral → tokenize → issue units to verified parties → optionally open an asset-backed card line.

| Route | Minimum role |
|---|---|
| `GET /assets/{id}/credit`, `GET /assets/{id}/holdings` | auditor |
| `POST /assets/{id}/tokenize`, `POST /assets/{id}/issue`, `POST /parties`, `POST /transfers`, `POST /cards` | operator |
| `POST /parties/{id}/kyc` | admin |
| `GET /cards/{id}` | auditor |
| `POST /cards/{id}/authorize`, `POST /cards/{id}/repay` | operator |

Tokenization requires 100% collateral coverage. Units can be issued only to parties marked KYC-verified. Secondary transfers require the `Secondary` channel and verified sender and receiver. Card availability is calculated from the illustrative advance-rate table in `trust.py`; risk grade D blocks new lines. Advance rates are not a credit policy and must be replaced by an approved, governed policy before use.

Card authorizations and repayments require a stable `event_id`. Replays with the same action, amount, card and ID are idempotent; reusing an ID for a different payload is rejected. Real PANs and issuer-network authorization remain with a certified issuer processor. This registry card ledger is not itself a card network or issuer processor.

## General ledger, loans, and REMIC waterfall

| Area | Routes |
|---|---|
| Loans | `POST /loans`, `GET /loans`, `GET /loans/{id}`, `GET /loans/{id}/schedule`, `POST /loans/{id}/accrue`, `/payments`, `/writeoff` |
| REMIC cash-flow model | `POST /pools`, `GET /pools/{id}`, `POST /pools/{id}/waterfall` |
| Books | `POST /journal`, `GET /reports/trial-balance`, `/income-statement`, `/balance-sheet?period=YYYY-MM`, `POST /periods/{period}/close` (admin) |

Journal lines are whole non-negative cents, must balance, and may not contain both debit and credit on one line. Once a period is closed, postings to that period or any earlier period are rejected. Loan payment requests require an `event_id`; identical retries are recognized even after the loan has been paid off, while conflicting reuse is rejected.

The waterfall calculates tranche interest by seniority, allocates losses to the most junior tranche, pays principal to the most senior tranche, and assigns remaining cash to the residual holder. Principal losses allocated to certificate holders now reduce the general-ledger certificate payable and are recorded in a separate REMIC principal-loss recovery account. The model remains simplified: monthly accrual only, one currency, no full day-count or delinquency mechanics, and no REMIC tax-qualification tests. It is a calculation/workflow aid, not a REMIC tax opinion or a complete servicing system. Have qualified accounting and tax counsel review the rules before relying on any output.

## Investments, software costs, documents, and jurisdictions

| Area | Routes |
|---|---|
| Investments | `POST /investments/buy`, `/sell`, `/mark`, `/income`; `GET /investments` |
| Software costs | `POST /software/projects`, `/software/projects/{id}/costs`, `/in-service`, `/amortize`; `GET /software/projects` |
| Documents | `POST /documents`, `GET /documents`, `GET /documents/{id}` |
| Jurisdictions | `POST /jurisdictions` (admin), `GET /jurisdictions`, `GET /jurisdictions/{code}/route`, `GET /loans/{id}/compliance` |

Investment holdings use whole units and integer cents. Software-cost classification is a simplified policy and must be aligned to the entity's approved accounting policy. Document intake stores text content and extracted metadata in the database; binary content is currently hashed/classified by filename but is **not** stored as a retrievable binary object. Documents may contain personal or financial data: configure encryption, access controls, retention, backup, and key management before use with real records. Keyword extraction and state detection are heuristics, not legal document review. Only a placeholder `DEFAULT` jurisdiction rule ships; jurisdiction-specific rules require qualified counsel and controlled approval.

## Agent Wallet OS bridge

The wallet service is a separate application and must use its own database. The bridge supports card-linked wallet creation, balance checks, spend synchronization, and repayment/refill. It sends idempotency keys for wallet creation/funding and card-side events. The configured `AGENT_WALLET_URL` must use HTTPS outside loopback development; redirects are not followed so the bearer API key is not forwarded to a redirect target.

The bridge has been exercised against a local fake HTTP service only. That does **not** prove compatibility with the real TypeScript wallet backend or prove the create-wallet idempotency contract. Verify the actual endpoint paths, response schema, scopes, currency scale, idempotency semantics, partial-failure recovery, and reconciliation behavior in an isolated staging environment before any funding or production connection.

## Persistence and ledger integrity

PostgreSQL DDL is currently applied at application startup using `CREATE TABLE IF NOT EXISTS`; this is not a versioned migration system and does not provide schema checksums, reviewed migration plans, or rollback automation. The PostgreSQL path and its trigger must be tested against a disposable PostgreSQL database before use. The PostgreSQL trigger prevents ordinary `UPDATE` and `DELETE` operations on ledger rows, while the application verifies the hash chain. A database owner/superuser can still alter schema, triggers, or data; obtain independent signed external checkpoints if a stronger tamper-evidence requirement applies.

The current data model is **single-tenant**. It has no `tenant_id` authorization boundary, tenant-scoped uniqueness, or tenant-isolation tests. Do not expose this instance as a multi-tenant service or place unrelated customers' records in the same database until tenant isolation is designed, migrated, and independently tested.

## Production release gates — remain open

Do not connect a production bank, card processor, wallet, or financial database until all of these are evidenced:

1. **PostgreSQL validation:** run the full suite against an isolated, disposable PostgreSQL instance; verify indexes, constraints, trigger behavior, concurrent posting, recovery, and backup/restore.
2. **Migrations:** introduce a versioned migration tool/process with reviewed forward and rollback plans; stop using startup DDL as the deployment migration mechanism.
3. **Security operations:** managed secrets, TLS ingress, WAF/rate limits, login throttling, MFA or equivalent privileged-access controls, security monitoring, key rotation, and incident response.
4. **Tenant design:** formally decide whether this remains single-tenant; if multi-tenant is required, implement tenant-scoped authorization and verify cross-tenant read/write denial before loading data.
5. **Provider certification:** validate every bank/Marqeta payload and signature scheme against current partner documentation and an authorized certification environment. The adapter shapes in this starter are templates, not certifications.
6. **Wallet certification:** test the actual Agent Wallet OS service in staging, including concurrent creation, retries, partial failures, reconciliation and idempotency behavior.
7. **Financial controls:** independent review of credit policy, KYC/AML controls, GL mappings, loan servicing, REMIC waterfalls, tax qualification, custody and token/legal characterization.
8. **Data resilience:** encrypted document/object storage, retention and deletion policies, tested backups/restores, immutable external audit exports, observability, alerting and operational runbooks.

A passing SQLite suite or a successful application startup is not evidence that these production release gates have passed.
