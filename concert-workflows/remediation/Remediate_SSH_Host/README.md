# 05 - Remediate a remote Linux host: Remediate SSH Host

Custom Concert Workflow (same `system/FaaS/Python` block style as the rest
of this repo) that remediates **OS-package CVEs on a live Linux host**
reached over SSH - the counterpart to
[Trivy_SSH_Host_Scan](../../discovery/Trivy_SSH_Host_Scan) for the
remediation side, and the SSH-host equivalent of
[Verify_And_Notify](../Verify_And_Notify).

## Why this is a different workflow, not a reuse of Maven_Package_Upgrade/Verify_And_Notify

Every other remediation workflow in this repo fixes a **Maven dependency
version in a `pom.xml`** by opening a GitHub PR. That doesn't apply here:
a host scanned by `Trivy_SSH_Host_Scan` has no backing git repo (nothing
to open a PR against - this is also a known limitation of this Concert
install, see the [top-level README](../../../README.md#known-ibm-concert-platform-limitations)),
and the CVEs themselves are in **RPM packages** (`openssl`, `glibc`,
`kernel`, ...), not Java dependencies. The fix for an OS package CVE is a
package manager update on the host itself, so that's what this workflow
automates instead of a PR.

## What it does

One `system/FaaS/Python` block:
1. SSHes in (same key-normalization/sudo handling as
   [Trivy_SSH_Host_Scan](../../discovery/Trivy_SSH_Host_Scan)), ensures
   Trivy is present, and detects the package manager (`dnf` or `yum` -
   **RPM-based distros only**, matching this repo's Red Hat-family focus;
   Debian/Ubuntu `apt` hosts are not supported by this workflow).
2. Runs a **pre-remediation scan** (Trivy's native JSON format) and
   collects every vulnerable package that has a `FixedVersion` available
   - CVEs with no fix published upstream yet are correctly left alone,
   since there's nothing to update to.
3. Runs `{dnf|yum} update -y <package1> <package2> ...` against **exactly
   that package list** (not a blanket `update -y` of the whole system -
   keeps the change scoped to what was actually found vulnerable).
4. Runs a **post-remediation scan**, computes which of the originally
   fixable CVE IDs are gone vs. still present, and prints the diff.
5. Pushes a fresh CycloneDX scan to Concert (same `code_scan` ingestion
   path as `Trivy_SSH_Host_Scan`) so its CVE list/Arena view reflects the
   post-remediation state instead of only ever showing the original scan.
6. Prints a pass/fail notification (SMTP sending disabled for this demo
   instance, same as [Verify_And_Notify](../Verify_And_Notify) - prints
   the email content instead of sending it).
7. Searches Concert for any `auto_remediation` action already open on
   `application_name` and marks it `success`/`failed` to match the actual
   outcome, so the Action Center doesn't show it stuck on "created".
8. Exits non-zero if any originally-fixable CVE is still present
   afterwards (e.g. no reboot yet for a kernel fix, or the fixed version
   isn't in the configured repos) - fails the workflow run visibly in
   Concert rather than silently reporting success.

## A caveat worth knowing: kernel CVEs

If `kernel`/`kernel-core` is among the updated packages, the RPM database
(and therefore Trivy's next scan) will show the CVE as fixed right away,
but the **running** kernel only picks up the fix after a reboot. The
workflow prints a warning when this happens - it does not reboot the host
for you.

## Inputs

Same `ssh_*`/`concert_*` inputs as
[Trivy_SSH_Host_Scan](../../discovery/Trivy_SSH_Host_Scan), plus:

| Input | Required | Notes |
|---|---|---|
| `notify_email` | no | Recipient for the pass/fail summary - printed only, not actually sent (no SMTP relay configured for this demo instance) |

## Trigger payload for the AWS test host

```json
{
  "ssh_host": "52.59.245.141",
  "ssh_port": 22,
  "ssh_user": "ec2-user",
  "ssh_private_key": "<contents of concert-cve-test-key.pem>",
  "ssh_use_sudo": true,
  "concert_url": "https://concert-concert.apps.itz-4j78fp.pok-lb.techzone.ibm.com",
  "concert_api_key": "<your Concert API key>",
  "concert_instance_id": "0000-0000-0000-0000",
  "concert_allow_insecure": false,
  "application_name": "amazon-linux-demo",
  "application_version": "1.0.0"
}
```

## How to import

Concert Workflows console -> Workflows -> Import -> `Remediate_SSH_Host.zip`
(the picker greys out loose `.json` files - always import the `.zip`).

## Verified

The embedded Python passes `py_compile`, and the two functions that carry
the actual logic risk (`normalize_pem_key`, `extract_fixable_vulns`) were
unit-tested locally against the exact code extracted from the final
generated JSON: `normalize_pem_key` was checked against three variants of
a freshly generated test RSA key (newlines collapsed to spaces, collapsed
to a literal `\n`, and already correct) with `ssh-keygen -y` confirming
each reconstruction is a valid PEM; `extract_fixable_vulns` was checked
against a synthetic Trivy JSON report to confirm it correctly keeps only
vulnerabilities with a non-empty `FixedVersion`. **Not independently
verified**: an actual live run against a real host (do this before relying
on it for a demo, same caveat as every other workflow here that touches
live infrastructure) and the SMTP send path (disabled, matches
Verify_And_Notify).
