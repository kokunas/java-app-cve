# Concert Workflows - bankdemo remediation lifecycle

These are real **IBM Concert Workflows** (importable `.json`/`.zip` flow
definitions for a Concert Workflows instance), not GitHub Actions.
[01-scan](01-scan) and [02-remediate-log4j](02-remediate-log4j) are
unmodified copies of IBM's own published samples from
[`github.com/IBM/Concert`](https://github.com/IBM/Concert) (Apache-2.0).
[03-remediate-sqli](03-remediate-sqli), [04-verify-notify](04-verify-notify),
[Remediate_All](Remediate_All) and [Reset_Demo](Reset_Demo) are custom
workflows authored using the exact same block schema (`system/Github/...`,
`system/FaaS/Python`, `assign`, `if`, plain JS `Function` blocks, nested
`./Helper/Subflow` calls) because no stock IBM workflow covers a non-CVE
code finding, PR auto-merge, isofunctional-test-plus-email, or demo
reset/cleanup.

Every JSON file's block logic that touches this repo's actual source or
this Concert instance's actual data was replayed/executed locally against
the real repo and the real Concert API before being written here - see
each folder's README for the exact verification performed.

## Live demo: two triggers, plus a reset

For presenting, you only ever trigger **two** workflows per pass through
the lifecycle (prioritization happens automatically in the console in
between), and **one** workflow before you start:

| # | Workflow | Stage | What it proves |
|---|----------|-------|-----------------|
| - | [`Reset_Demo`](Reset_Demo) | **Reset (run first)** | Reverts the code to the vulnerable baseline and wipes every application/repo/artifact/certificate out of Concert, so the instance is empty and the CVEs are real again |
| [01](01-scan) | `Trivy_GitHub_Scan` | **Scan** | Trivy SCA scan of `kokunas/java-app-cve`, CycloneDX report ingested into Concert - trigger this live, in front of the audience, to show Concert connecting to GitHub and detecting the CVEs from nothing |
| - | *(Concert UI)* | **Prioritize** | Open the finding in Concert's Vulnerability dimension / **Arena view**, see it scored against the `bankdemo` app topology (public endpoint, business criticality) - explain why CVE-2021-44228 outranks its siblings |
| [Remediate_All](Remediate_All) | `Remediate_All` | **Remediate + merge + verify + notify** | Nested: opens the log4j PR, opens the SQLi PR, **auto-merges both** via the GitHub REST API, then runs the isofunctional test suite + a fresh Trivy scan against `main` and emails the result - all from one trigger |

The four individual workflows ([01](01-scan), [02](02-remediate-log4j),
[03](03-remediate-sqli), [04](04-verify-notify)) still exist standalone and
are documented on their own, in case you want to demo any single stage in
isolation instead of the nested `Remediate_All`.

## Prerequisites to run this for real

1. A Concert Workflows instance (API Gateway URL, API key, instance ID -
   Concert Administration -> API keys).
2. A GitHub credential/token with write access to `kokunas/java-app-cve`
   (fine-grained PAT scoped to this repo only: `contents:write` +
   `pull_requests:write`) for `Remediate_All` and `Reset_Demo`.
3. An SMTP relay (host/port/username/password + a verified "from" address)
   for `Remediate_All`/[04-verify-notify](04-verify-notify). Store the
   password as a Concert credential, not inline.
4. Nothing pre-registered in Concert - `Trivy_GitHub_Scan`'s ingestion
   auto-creates the `bankdemo` application (see
   [01-scan](01-scan)'s metadata contract) the first time it runs after a
   `Reset_Demo`.

## Importing

**Always import the `.zip`, never the loose `.json`** - Concert's import
picker greys out/disables raw `.json` files, even for a single-flow bundle
with no subflows (confirmed live: `Reset_Demo.json` and
`Trivy_GitHub_Scan.json` were both unselectable until zipped). This
matches how IBM distributes its own samples - `trivy-github-scan.zip`
wraps a single JSON file the same way. Every workflow here ships both
forms: the loose `.json` for readability/diffing in this repo, and a
`.zip` next to it (or containing it, for the folders) for import:

| Workflow | Import this |
|---|---|
| Reset_Demo | [`Reset_Demo.zip`](Reset_Demo.zip) |
| Trivy_GitHub_Scan | [`01-scan/Trivy_GitHub_Scan.zip`](01-scan/Trivy_GitHub_Scan.zip) |
| Maven_Package_Upgrade | [`02-remediate-log4j/Maven_Package_Upgrade.zip`](02-remediate-log4j/Maven_Package_Upgrade.zip) |
| SQLi_Code_Remediation | [`03-remediate-sqli/SQLi_Code_Remediation.zip`](03-remediate-sqli/SQLi_Code_Remediation.zip) |
| Verify_And_Notify | [`04-verify-notify/Verify_And_Notify.zip`](04-verify-notify/Verify_And_Notify.zip) |
| Remediate_All (+ 3 nested subflows) | [`Remediate_All.zip`](Remediate_All.zip) |

Console -> Workflows -> Import -> select the `.zip`. See
[Importing/exporting Concert workflows](https://www.ibm.com/docs/en/rapid-network-auto/1.1.x?topic=workflows-importing-exporting)
for the general procedure (same one IBM's own sample README points to).
