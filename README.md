# Banco Kokunas - bankdemo

IBM Concert remediation-lifecycle demo: a small banking application
(mortgage-loan simulation/application, funds transfers, customer search)
built with Spring Boot, deliberately shipping with a **known CVE**
(Log4Shell, CVE-2021-44228/CVE-2021-45046) and an **unknown/non-catalogued
vulnerability** (SQL Injection, CWE-89), plus an isofunctional test suite
used to prove that automated remediation doesn't break the app.

It exists to demonstrate the full Concert lifecycle: **scan -> prioritize
-> auto-remediate -> verify -> notify**, using real [Concert
Workflows](concert-workflows/) (not GitHub Actions) for the automation.

## The app

- **Dashboard** (`/`) - portfolio overview: customers, mortgage volume,
  transfers.
- **Mortgage simulator** (`/loans/new`) - French-amortization monthly
  payment calculator + 80% loan-to-value eligibility check + application
  submission (`/loans`, `/loans/{id}`).
- **Transfers** (`/transfers`, `/transfers/new`) - IBAN-to-IBAN transfer
  creation and ledger.
- **Customer search** (`/search`) - branch-office quick lookup by name/NIF.
  **This is the vulnerable endpoint** (see below).

Stack: Spring Boot 3.2 (Java 17), Thymeleaf, Spring Data JPA, PostgreSQL 16
(H2 in tests), Maven.

## The vulnerabilities

| Class | Where | Detail |
|---|---|---|
| **Known CVE** | [`pom.xml`](pom.xml) | `log4j-core`/`log4j-api` pinned to `2.14.1` (CVE-2021-44228 "Log4Shell", CVE-2021-45046). Reachable sink: [`AuditLogger`](src/main/java/com/kokunas/bankdemo/config/AuditLogger.java) logs the `X-Channel` request header via `org.apache.logging.log4j.LogManager` on every `/search` call. |
| **Unknown / non-CVE** | [`VulnerableSearchRepository`](src/main/java/com/kokunas/bankdemo/repository/VulnerableSearchRepository.java) | CWE-89 SQL Injection: `searchCustomers(term)` concatenates user input directly into the SQL string. Confirmed locally: `GET /search?query=zzznomatch` returns nothing, `GET /search?query=' OR '1'='1` dumps every customer row. Trivy's SCA scan will **not** catch this - it's not a dependency, it's how the app talks to the database. |

## Isofunctional tests

[`LoanServiceTest`](src/test/java/com/kokunas/bankdemo/LoanServiceTest.java)
and
[`IsofunctionalWebTests`](src/test/java/com/kokunas/bankdemo/IsofunctionalWebTests.java)
(11 tests total) describe observable business behavior - mortgage
simulation math, eligibility rules, transfer creation, customer search -
independent of implementation. They pass unchanged both **before and
after** the log4j and SQLi fixes (verified locally during development, see
[concert-workflows/02](concert-workflows/02-remediate-log4j) and
[03](concert-workflows/03-remediate-sqli)), which is what "isofunctional"
means here: the remediation changes *how* the code does its job, not
*what* it does.

Run them: `mvn test`

## Running locally

```bash
docker compose up --build
```

Then open <http://localhost:8080>. Postgres runs on an `internal: true`
compose network (not published beyond `5432` on the host for local dev
convenience) - see [k8s/](k8s) for the production-shaped topology where
the database has no external exposure at all.

Try the SQLi live:

```bash
curl -s "http://localhost:8080/search?query=zzznomatch"          # no results
curl -s -G "http://localhost:8080/search" --data-urlencode "query=' OR '1'='1"  # dumps all customers
```

## Container image & topology (for Concert's Arena view)

- **Source repository**: <https://github.com/kokunas/java-app-cve>
- **Container artifact**: built by [`Dockerfile`](Dockerfile), intended to
  be published to `ghcr.io/kokunas/java-app-cve` (GitHub Container
  Registry - wire up a `docker build && docker push` step, or Concert's own
  image-scanning integration, once you're ready to publish).
- **Deployment topology**: [`k8s/`](k8s) - a `banco-kokunas` namespace with:
  - `bankdemo-app` Deployment + `Service`/`Ingress` labeled
    `concert.ibm.com/exposure: public` (the web UI) - see
    [k8s/deployment.yaml](k8s/deployment.yaml) and
    [k8s/service-public.yaml](k8s/service-public.yaml).
  - `bankdemo-db` (Postgres) Deployment + `ClusterIP` `Service` labeled
    `concert.ibm.com/exposure: private` - see
    [k8s/postgres.yaml](k8s/postgres.yaml).

Register `bankdemo` as an application in Concert pointing at these three
resources (repo, image, K8s deployment) so the Arena view renders the full
map: application name, public vs. private endpoints, the container
artifact, and the source repo, all linked together.

## The remediation lifecycle

All automation lives in [concert-workflows/](concert-workflows/) as real,
importable Concert Workflow JSON/zip definitions. See that folder's README
for the full table, trigger payloads, and what was verified (against the
real repo and the real Concert API) before writing each one. **Live demo
shape: one reset, then two triggers:**

0. **Reset (run before every demo)**:
   [`Reset_Demo`](concert-workflows/Reset_Demo) - reverts the code to the
   [`vulnerable-baseline`](https://github.com/kokunas/java-app-cve/releases/tag/vulnerable-baseline)
   tag and deletes every application/source_repo/build_artifact/
   environment/certificate from the Concert instance, so it's genuinely
   empty before you start.
1. **Scan**: [`Trivy_GitHub_Scan`](concert-workflows/01-scan) - trigger
   this live, in front of the audience: Concert connects to GitHub, scans
   with Trivy, and the `bankdemo` application appears with real CVEs.
2. **Prioritize**: done in the Concert console (Vulnerability dimension /
   Arena view) - no workflow needed, this is Concert scoring each finding
   against `bankdemo`'s topology and business criticality (explain why
   CVE-2021-44228 outranks its siblings despite similar CVSS).
3. **Remediate + merge + verify + notify, nested**:
   [`Remediate_All`](concert-workflows/Remediate_All) - one trigger opens
   the log4j PR and the SQLi PR, **auto-merges both** via the GitHub REST
   API (no native "merge PR" block exists in Concert's catalog, confirmed
   against IBM's published samples - this calls GitHub directly), then
   runs the isofunctional test suite and a fresh Trivy scan against `main`
   and emails the outcome.

The four individual stage workflows ([01](concert-workflows/01-scan),
[02](concert-workflows/02-remediate-log4j),
[03](concert-workflows/03-remediate-sqli),
[04](concert-workflows/04-verify-notify)) still exist standalone if you'd
rather demo any single stage in isolation.

## What's still needed from you to run this live

- A Concert Workflows instance (API Gateway URL, API key, instance ID).
- A GitHub token with write access to this repo (`contents:write` +
  `pull_requests:write`) for `Remediate_All` and `Reset_Demo`.
- An SMTP relay for the notification step.
- That's it - `bankdemo` no longer needs to be pre-registered in Concert;
  `Trivy_GitHub_Scan` creates it by name on first run after a
  `Reset_Demo`.
