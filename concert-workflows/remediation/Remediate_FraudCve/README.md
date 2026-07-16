# Remediate_FraudCve - remediation orchestrator for fraud-cve

Companion to [Remediate_All](../Remediate_All), scoped to the
[fraud-cve](https://github.com/kokunas/fraud-cve) repo's single known CVE
(CVE-2017-5638, Struts2 Jakarta Multipart RCE - the Equifax vulnerability).

1. `./Remediate_FraudCve_Helper/Struts_Property_Upgrade` - bumps the
   `struts.version` property in `pom.xml` (not a literal `<dependency>`
   version tag, since `struts2-core`/`struts2-json-plugin` both read
   their version from that property) and opens a PR.
2. `merge_pr` (`system/FaaS/Python`) - merges the PR via the GitHub REST
   API directly (same pattern as `Remediate_All`'s `merge_prs`).
3. `./Remediate_FraudCve_Helper/Verify_And_Notify` - runs fraud-cve's
   isofunctional test suite (`mvn test`, which also starts/stops the
   `jetty-maven-plugin` daemon) + a fresh Trivy re-scan, pushes the
   post-remediation scan to Concert, marks the matching native
   `auto_remediation` action as `success`/`failed`, and emails the result.

## Setup required before running

- A GitHub connection named `github_pat_fraud-cve` in Concert's
  Integrations, scoped to the `fraud-cve` repo with Contents (read/write)
  and Pull requests (read/write) permissions - same pattern as
  `github_pat_java-app-cve` used by `Remediate_All`.
- Import **`Remediate_FraudCve.zip`** (not the loose `.json` files in this
  folder - Concert resolves `"action": "./Remediate_FraudCve_Helper/X"`
  relative to the importing bundle, so the helper subflows must travel in
  the same zip).

## Prerequisite

Run `Trivy_GitHub_Scan` (with `gh_repo_url` pointed at
`https://github.com/kokunas/fraud-cve` and `application_name: "fraud-cve"`)
first, so Concert has ingested the CVE-2017-5638 finding before this
orchestrator's `Verify_And_Notify` step tries to re-scan and mark the
action resolved.
