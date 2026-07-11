# Concert Workflows - bankdemo remediation lifecycle

These are real **IBM Concert Workflows** (importable `.json` flow
definitions for a Concert Workflows instance), not GitHub Actions. Two of
them ([01-scan](01-scan) and [02-remediate-log4j](02-remediate-log4j)) are
unmodified copies of IBM's own published samples from
[`github.com/IBM/Concert`](https://github.com/IBM/Concert) (Apache-2.0).
The other two ([03-remediate-sqli](03-remediate-sqli) and
[04-verify-notify](04-verify-notify)) are custom workflows authored using
the exact same block schema (`system/Github/...`, `system/FaaS/Python`,
`assign`, `if`, plain JS `Function` blocks) because no stock IBM workflow
covers a non-CVE code finding or an isofunctional-test-plus-email step.

Every JSON file's block logic that touches this repo's actual source was
replayed locally against the real files (Node.js for the `Function`
blocks, `mvn test` for the result) before being written here - see each
folder's README for the exact verification performed.

## The lifecycle, in order

| # | Workflow | Stage | What it proves |
|---|----------|-------|-----------------|
| [01](01-scan) | `Trivy_GitHub_Scan` | **Scan** | Trivy SCA scan of `kokunas/java-app-cve`, CycloneDX report ingested into Concert |
| - | *(Concert UI)* | **Prioritize** | Open the finding in Concert's Vulnerability dimension / **Arena view**, see it scored against the `bankdemo` app topology (public endpoint, business criticality) |
| [02](02-remediate-log4j) | `Maven_Package_Upgrade` | **Remediate (known CVE)** | Auto-PR bumping `log4j-core`/`log4j-api` 2.14.1 -> 2.24.3, fixing CVE-2021-44228/CVE-2021-45046 |
| [03](03-remediate-sqli) | `SQLi_Code_Remediation` | **Remediate (unknown/non-CVE)** | Auto-PR parameterizing the SQL Injection in `VulnerableSearchRepository` - a finding Trivy's SCA scan can't see |
| [04](04-verify-notify) | `Verify_And_Notify` | **Verify & notify** | Runs the isofunctional test suite + re-scans with Trivy, emails the pass/fail outcome |

## Prerequisites to run this for real

1. A Concert Workflows instance (API Gateway URL, API key, instance ID -
   Concert Administration -> API keys).
2. A GitHub credential/integration in Concert with write access to
   `kokunas/java-app-cve` (fine-grained PAT, `contents:write` +
   `pull_requests:write` on this repo only) for workflows 02 and 03.
3. An SMTP relay (host/port/username/password + a verified "from" address)
   for workflow 04. Store the password as a Concert credential, not inline.
4. `bankdemo` onboarded as an application in Concert (source repo +
   container image in a registry + deployment topology) so the scan
   results and Arena view have something to attach to - see the top-level
   [README](../README.md) for the exact application resources to
   register.

## Importing

Console -> Workflows -> Import -> select the `.json` file. See
[Importing/exporting Concert workflows](https://www.ibm.com/docs/en/rapid-network-auto/1.1.x?topic=workflows-importing-exporting)
for the general procedure (same one IBM's own sample README points to).
