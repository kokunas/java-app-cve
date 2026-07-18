# 01c - Scan a remote Linux host: Trivy SSH Host Scan

Scans a **live remote Linux host reached over SSH** with Trivy instead of
a git repo or a container image - useful for OS-level CVE detection on a
VM that isn't containerized, rather than the Java application dependency
scanning the rest of this repo's workflows focus on.

**Rebuilt with native Concert blocks** (`Common/SSH`, `Common/String`,
`Common/JSON`, `Common/HTTP`, from IBM's
[`Common` automation library](https://automation-library.ibm.com/files/files/Common_1.3.360.ssi.zip))
instead of the original custom `system/FaaS/Python` script - every step is
now a stock block visible/editable in Concert's own low-code editor, with
no Python hidden inside a single opaque block. The only thing that lives
outside the visual editor now is the SSH private key itself, and that's
by design (see below).

## Prerequisite: create a Common/SSH credential in Concert

Before importing this workflow, create a stored credential:
**Concert Administration -> Credentials -> Add -> type `Common/SSH`**,
with:

| Field | Value |
|---|---|
| Host | the target's IP/hostname |
| Port | the target's SSH port |
| Username | the target's SSH user |
| Private Key | full PEM contents (pasted into a real multi-line textarea in this form - this is what actually fixes the `error in libcrypto` bug the FaaS/Python version hit: that bug was Concert's generic single-line trigger-payload text field mangling a multi-line key on paste, and the Credential Manager's own SSH form doesn't have that problem) |

Give the credential a name (e.g. `concert-cve-ssh-key`) and use that name
as this workflow's `ssh_auth` input - same pattern this repo's own GitHub
remediation workflows already use for `gh_api_token`/`auth`
(`"auth": "github_pat_java-app-cve"` referencing a stored credential by
name, not a raw secret inline).

## What it does

1. **`InstallTrivy`** (`Common/SSH`): `authKey=$ssh_auth`, installs Trivy
   on the target if not already present (idempotent).
2. **`RunScan`** (`Common/SSH`): runs `trivy rootfs --format cyclonedx`
   against `/` on the target (a small `for`/`sleep` retry loop is baked
   into the shell command itself, since Trivy's vulnerability-DB download
   can be rate-limited - this keeps the retry logic as plain shell, not
   Concert-level looping), writes to a remote temp file, `cat`s it back,
   deletes the temp file.
3. **`PatchSpecVersion`** (`Common/String/String Replace`): regex-replaces
   `"specVersion": "1.6"` (or any version) with `"specVersion":"1.5"` as
   plain text - Concert 3.0.0 rejects CycloneDX 1.6 (see
   [Trivy_GitHub_Scan](../Trivy_GitHub_Scan)/[Trivy_Image_Scan](../Trivy_Image_Scan)
   for the original discovery of this). Doing this as a text substitution
   instead of parse-patch-restringify avoids needing any JSON object-merge
   expression syntax.
4. **`BuildMetadataObject`** (`assign`) + **`StringifyMetadata`**
   (`Common/JSON/JSON Stringify`): builds the ingestion metadata
   (`scanner_name`, `application_name`, `application_version`, `target`).
5. **`BuildAuthHeader`** (`Common/String/String Append`): concatenates
   `"C_API_KEY "` with `$concert_api_key` for the `Authorization` header.
6. **`UploadToConcert`** (`Common/HTTP/HTTP Request`): `POST`s to
   `/ingestion/api/v1/upload_files` with the patched scan as a multipart
   file attachment plus `data_type`/`metadata` fields.

Unlike the repo/image scans, Trivy runs **on the remote host itself**
(there's no "scan a live machine over the network" mode in Trivy) - the
SSH blocks just drive it remotely and relay the JSON output back. The
target host ends up with Trivy installed on it afterwards (left in place,
same tradeoff as any first-run agent install).

## Intentionally left out of this rebuild (vs. the old Python version)

- **Setting `criticality`/`data_impact_risk`** on the application after
  ingestion - the original Python version polled `GET /applications`
  until the newly-ingested app appeared (ingestion is async), which needs
  a retry loop. There's no confirmed native "retry until" block, and
  guessing at conditional/loop block syntax with zero working examples in
  this repo felt like too much unverified surface for one rebuild. Set
  these manually in Concert's UI after a scan, or ask if you want this
  added back (possibly as a small standalone follow-up, native or not).
- The `ssh_use_sudo` toggle - both hosts this was tested against (Amazon
  Linux, and the original RHEL lab VM) needed root, so `sudo` is now
  hardcoded into the two SSH commands rather than kept configurable.

## Inputs

| Input | Required | Notes |
|---|---|---|
| `ssh_auth` | yes | Name of the `Common/SSH` credential stored in Concert (see prerequisite above), e.g. `"concert-cve-ssh-key"` |
| `concert_url` / `concert_api_key` / `concert_instance_id` | yes | same as other scan workflows |
| `application_name` / `application_version` | no | Name/version to register the host as an application in Concert |
| `target_label` | no | Free-text label for the scanned host, only used in the ingestion metadata (e.g. `"ec2-user@52.59.245.141"`) - purely cosmetic, no longer used to build an SSH connection since that now lives in the credential |

## Trigger payload

```json
{
  "ssh_auth": "concert-cve-ssh-key",
  "concert_url": "https://concert-concert.apps.itz-4j78fp.pok-lb.techzone.ibm.com",
  "concert_api_key": "<your Concert API key>",
  "concert_instance_id": "0000-0000-0000-0000",
  "application_name": "amazon-linux-demo",
  "application_version": "1.0.0",
  "target_label": "ec2-user@<current host IP>"
}
```

## How to import

Concert Workflows console -> Workflows -> Import -> `Trivy_SSH_Host_Scan.zip`
(the picker greys out loose `.json` files - always import the `.zip`).
**Delete any previously-imported version of this workflow first** - a
stale/duplicate import was the suspected cause of a previous session
appearing to keep running old code after a re-import.

## Verified vs. not verified

**Verified locally** (without a live Concert instance):
- The `RunScan` shell command passes `bash -n` syntax validation.
- The `PatchSpecVersion` regex (`"specVersion"\s*:\s*"[^"]*"` ->
  `"specVersion":"1.5"`) correctly rewrites a sample CycloneDX snippet.
- The generated workflow JSON is well-formed and every block's inputs
  match that block's actual variable names, cross-checked directly
  against the extracted block definitions from IBM's `Common` library
  zip (`SSH.json`, `String Replace.json`, `JSON Stringify.json`,
  `String Append.json`, `HTTP Request.json`).

**Not verified - genuinely unconfirmed, first thing to check if this
fails**:
- Whether `HTTP Request`'s `attachments` array-literal syntax
  (`[{...}]`) is valid in Concert's expression language - every existing
  example in this repo builds object literals (`{...}`), none build an
  array literal this way.
- Whether `body` (plain fields: `data_type`, `metadata`) and
  `attachments` (the file) actually combine into one coherent multipart
  request the way `curl --form data_type=... --form metadata=... --form
  filename=@file` did in the old, proven-working Python version - or
  whether the ingestion endpoint only sees one or the other.
- Whether `ssh_auth` as a bare string genuinely resolves to the stored
  credential the same way it demonstrably does for this repo's GitHub
  blocks (same pattern, different `authType`, not independently tested
  for `Common/SSH` specifically).

If the upload step fails, check the `UploadToConcert` block's own
response/error in Concert's execution log first - that will show
directly whether the server received `metadata/data_type` at all, which
narrows down which of the above it is.
