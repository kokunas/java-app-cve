# Banco Kokunas - bankdemo + fraud-cve

IBM Concert remediation-lifecycle demo: two small real applications for a
fictional bank, deliberately shipping known CVEs, one unknown/non-cataloged
vulnerability, and isofunctional test suites used to prove that automated
remediation doesn't break either app.

| | [`bankdemo`](.) (this repo) | [`fraud-cve`](https://github.com/kokunas/fraud-cve) |
|---|---|---|
| Role | Mortgage-loan simulation/application, funds transfers, customer search | Fraud/credit-risk scoring, called by `bankdemo` before approving a transfer or mortgage |
| Stack | Spring Boot 2.7.x (Java 17), Thymeleaf, Spring Data JPA, PostgreSQL 16 | Apache Struts 2.3.30 (Java 8 target), JSP |
| Exposure | Public (OpenShift Route) | Private (`ClusterIP` only, no Route) |

It exists to demonstrate the full Concert lifecycle across a small
multi-app portfolio: **scan -> prioritize -> auto-remediate -> verify ->
notify**, using real [Concert Workflows](concert-workflows/) (not GitHub
Actions) for every step of the automation.

## The vulnerabilities

| App | Class | Where | Detail |
|---|---|---|---|
| bankdemo | **Known CVE** | [`pom.xml`](pom.xml) | `log4j-core`/`log4j-api` pinned to `2.14.1` (CVE-2021-44228 "Log4Shell", CVE-2021-45046). Reachable sink: [`AuditLogger`](src/main/java/com/kokunas/bankdemo/config/AuditLogger.java) logs the `X-Channel` request header via `org.apache.logging.log4j.LogManager` on every `/search` call. |
| bankdemo | **Known CVE** | [`pom.xml`](pom.xml) | `spring-framework.version` pinned to `5.3.17` (CVE-2022-22965 "Spring4Shell"). This is why `bankdemo` runs on the `spring-boot-starter-parent:2.7.18` line rather than Boot 3.x - Boot 3 requires Spring Framework 6.x (`jakarta.*` namespace), which isn't affected by this CVE, so the vulnerable version genuinely can't coexist with Boot 3. |
| bankdemo | **Unknown / non-CVE** | [`VulnerableSearchRepository`](src/main/java/com/kokunas/bankdemo/repository/VulnerableSearchRepository.java) | CWE-89 SQL Injection: `searchCustomers(term)` concatenates user input directly into the SQL string. Confirmed locally: `GET /search?query=zzznomatch` returns nothing, `GET /search?query=' OR '1'='1` dumps every customer row. A dependency scanner will **not** catch this - it's not a library version, it's how the app talks to the database. |
| fraud-cve | **Known CVE** | [`pom.xml`](https://github.com/kokunas/fraud-cve/blob/main/pom.xml) | `struts.version` pinned to `2.3.30` (CVE-2017-5638, the Apache Struts2 Jakarta Multipart parser RCE used in the 2017 Equifax breach). The vulnerability lives in the framework's default interceptor stack, which processes every request's `Content-Type` header - so any endpoint is exploitable, not just file-upload ones. |

## The apps

### bankdemo

- **Dashboard** (`/`) - portfolio overview: customers, mortgage volume, transfers.
- **Mortgage simulator** (`/loans/new`) - French-amortization monthly
  payment calculator + 80% loan-to-value eligibility check + application
  submission (`/loans`, `/loans/{id}`). A fraud-check call to `fraud-cve`
  can override an otherwise-eligible application to `REJECTED`.
- **Transfers** (`/transfers`, `/transfers/new`) - IBAN-to-IBAN transfer
  creation and ledger. Same fraud-check gate as mortgages.
- **Customer search** (`/search`) - branch-office quick lookup by name/NIF.
  **This is the SQLi-vulnerable endpoint.**

[`FraudCheckClient`](src/main/java/com/kokunas/bankdemo/client/FraudCheckClient.java)
calls `fraud-cve`'s JSON API over HTTP with an 800ms timeout and fails
open (approves normally) if the service is unreachable - a secondary
risk-scoring dependency being down should never block core banking
operations, and this also keeps bankdemo's own test suite passing
unchanged whether or not `fraud-cve` happens to be running.

### fraud-cve

Single endpoint (`/fraudCheckForm` for the human-facing form,
`/api/fraudCheck` for the JSON API bankdemo actually calls) computing a
fraud score from the customer NIF format, transaction type, and amount -
`LOW`/`MEDIUM`/`HIGH` risk, with `HIGH` meaning rejected. See
[its own README](https://github.com/kokunas/fraud-cve#readme) for the
full detail.

## Isofunctional tests

Prove that remediation changes *how* the code does its job, not *what*
it does - the same suite passes both before and after each fix.

- **bankdemo** (11 tests): [`LoanServiceTest`](src/test/java/com/kokunas/bankdemo/LoanServiceTest.java)
  (3, pure business-logic unit tests) +
  [`IsofunctionalWebTests`](src/test/java/com/kokunas/bankdemo/IsofunctionalWebTests.java)
  (8, full MockMvc integration tests: dashboard, mortgage simulation/
  application, transfers, customer search). Run: `mvn test`.
- **fraud-cve** (8 tests): [`IsofunctionalFraudCheckTests`](https://github.com/kokunas/fraud-cve/blob/main/src/test/java/com/kokunas/frauddetection/IsofunctionalFraudCheckTests.java),
  run against a real running instance (`jetty-maven-plugin` started
  automatically before the test phase, since the CVE lives in the
  framework layer, not in the action code - mocking it out would prove
  nothing). Run: `mvn test`.

`Verify_And_Notify` (part of both `Remediate_All` and
`Remediate_FraudCve`) runs the relevant suite against the merged fix and
only reports success if every test still passes.

## Running locally

**bankdemo:**
```bash
docker compose up --build
```
Then open <http://localhost:8080>. Postgres runs on an `internal: true`
compose network (not published beyond `5432` on the host) - see [k8s/](k8s)
for the production-shaped topology where the database has no external
exposure at all.

Try the SQLi live:
```bash
curl -s "http://localhost:8080/search?query=zzznomatch"          # no results
curl -s -G "http://localhost:8080/search" --data-urlencode "query=' OR '1'='1"  # dumps all customers
```

**fraud-cve:** see [its own README](https://github.com/kokunas/fraud-cve#readme)
(`mvn jetty:run`, then <http://localhost:8081/fraudCheckForm>).

## Container images & deployment topology

- **bankdemo**: source at this repo, image at `ghcr.io/kokunas/java-app-cve`
  (built by [`Dockerfile`](Dockerfile) + `.github/workflows/build-and-push.yml`
  on every push to `main`). Deployed in the `banco-kokunas` OpenShift
  namespace as `bankdemo-app` (public Route, edge TLS) +
  `bankdemo-db` (Postgres, `ClusterIP` only) - see [k8s/](k8s).
- **fraud-cve**: source at [github.com/kokunas/fraud-cve](https://github.com/kokunas/fraud-cve),
  image at `ghcr.io/kokunas/fraud-cve`, same build pattern. Deployed in
  the same namespace as `fraud-cve-app` - `ClusterIP` only, deliberately
  no Route, since this is an internal-only service bankdemo calls
  server-side.

## The remediation lifecycle

All automation lives in [concert-workflows/](concert-workflows/) as real,
importable Concert Workflow JSON/zip definitions, organized by stage:

```
concert-workflows/
├── discovery/     - find things: scan repos/images, register CMDB-style apps
├── remediation/   - fix things: individual steps + the two orchestrators
└── reset-demo/    - clean up between demo runs
```

See that folder's README for the full workflow table, trigger payloads,
and what was verified against the real repos and the real Concert API.
**Live demo shape - one reset, then per app: scan, then remediate:**

0. **Reset** (run before every demo):
   [`Reset_Demo`](concert-workflows/reset-demo/Reset_Demo) - reverts both
   repos' code to their `vulnerable-baseline` git tags, and deletes only
   this demo's applications from Concert (safe on an instance shared with
   other teams - nothing else gets touched).
1. **Scan** (once per app):
   [`Trivy_GitHub_Scan`](concert-workflows/discovery/Trivy_GitHub_Scan) -
   trigger live, in front of the audience, once with `application_name:
   bankdemo` and once with `application_name: fraud-cve`: Concert connects
   to GitHub, scans with Trivy, and the application appears with real
   CVEs, already prioritized (this workflow also sets business
   criticality on the application, which Concert factors into
   prioritization alongside raw CVE severity).
2. *(optional)* [`Simulate_CMDB_Applications`](concert-workflows/discovery/Simulate_CMDB_Applications) -
   registers 3 fictional legacy applications (naming WannaCry/Heartbleed/
   Shellshock in their descriptions) to enrich the portfolio view - these
   stay unremediated by design, contrasting with the automated fixes below.
3. **Prioritize**: done in the Concert console (Vulnerability dimension /
   Arena view) - no workflow needed.
4. **Remediate + merge + verify + notify, nested** (once per app):
   [`Remediate_All`](concert-workflows/remediation/Remediate_All) for
   bankdemo (log4j + Spring4Shell + SQLi, 3 PRs auto-merged in one trigger)
   and [`Remediate_FraudCve`](concert-workflows/remediation/Remediate_FraudCve)
   for fraud-cve (Struts2, 1 PR). Neither Concert's block catalog nor
   IBM's published samples have a native "merge PR" block, so both call
   the GitHub REST API directly to merge. Each then runs the app's
   isofunctional test suite and a fresh Trivy scan against `main`, and
   emails the outcome.
5. Roll the running pods so they pick up the newly-published fixed image:
   ```bash
   oc rollout restart deployment/bankdemo-app -n banco-kokunas
   oc rollout restart deployment/fraud-cve-app -n banco-kokunas
   ```

The individual remediation steps
([Maven_Package_Upgrade](concert-workflows/remediation/Maven_Package_Upgrade),
[Spring_Property_Upgrade](concert-workflows/remediation/Spring_Property_Upgrade),
[SQLi_Code_Remediation](concert-workflows/remediation/SQLi_Code_Remediation),
[Verify_And_Notify](concert-workflows/remediation/Verify_And_Notify)) still
exist standalone if you'd rather demo any single stage in isolation.

## Known IBM Concert platform limitations hit while building this

A few things behave unexpectedly on this Concert 3.0.0 install regardless
of how this demo's own workflows are written - e.g. `build_artifacts`
registration always fails, CVE/certificate data never links for an
application with no real reachable backing repository, and a couple of
API schema/documentation mismatches. None of these block the demo, but
they shape some of the workarounds above (like registering the CMDB
apps via `POST /applications` with a descriptive narrative, rather than
trying to attach structured CVE findings to them).

## What's still needed from you to run this live

- A Concert Workflows instance (API Gateway URL, API key, instance ID).
- Two fine-grained GitHub tokens, one per repo (`contents:write` +
  `pull_requests:write`): one for `kokunas/java-app-cve`, one for
  `kokunas/fraud-cve`.
- An SMTP relay for the notification step (currently disabled pending
  setup - `Verify_And_Notify` prints the notification instead of sending it).
- That's it - neither app needs to be pre-registered in Concert;
  `Trivy_GitHub_Scan` creates each by name on first run after a `Reset_Demo`.
