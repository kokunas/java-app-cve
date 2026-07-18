# 01c - Scan a remote Linux host: Trivy SSH Host Scan

Scans a **live remote Linux host reached over SSH** with Trivy instead of
a git repo or a container image - useful for OS-level CVE detection on a
VM that isn't containerized, rather than the Java application dependency
scanning the rest of this repo's workflows focus on.

**Rebuilt to match IBM's own published workflow patterns** (found in
[`IBM/Concert`'s `concert-workflows/Vulnerability`](https://github.com/IBM/Concert/tree/main/concert-workflows/Vulnerability) -
specifically `RHEL_SCAN_OSCAP_GRYPE`, `Test_Connection_to_RHEL`), after
confirming those real, IBM-authored workflows use exactly this block
combination for scanning/patching remote Linux hosts:

- `Common/SSH` for remote command execution (unchanged from the previous
  rebuild).
- A plain **JS `function` block** for the CycloneDX `specVersion` patch and
  metadata-building - real, visible JavaScript in Concert's editor, not a
  hidden Python sandbox. Confirmed this really is plain JS (not JSONata or
  some other DSL) directly from IBM's own examples (`Object.keys()`,
  template literals, `.map()`/`.filter()`, destructuring all appear in
  their production workflows).
- The **official ingestion block**,
  `system/IBM/Concert v2/Import Data/Upload Files to Concert`, with
  `data_type: "vm_scan"` - not the raw HTTP/curl call this repo's other
  scan workflows use (`code_scan`, meant for a repo/image, not a bare
  host). This is likely the actual reason a scanned host never linked up
  cleanly with Concert's CVE views before.

## Unresolved: where do `ssh_auth` / `concert_auth` actually get created?

**This is the one open question before importing.** Every block here
needs an `authKey` pointing at a stored credential (`ssh_auth` for the
target host, `concert_auth` for Concert's own API) - this repo's existing
GitHub-based workflows already do the same thing successfully
(`"auth": "github_pat_java-app-cve"`, a credential referenced by name),
so *some* credential store for Workflows exists. But when asked, the only
credentials UI found so far was **Administration -> Integrations ->
Connections -> Create Connection**, and that one has a short, fixed list
of connection types - no SSH, no generic/custom auth type.

Two things worth checking before importing this workflow:
1. **Look inside the Workflows app itself**, not the main Concert
   Administration menu - a gear/settings icon on the Workflows console,
   or a "Credentials"/"Secrets" section scoped to Workflows specifically.
   Given this repo's GitHub workflows already reference a named credential
   successfully, that credential had to be created *somewhere* - find that
   same place.
2. If truly nothing like that exists in this Concert install, the
   fallback is to pass an **inline credential object** as the `authKey`
   value instead of a bare string - e.g. for `ssh_auth`:
   ```json
   {"host": "35.158.156.105", "port": 22, "username": "ec2-user", "privateKey": "<PEM>"}
   ```
   directly in the trigger payload. This brings back the exact
   multi-line-paste risk the previous rebuild was trying to avoid (a
   generic trigger-payload text field mangling a pasted PEM), so only use
   it if the credential-store path is genuinely unavailable.

## What it does

1. **`InstallTrivy`** (`Common/SSH`): `authKey=$ssh_auth`, installs Trivy
   on the target if not already present (idempotent).
2. **`RunScan`** (`Common/SSH`): runs `trivy rootfs --format cyclonedx`
   against `/` on the target (a small shell-level retry loop handles
   Trivy's vulnerability-DB download being occasionally rate-limited),
   writes to a remote temp file, `cat`s it back, deletes the temp file.
3. **`BuildPayload`** (plain JS `function` block): regex-replaces
   `"specVersion": "1.6"` (or any version) with `"specVersion":"1.5"` as
   text (Concert 3.0.0 rejects CycloneDX 1.6 - see
   [Trivy_GitHub_Scan](../Trivy_GitHub_Scan)/[Trivy_Image_Scan](../Trivy_Image_Scan)
   for the original discovery), and builds the ingestion metadata JSON
   string via `JSON.stringify(...)`.
4. **`IngestScan`**
   (`system/IBM/Concert v2/Import Data/Upload Files to Concert`): uploads
   the patched scan with `data_type: "vm_scan"`.

Unlike the repo/image scans, Trivy runs **on the remote host itself**
(there's no "scan a live machine over the network" mode in Trivy) - the
SSH blocks just drive it remotely and relay the JSON output back. The
target host ends up with Trivy installed on it afterwards (left in place,
same tradeoff as any first-run agent install).

## Inputs

| Input | Required | Notes |
|---|---|---|
| `ssh_auth` | yes | Credential reference for the target host - see the open question above |
| `concert_auth` | yes | Credential reference for Concert's own API - see the open question above |
| `application_name` / `application_version` | no | Name/version to register the host as an application in Concert |
| `target_label` | no | Free-text label for the scanned host, only used in the ingestion metadata (e.g. `"ec2-user@52.59.245.141"`) |

## Trigger payload

```json
{
  "ssh_auth": "<credential name, once you find where to create it>",
  "concert_auth": "<credential name, once you find where to create it>",
  "application_name": "amazon-linux-demo",
  "application_version": "1.0.0",
  "target_label": "ec2-user@<current host IP>"
}
```

## How to import

Concert Workflows console -> Workflows -> Import -> `Trivy_SSH_Host_Scan.zip`
(the picker greys out loose `.json` files - always import the `.zip`).
**Delete any previously-imported version first.**

## Verified vs. not verified

**Verified locally**:
- `RunScan`'s shell command passes `bash -n`.
- `BuildPayload`'s JS was actually *executed* (not just syntax-checked) in
  Node against a sample CycloneDX snippet and confirmed to rewrite
  `specVersion` correctly and produce valid metadata JSON.
- Every block's action path and input names were copied directly from
  real, working IBM-published workflows (`RHEL_SCAN_OSCAP_GRYPE`), not
  guessed from a schema file.

**Not verified - genuinely unconfirmed**:
- Where `ssh_auth`/`concert_auth` credentials actually get created in
  this specific Concert install (see above).
- The exact response/behavior of `Upload Files to Concert` for
  `data_type: "vm_scan"` against a bare host with no real repo - this is
  new to this rebuild and hasn't been triggered live yet.
