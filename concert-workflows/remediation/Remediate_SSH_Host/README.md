# 05 - Remediate a remote Linux host: Remediate SSH Host

Remediates OS-package CVEs on a live Linux host reached over SSH - the
counterpart to [Trivy_SSH_Host_Scan](../../discovery/Trivy_SSH_Host_Scan)
for the remediation side.

**Rebuilt to match IBM's own published remediation pattern**, after
downloading and inspecting the real, IBM-authored
`Apply_Amazon_Linux_Patch` / `Apply_Linux_Patch` / `Test_Connection_to_RHEL`
workflows from
[`IBM/Concert`'s `concert-workflows/Vulnerability/Remediation`](https://github.com/IBM/Concert/tree/main/concert-workflows/Vulnerability/Remediation).
Those apply OS patches via an **Ansible playbook** block
(`system/Ansible/Playbook`), not raw SSH command strings - so this version
does too, instead of the earlier `Common/SSH`-only rebuild.

## Why Trivy instead of official OS advisories, and why not the full fleet pipeline

IBM's official workflows source their patch list from a **separate
pipeline this repo doesn't have imported**: `Discover_Redhat_Advisories` /
`Sync_AWS_Linux_Bulletin` sync the vendor's own security bulletins
(RHSA/ALAS) into Concert, some correlation step matches them against the
host's installed packages, and *that* structured
recommendation list is what `Apply_Amazon_Linux_Patch` consumes. Without
that infrastructure, this workflow still uses **Trivy** (via
`Common/SSH`, same as the scan workflow) to work out which packages need
updating - a reasonable, self-contained substitute, matching the rest of
this repo's Trivy-based approach.

The official workflows are also built for a **fleet** (a `Config Data`
table mapping many hostnames to credentials, `foreach`-ing over all of
them). This one stays scoped to a single host, matching what the rest of
this repo's remediation workflows do (one application/target per
trigger) - no `Config Data` needed, just a single `ssh_auth` credential.

## What it does

1. **`PreScan`** (`Common/SSH`): installs Trivy if needed, scans with
   `trivy rootfs --format json`.
2. **`ExtractPrePackages`** (JS `function`): parses the scan, keeps only
   `Class == "os-pkgs"` vulnerabilities with a `FixedVersion` (excludes
   `lang-pkgs` findings like Trivy's own bundled Go dependencies - a real
   bug hit live in the previous Python version, see git history).
3. **`if $vulnerable_packages.length > 0`**:
   - **then**: builds `dnf update -y <packages>` as `$patch_command`,
     runs it via **`ApplyUpdates`** (`system/Ansible/Playbook`,
     `become: yes`), **reboots the host** if the update succeeded (`reboot`
     Ansible module, matching `Apply_Amazon_Linux_Patch`'s behavior - this
     is a real change from the previous Python version, which only warned
     about needing a reboot for kernel fixes instead of doing it), re-scans
     (`PostScanJson` + `PostScanCdx`), compares which of the originally-
     fixable CVE IDs are now gone (`ComparePrePost`), pushes the
     post-remediation CycloneDX scan to Concert
     (`system/IBM/Concert v2/Import Data/Upload Files to Concert`,
     `data_type: "vm_scan"`), finds any matching native `auto_remediation`
     action (`system/IBM/Concert v2/Action Insights/Search in Actions`)
     and marks it `success`/`failed`
     (`.../Action Insights/Update Existing Action`).
   - **else**: nothing to do, sets `$result` accordingly and skips
     Ansible/re-scan/upload entirely.

## Same unresolved credential-store question as Trivy_SSH_Host_Scan

See [Trivy_SSH_Host_Scan's README](../../discovery/Trivy_SSH_Host_Scan#unresolved-where-do-ssh_auth--concert_auth-actually-get-created) -
`ssh_auth` (also used as the Ansible playbook's `authKey` - IBM's own
`Test_Connection_to_RHEL` example confirms the same credential type works
for both `Common/SSH` and `Ansible/Playbook` blocks) and `concert_auth`
both need to resolve to *something* Concert lets you create, and that
place hasn't been located in this install yet.

## `hosts: canary` is IBM's own fixed convention, not a typo

Both `Test_Connection_to_RHEL` and `Apply_Amazon_Linux_Patch` target
`hosts: canary` in their playbooks with no visible inventory file - the
actual host/credentials come entirely from the `authKey` passed to the
`Ansible/Playbook` block, which presumably generates a temporary inventory
naming that group `canary` behind the scenes. Copied verbatim rather than
guessing a different group name.

## A caveat carried over from the previous version: kernel packages

If `kernel`/`kernel-core` is among the updated packages, this version
actually **does** reboot (unlike the previous one, which only warned) -
matching the official pattern. Still worth knowing that the RPM database
shows the fix immediately, while the running kernel only truly picks it
up once that reboot completes - which is exactly why the official
playbook includes it.

## Inputs

| Input | Required | Notes |
|---|---|---|
| `ssh_auth` | yes | Credential for the target host (`Common/SSH` block *and* the Ansible playbook) |
| `concert_auth` | yes | Credential for Concert's own API |
| `application_name` / `application_version` | no | Must match the application this host was registered as by `Trivy_SSH_Host_Scan` |

## Trigger payload

```json
{
  "ssh_auth": "<credential name>",
  "concert_auth": "<credential name>",
  "application_name": "amazon-linux-demo",
  "application_version": "1.0.0"
}
```

## How to import

Concert Workflows console -> Workflows -> Import -> `Remediate_SSH_Host.zip`.
**Delete any previously-imported version first** - a stale/duplicate
import was the suspected cause of a previous session appearing to keep
running old code after a re-import.

## Verified vs. not verified

**Verified locally**:
- Every JS `function` block (`ExtractPrePackages`, `ComparePrePost`,
  `BuildActionUpdates`, `BuildPatchCommand`) was *executed* (not just
  syntax-checked) in Node against realistic mock Trivy JSON / Concert
  action-search responses, confirming: `lang-pkgs` findings are correctly
  excluded, the pre/post CVE-ID diff correctly identifies a resolved CVE,
  and the action-update payload shape matches.
- The embedded Ansible playbook passes both `bash`-adjacent YAML parsing
  (`PyYAML`) and a real `ansible-playbook --syntax-check`, with
  `${$patch_command}` substituted for a realistic value first.
- Every native block's action path and input names were copied from real,
  working IBM-published workflows, not guessed.

**Not verified - genuinely unconfirmed**:
- Live end-to-end execution against a real Concert instance (same caveat
  as the scan workflow - this has real infrastructure dependencies
  I cannot exercise myself).
- Whether `Search in Actions`' filter actually supports an
  `application_name` condition the way this workflow assumes - IBM's own
  example filtered by `status`/`risk_type`/`action_category` only, not
  `application_name`; this workflow's filter shape is carried over from
  this repo's own pre-existing (also not confirmed live end-to-end)
  `Verify_And_Notify` convention.
