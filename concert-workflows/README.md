# Concert Workflows - remediation lifecycle for bankdemo + fraud-cve

These are real **IBM Concert Workflows** (importable `.json`/`.zip` flow
definitions for a Concert Workflows instance), not GitHub Actions.
[discovery/Trivy_GitHub_Scan](discovery/Trivy_GitHub_Scan) and
[remediation/Maven_Package_Upgrade](remediation/Maven_Package_Upgrade) are
unmodified copies of IBM's own published samples from
[`github.com/IBM/Concert`](https://github.com/IBM/Concert) (Apache-2.0).
Everything else here is custom, authored using the exact same block schema
(`system/Github/...`, `system/FaaS/Python`, `assign`, `if`, plain JS
`Function` blocks, nested `./Helper/Subflow` calls) because no stock IBM
workflow covers a non-CVE code finding, a property-based `pom.xml` bump,
PR auto-merge, isofunctional-test-plus-email, CMDB-style application
registration, or scoped demo reset/cleanup.

Every JSON file's block logic that touches a real repo or this Concert
instance's real data was replayed/executed locally against the actual
repo and the actual Concert API before being written here - see each
folder's README for the exact verification performed.

## Layout

```
concert-workflows/
├── discovery/       - scan/find CVEs, register applications in Concert
├── remediation/     - fix CVEs (individual steps + the two orchestrators)
└── reset-demo/      - revert code + clean up Concert between demo runs
```

## discovery/ - find things

| Workflow | What it does |
|---|---|
| [Trivy_GitHub_Scan](discovery/Trivy_GitHub_Scan) | Trivy SCA scan of a GitHub repo, CycloneDX report ingested into Concert. Run once per app (`bankdemo`, then `fraud-cve`) - trigger live, in front of the audience, to show Concert connecting to GitHub and detecting CVEs from nothing. Also sets the scanned application's `criticality`/`data_impact_risk` (needed so findings get prioritized instead of defaulting to "Deprioritized"). |
| [Trivy_Image_Scan](discovery/Trivy_Image_Scan) | Trivy scan of the built container image (OS packages + bundled libraries), same `code_scan` ingestion path - workaround for `build_artifacts` registration being broken on this Concert install (see the top-level bug report). |
| [Trivy_SSH_Host_Scan](discovery/Trivy_SSH_Host_Scan) | Trivy OS-level scan of a live Linux host reached over SSH (rootfs scan run on the target itself, driven remotely) - same `code_scan` ingestion path, for CVE detection on a bare VM rather than a repo or container image. **Network reachability from Concert to the target host is unconfirmed** - see its README. |
| [Simulate_CMDB_Applications](discovery/Simulate_CMDB_Applications) | Registers 3 fictional legacy applications directly via the core API (no scan behind them), each with a description naming a famous CVE (WannaCry/Heartbleed/Shellshock) that doesn't fit this demo's real stack. Stay "Manual" by design - contrast with the automated remediation on bankdemo/fraud-cve. |

## remediation/ - fix things

Individual steps (each opens one PR):

| Workflow | Fixes |
|---|---|
| [Maven_Package_Upgrade](remediation/Maven_Package_Upgrade) | CVE-2021-44228 (Log4Shell) - bumps a literal `<dependency><version>` tag, matched by `groupId:artifactId` |
| [Spring_Property_Upgrade](remediation/Spring_Property_Upgrade) | CVE-2022-22965 (Spring4Shell) - bumps the `spring-framework.version` **property** in `pom.xml` instead (spring-core isn't a direct dependency here) |
| [SQLi_Code_Remediation](remediation/SQLi_Code_Remediation) | CWE-89 (SQL injection, not a CVE - lives in this repo's own code) - rewrites string concatenation to bind parameters |
| [Verify_And_Notify](remediation/Verify_And_Notify) | Runs the isofunctional test suite + a fresh Trivy re-scan against the now-merged `main`, pushes the re-scan to Concert, marks the matching native Concert action `success`/`failed`, and emails the result |

Orchestrators (nest the individual steps above into one trigger):

| Workflow | Nests |
|---|---|
| [Remediate_All](remediation/Remediate_All) | `bankdemo`: Maven_Package_Upgrade + Spring_Property_Upgrade + SQLi_Code_Remediation -> merges all 3 PRs via the GitHub REST API -> Verify_And_Notify |
| [Remediate_FraudCve](remediation/Remediate_FraudCve) | `fraud-cve`: a Struts_Property_Upgrade subflow (same property-bump pattern as Spring_Property_Upgrade, targeting `struts.version`) -> merges the PR -> Verify_And_Notify |

## reset-demo/ - clean up between runs

| Workflow | What it does |
|---|---|
| [Reset_Demo](reset-demo/Reset_Demo) | Reverts `pom.xml` (+ `VulnerableSearchRepository.java` for bankdemo) on both repos back to their `vulnerable-baseline` git tag, then deletes **only** the applications named in `application_names` from Concert (default: `bankdemo`, `fraud-cve`, the 3 simulated legacy apps) - safe to run against a Concert instance shared with other teams, since anything else is left untouched. |

## Live demo flow

1. **`Reset_Demo`** (reset-demo/) - run first, every time.
2. **`Trivy_GitHub_Scan`** (discovery/) - once for `bankdemo`, once for `fraud-cve` (double-check `application_name` each time - the default won't change itself).
3. *(optional)* **`Simulate_CMDB_Applications`** (discovery/) - enrich the Arena view with legacy portfolio apps.
4. *(Concert UI)* **Prioritize** - open the findings in Concert's Vulnerability dimension / Arena view.
5. **`Remediate_All`** (remediation/) for bankdemo, **`Remediate_FraudCve`** (remediation/) for fraud-cve - remediate + merge + verify + notify, one trigger each.
6. `oc rollout restart deployment/bankdemo-app -n banco-kokunas` and same for `fraud-cve-app`, so the running pods pick up the newly-published fixed image.

## Prerequisites to run this for real

1. A Concert Workflows instance (API Gateway URL, API key, instance ID - Concert Administration -> API keys).
2. Two GitHub credentials/tokens, fine-grained PATs scoped one repo each (`contents:write` + `pull_requests:write`): `github_pat_java-app-cve` for `kokunas/java-app-cve`, `github_pat_fraud-cve` for `kokunas/fraud-cve`.
3. An SMTP relay (host/port/username/password + a verified "from" address) for `Verify_And_Notify` (currently disabled pending SMTP setup - prints the notification instead of sending it). Store the password as a Concert credential, not inline.
4. Nothing pre-registered in Concert - `Trivy_GitHub_Scan`'s ingestion auto-creates the application the first time it runs after a `Reset_Demo`.

## Importing

**Always import the `.zip`, never the loose `.json`** - Concert's import
picker greys out/disables raw `.json` files, even for a single-flow bundle
with no subflows (confirmed live). Every workflow here ships both forms:
the loose `.json` for readability/diffing in this repo, and a `.zip` next
to it for import:

| Workflow | Import this |
|---|---|
| Reset_Demo | [`reset-demo/Reset_Demo/Reset_Demo.zip`](reset-demo/Reset_Demo/Reset_Demo.zip) |
| Trivy_GitHub_Scan | [`discovery/Trivy_GitHub_Scan/Trivy_GitHub_Scan.zip`](discovery/Trivy_GitHub_Scan/Trivy_GitHub_Scan.zip) |
| Trivy_Image_Scan | [`discovery/Trivy_Image_Scan/Trivy_Image_Scan.zip`](discovery/Trivy_Image_Scan/Trivy_Image_Scan.zip) |
| Trivy_SSH_Host_Scan | [`discovery/Trivy_SSH_Host_Scan/Trivy_SSH_Host_Scan.zip`](discovery/Trivy_SSH_Host_Scan/Trivy_SSH_Host_Scan.zip) |
| Simulate_CMDB_Applications | [`discovery/Simulate_CMDB_Applications/Simulate_CMDB_Applications.zip`](discovery/Simulate_CMDB_Applications/Simulate_CMDB_Applications.zip) |
| Maven_Package_Upgrade | [`remediation/Maven_Package_Upgrade/Maven_Package_Upgrade.zip`](remediation/Maven_Package_Upgrade/Maven_Package_Upgrade.zip) |
| Spring_Property_Upgrade | [`remediation/Spring_Property_Upgrade/Spring_Property_Upgrade.zip`](remediation/Spring_Property_Upgrade/Spring_Property_Upgrade.zip) |
| SQLi_Code_Remediation | [`remediation/SQLi_Code_Remediation/SQLi_Code_Remediation.zip`](remediation/SQLi_Code_Remediation/SQLi_Code_Remediation.zip) |
| Verify_And_Notify | [`remediation/Verify_And_Notify/Verify_And_Notify.zip`](remediation/Verify_And_Notify/Verify_And_Notify.zip) |
| Remediate_All (+ 3 nested subflows) | [`remediation/Remediate_All/Remediate_All.zip`](remediation/Remediate_All/Remediate_All.zip) |
| Remediate_FraudCve (+ 2 nested subflows) | [`remediation/Remediate_FraudCve/Remediate_FraudCve.zip`](remediation/Remediate_FraudCve/Remediate_FraudCve.zip) |

Console -> Workflows -> Import -> select the `.zip`. See
[Importing/exporting Concert workflows](https://www.ibm.com/docs/en/rapid-network-auto/1.1.x?topic=workflows-importing-exporting)
for the general procedure (same one IBM's own sample README points to).
