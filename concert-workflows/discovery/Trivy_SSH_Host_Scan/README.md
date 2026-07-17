# 01c - Scan a remote Linux host: Trivy SSH Host Scan

Custom Concert Workflow (same `system/FaaS/Python` block schema as IBM's
official `trivy-github-scan` sample and this repo's own
[Trivy_Image_Scan](../Trivy_Image_Scan)) that scans a **live remote Linux
host reached over SSH** instead of a git repo or a container image -
useful for OS-level CVE detection on a VM that isn't containerized (e.g.
a bare RHEL/Ubuntu/etc. box), rather than the Java application dependency
scanning the rest of this repo's workflows focus on.

## What it does

A `system/FaaS/Python` block:
1. Installs `curl` + `openssh-client` inside the workflow's sandbox.
2. Writes the supplied SSH private key to a temp file (`chmod 600`) - the
   key is never interpolated into a shell string, only passed via `-i` to
   the `ssh` binary.
3. Over SSH: installs Trivy on the **target host** if not already present
   (idempotent - `command -v trivy || curl ... | sh`), then runs
   `trivy rootfs --scanners vuln --format cyclonedx` against `/` **on the
   target**, writes it to a remote temp file, `cat`s it back over the same
   SSH round-trip, then deletes the remote temp file.
4. Same CycloneDX `specVersion` 1.6 -> 1.5 downgrade as
   [Trivy_GitHub_Scan](../Trivy_GitHub_Scan)/[Trivy_Image_Scan](../Trivy_Image_Scan)
   (Concert 3.0.0 rejects 1.6).
5. Uploads the result to Concert's ingestion API
   (`POST /ingestion/api/v1/upload_files`, `data_type=code_scan`) and
   optionally sets `criticality`/`data_impact_risk` on the application,
   same as the other two scan workflows.

Unlike the repo/image scans, Trivy runs **on the remote host itself**
(there's no "scan a live machine over the network" mode in Trivy) - the
workflow's sandbox only drives it over SSH and relays the JSON output
back. This means the target host ends up with Trivy installed on it
afterwards (left in place, not cleaned up - same tradeoff as any other
first-run agent install).

## Inputs

| Input | Required | Notes |
|---|---|---|
| `ssh_host` | yes | Hostname/IP of the Linux host to scan |
| `ssh_port` | no (default `22`) | |
| `ssh_user` | yes | |
| `ssh_private_key` | yes | Full PEM contents, pasted as a Concert `password`-typed variable (`notLogged: true`) - **store as a Concert credential, not inline, for anything beyond a throwaway lab VM** |
| `ssh_use_sudo` | no (default `true`) | Needed to scan `/` and install to `/usr/local/bin`. Requires **passwordless, non-interactive sudo** on the target (no `requiretty`) - the workflow never allocates a PTY or supplies a password |
| `concert_url` / `concert_api_key` / `concert_instance_id` / `concert_allow_insecure` | same as other scan workflows | |
| `application_name` / `application_version` | no | Name/version to register the host as an application in Concert |
| `criticality` / `data_impact_risk` | no | 0-5, same caveat as [Trivy_GitHub_Scan](../Trivy_GitHub_Scan) about prioritization defaulting to "Deprioritized" without these |

## Trigger payload for the lab VM given for this demo

```json
{
  "ssh_host": "158.175.71.20",
  "ssh_port": 30238,
  "ssh_user": "itzuser",
  "ssh_private_key": "<contents of vm_ssh_key-2.vm>",
  "ssh_use_sudo": true,
  "concert_url": "https://concert-concert.apps.itz-4j78fp.pok-lb.techzone.ibm.com",
  "concert_api_key": "<your Concert API key>",
  "concert_instance_id": "0000-0000-0000-0000",
  "concert_allow_insecure": false,
  "application_name": "rhel-lab-vm",
  "application_version": "1.0.0"
}
```

## How to import

Concert Workflows console -> Workflows -> Import -> `Trivy_SSH_Host_Scan.zip`
(the picker greys out loose `.json` files - always import the `.zip`).

## Not verified live - network reachability is unconfirmed

Unlike this repo's other two scan workflows (each explicitly re-run
against the real repo/image/Concert instance before being committed),
**this one could not be end-to-end verified**: from this development
sandbox, `ssh -p 30238 itzuser@158.175.71.20` and a raw `nc -vz` both
timed out on the TCP handshake, while unrelated general internet access
(and reaching the Concert console URL itself) worked fine from the same
sandbox. That rules out "no internet at all" as the cause and points at
something specific to that host/port - most likely one of:

- The VM's SSH `NodePort` (`30238`) is IP-allowlisted (e.g. only to the
  TechZone reservation's own network or your own current IP), not open
  to arbitrary internet egress.
- A security-group/firewall rule scoped narrower than "public".

**This may or may not affect Concert itself.** Concert's own FaaS sandbox
pods run inside the OpenShift cluster backing
`concert-concert.apps.itz-4j78fp.pok-lb.techzone.ibm.com`, which could sit
on the same TechZone reservation's private network as the target VM (in
which case Concert can likely reach it even though this external sandbox
can't) - or it could be on a fully separate network (in which case
Concert will hit the same timeout this sandbox did). **The only way to
know for sure is to trigger this workflow from the real Concert instance
and check the block's execution log.** If it times out there too, the fix
is opening/allowlisting the security group for `158.175.71.20:30238` to
wherever Concert's outbound egress actually originates from (ask your
TechZone reservation admin or check the cluster's egress IP).

Two more things worth checking once SSH does connect, before trusting the
scan results:
- Whether the target VM itself has outbound internet access - Trivy needs
  to download its vulnerability DB from `ghcr.io` on first run (no
  `--offline-scan` equivalent skips this for `rootfs` scans the way it
  does for license lookups on `repo`/`image` scans).
- That `itzuser`'s sudo is passwordless and doesn't require a TTY -
  confirm with `ssh ... sudo -n true` if unsure, since this workflow
  can't respond to an interactive password prompt.
