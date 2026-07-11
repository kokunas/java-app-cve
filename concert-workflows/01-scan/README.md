# 01 - Scan: Trivy GitHub Scan

Source: unmodified copy of IBM's official sample workflow
[`trivy-github-scan`](https://github.com/IBM/Concert/blob/main/concert-workflows-samples/trivy-github-scan.zip)
(Apache-2.0, `github.com/IBM/Concert`). It is fully generic and does not need
any change to work with this demo - only the input parameters differ.

## What it does

A `system/FaaS/Python` block:
1. Installs `curl` and `trivy` inside the workflow's sandbox.
2. Runs `trivy repo --scanners vuln --format cyclonedx --output scan.json <gh_repo_url>`.
3. Uploads `scan.json` (a CycloneDX SBOM + vulnerability report) to Concert's
   ingestion API (`POST /ingestion/api/v1/upload_files`, `data_type=code_scan`).

This is the same scanner Concert uses for its own default code scanning, so
this workflow reproduces exactly what "Concert scans the repo with Trivy by
default" looks like, just made explicit and re-runnable on demand.

## How to import

Follow [Importing/exporting Concert workflows](https://www.ibm.com/docs/en/rapid-network-auto/1.1.x?topic=workflows-importing-exporting):
Concert Workflows console -> Workflows -> Import -> select `Trivy_GitHub_Scan.json`.

## How to run it for this demo

Trigger the workflow manually (or on a schedule) with:

```json
{
  "gh_repo_url": "https://github.com/kokunas/java-app-cve",
  "gh_api_token": "",
  "concert_url": "<your Concert API Gateway URL>",
  "concert_api_key": "<your Concert API key>",
  "concert_instance_id": "<your Concert instance ID>",
  "concert_allow_insecure": false
}
```

Leave `gh_api_token` empty - the repo is public. `concert_url`,
`concert_api_key` and `concert_instance_id` come from your Concert tenant
(Administration -> API keys) - see the top-level [README](../../README.md)
for where to get them.

## Expected findings

Scanning `pom.xml` should surface, among others:

| CVE | Package | Installed | Fixed in | Severity |
|-----|---------|-----------|----------|----------|
| CVE-2021-44228 (Log4Shell) | org.apache.logging.log4j:log4j-core | 2.14.1 | 2.17.1+ (2.24.3 used here) | CRITICAL |
| CVE-2021-45046 | org.apache.logging.log4j:log4j-core | 2.14.1 | 2.16.0+ | CRITICAL |

The SQL Injection in `VulnerableSearchRepository` (see
[03-remediate-sqli](../03-remediate-sqli)) is **not** a dependency CVE, so
Trivy's SCA scan will not flag it - it represents the "unknown" / non-CVE
class of finding that Concert's code-risk analysis (or a SAST tool) needs to
surface separately, deliberately included in this demo to show both
remediation paths.

Once ingested, open the vulnerability in Concert's **Vulnerability**
dimension or the **Arena view** to see it prioritized against the
`bankdemo` application topology (public endpoint reachability, business
criticality, EPSS/exploitability) before triggering remediation.
