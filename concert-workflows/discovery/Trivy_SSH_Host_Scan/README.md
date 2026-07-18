# 01c - Scan a remote Linux host: Trivy SSH Host Scan

Scans a **live remote Linux host reached over SSH** with Trivy instead of
a git repo or a container image - useful for OS-level CVE detection on a
VM that isn't containerized, rather than the Java application dependency
scanning the rest of this repo's workflows focus on.

**Rebuilt to match IBM's own published workflow patterns** (found in
[`IBM/Concert`'s `concert-workflows/Vulnerability`](https://github.com/IBM/Concert/tree/main/concert-workflows/Vulnerability) -
specifically `RHEL_SCAN_OSCAP_GRYPE`, `Test_Connection_to_RHEL`,
`Fetch_Scan_files_from_AWS_Inspector`):

- `Common/SSH` for remote command execution.
- A plain **JS `function` block** for building the upload payload - real,
  visible JavaScript in Concert's editor, not a hidden Python sandbox
  (confirmed this really is plain JS, not JSONata, directly from IBM's own
  examples: `Object.keys()`, template literals, `.map()`/`.filter()`).
- The **official ingestion block**,
  `system/IBM/Concert v2/Import Data/Upload Files to Concert`.

## Credentials: created under the Workflows app's own settings, not Concert's main Administration menu

Both `ssh_auth` and `concert_auth` are stored credentials, created from
**inside the Workflows app itself** (not Administration -> Integrations ->
Connections, which is a different, unrelated list with no SSH/generic
option). Concretely, for this demo:

- `ssh_auth`: credential type **SSH** (host/port/username/private key of
  the target).
- `concert_auth`: credential type **IBM Concert API Key** - fields:
  `protocol` (`https`), `host` (the Concert console hostname, no
  `https://` prefix), `api key`, `api key type` (`C_API_KEY`),
  `instance id` (`0000-0000-0000-0000`).

Reference each by the name you gave it when creating them.

## A real bug found live: `vm_scan` needs Concert's own CSV shape, not raw Trivy JSON/CycloneDX

The first working version of this rebuild uploaded the patched CycloneDX
scan directly with `data_type: "vm_scan"`. Concert accepted it
(`202`, real CVE Findings showed up under the application), **but Arena
view couldn't distinguish these from application-code CVEs** - no way to
tell OS vs. app findings apart without opening each one individually.

Digging into IBM's own sample data
(`use-case-data-files/*/vulnerabilities/vm_scan/` in the `IBM/Concert`
repo) showed why: `vm_scan` ingestion is scanner-specific, keyed by the
`scanner_name` in the upload metadata - recognized values are
`aws_inspector`, `nessus`, `qualys`, and a **native `concert` CSV format**
(`vm_scan_concert_sample.csv`). `"trivy"` isn't one of them, so the
ingestion fell back to something that stores flat findings without
properly attributing them to a host resource.

Fixed by having `BuildVmScanCsv` convert Trivy's JSON scan into that
native CSV shape instead:
```
CVE,Host IPAddress,Package,Package Version,Package Path,severity,Score,hasFix,Fixed Version,Description,Host Name
```
with `metadata: {"scanner_name": "concert"}` and `fileType: "csv"`.

## A third real bug found live: `Common/SSH` chokes on `for` loops / `;`-separated commands

After fixing the timeout, the run kept failing with
`bash: line 1: for i in 1 2 3 4 5; do sudo trivy ...: No such file or
directory` - bash's "No such file or directory" (as opposed to "command
not found") is specifically what it prints when it tries to look up a
command name containing a `/` as a literal path, meaning the whole
string was being treated as one filename to execute rather than as shell
code to parse. First suspected this was real newlines getting flattened
to literal `\n` (single-lining the command with `;` looked like it should
fix it) - **it didn't**: the exact same error recurred with a genuinely
single-line command.

Isolated it by running the identical command string directly over SSH
from outside Concert entirely (bypassing the SSH block, same target
host, same key): it worked perfectly, first try, full valid output. That
conclusively proves the command text itself was never the problem - the
bug is in how Concert's `Common/SSH` block specifically invokes
multi-statement commands. `InstallTrivy` (`A || (B | C)` - no `;`, no
`for`) never failed; `RunScan` (`for ... ; do ... ; done`) always did.

**Fixed the `Common/SSH` command itself by chaining with `&&` only**
(`trivy ... && cat ... && sudo rm -f ...`) instead of `for`/`;` - matches
the one operator style (`||`/`|`/subshells `(...)`, now also `&&`)
confirmed to work through this block.

**The retry logic moved out of the shell entirely, into native Concert
control blocks** - the right fix, not just a workaround: a shell-level
`for` loop was never the correct way to retry inside a single `Common/SSH`
call anyway, since `Common/SSH` is meant for one command execution and
Concert's own engine already has loop/branch primitives for exactly this.
Found the proven pattern in IBM's own `Fetch_Scan_files_from_AWS_Inspector`
workflow, which polls a report-generation status with this exact
combination: a `while` block (`condition`, nested `blocks`), an `if`
checking the result, and `system/Common/Sleep` (`seconds` input) for the
delay. `RunScan` is now wrapped:
```
while (!$scan_ok && $scan_attempts < 5):
    RunScan (Common/SSH, && chained)
    scan_ok = RunScan.exitcode === 0
    scan_attempts += 1
    if (!scan_ok && scan_attempts < 5): Sleep 5s
```
Same pattern applied to `Remediate_SSH_Host`'s `PreScan`/`PostScanJson`
(distinct `pre_scan_ok`/`post_scan_ok` tracking variables so the two
retries don't interfere with each other).

**Update: the `&&` fix above turned out to be incomplete.** The exact
same error recurred for the pure `&&`-chained command too - `for`/`;`
were never actually the cause. Root-caused properly below.

## The actual root cause: `Common/SSH` wraps `command` in an extra pair of double quotes

Isolated with a bisection down to the simplest possible case: a
single-word command (`whoami`) succeeded through `Common/SSH` and
returned `ec2-user` correctly. A two-word command (`echo hello`) failed
with `bash: line 1: echo hello: command not found` - note it's the
*entire two-word string* being reported as one not-found command name.
Reproduced this exactly, locally, with `bash -c "\"echo hello\""` (a
command wrapped in one extra pair of literal double quotes before being
handed to `-c`) - identical error, character for character. A
single-quoted word doesn't change how bash parses it, so single-word
commands were never actually a real test of anything - `Common/SSH`
appears to always wrap whatever we pass as `command` in an extra pair of
double quotes before the remote shell sees it, and bash's own quoting
then swallows every space in a multi-word command into one big
single-quoted token, which cannot resolve to any real executable.

This also means `InstallTrivy` was **silently failing this entire time**
- nothing downstream ever checked its result, and the target host
happened to already have Trivy installed from manually testing it
directly over SSH during this same debugging session, which is why its
failure was never visible.

**The fix**: since the extra quote is always exactly one pair wrapped
around the whole string, it can be defeated from inside the command
itself - prepend `:" ; ` and append ` ; ":` to whatever real command you
want to run. This makes the extra quote Concert adds close immediately
after a harmless `:` (bash no-op) command, runs the real command
completely unquoted so `&&`/`;`/spaces all behave as real shell syntax,
then reopens a quote right before the trailing one closes it. Verified
end-to-end for real, not just in local `bash -c` simulation: sent the
exact escaped `RunScan` command over a real SSH session to the real test
host, wrapped in the same extra quote Concert adds, and got a clean exit
0 with the full valid Trivy JSON report - no partial/simulated result.

Every `Common/SSH` command in both this workflow and `Remediate_SSH_Host`
now goes through this `ssh_escape()` wrapper before being set as the
`command` input - including `InstallTrivy`, which needed it just as much
even though its failure was invisible before.

**Still unconfirmed**: whether `Ansible/Playbook`'s multi-line YAML
`playbook` input hits the same class of bug - that's a different code
path from `Common/SSH`, so this fix doesn't automatically cover it. If
the same symptom shows up there, this is the section to revisit.

## Another real bug found live: `Common/SSH`'s default 30s inactivity timeout

The first live run of this CSV version failed at `BuildVmScanCsv` with
`Unexpected end of JSON input` - `$RunScan.result` was empty. Trivy's
`--quiet` scan produces no stdout while it works (downloading its
vulnerability DB, then scanning), and `Common/SSH`'s `timeout` input
defaults to 30 seconds of *inactivity*, not a hard ceiling - a real scan
can easily run past that in silence and get killed mid-way, leaving
nothing in the remote temp file for `cat` to return. Fixed by setting
`timeout: 300` explicitly on `InstallTrivy`/`RunScan`, and
`BuildVmScanCsv` now throws a clear error naming the exit code instead of
a bare `JSON.parse` crash if this happens again.

## What it does

1. **`InstallTrivy`** (`Common/SSH`): `authKey=$ssh_auth`, installs Trivy
   on the target if not already present (idempotent).
2. **`RunScan`** (`Common/SSH`): runs `trivy rootfs --format json` against
   `/` on the target (a small shell-level retry loop handles Trivy's
   vulnerability-DB download being occasionally rate-limited), writes to
   a remote temp file, `cat`s it back, deletes the temp file.
3. **`BuildVmScanCsv`** (plain JS `function` block): parses the JSON,
   keeps only `Class == "os-pkgs"` vulnerabilities (excludes `lang-pkgs`
   findings, e.g. Trivy's own bundled Go dependencies), and emits the
   Concert-native `vm_scan` CSV, one row per CVE, using `$target_host` for
   the Host IPAddress/Host Name columns.
4. **`IngestScan`**
   (`system/IBM/Concert v2/Import Data/Upload Files to Concert`): uploads
   the CSV with `data_type: "vm_scan"`, `scanner_name: "concert"`.

Unlike the repo/image scans, Trivy runs **on the remote host itself**
(there's no "scan a live machine over the network" mode in Trivy) - the
SSH blocks just drive it remotely and relay the JSON output back. The
target host ends up with Trivy installed on it afterwards (left in place,
same tradeoff as any first-run agent install).

## Inputs

| Input | Required | Notes |
|---|---|---|
| `ssh_auth` | yes | SSH credential name for the target host |
| `concert_auth` | yes | IBM Concert API Key credential name |
| `target_host` | yes | IP/hostname of the target - populates the CSV's Host IPAddress/Host Name columns, which is what Concert uses to attribute these CVEs to this specific host |

## Trigger payload

```json
{
  "ssh_auth": "concert-cve-ssh-key",
  "concert_auth": "concert-api-auth",
  "target_host": "35.158.156.105"
}
```

## How to import

Concert Workflows console -> Workflows -> Import -> `Trivy_SSH_Host_Scan.zip`
(the picker greys out loose `.json` files - always import the `.zip`).
**Delete any previously-imported version first.**

## Verified vs. not verified

**Verified locally**:
- `RunScan`'s shell command passes `bash -n`.
- `BuildVmScanCsv`'s JS was actually *executed* (not just syntax-checked)
  in Node against a sample Trivy JSON report containing both `os-pkgs`
  and `lang-pkgs` findings, plus a description with embedded commas and
  quotes - confirmed `lang-pkgs` is excluded and the CSV escaping is
  correct (round-tripped back through Python's own `csv` module reader).
- Every block's action path and input names were copied directly from
  real, working IBM-published workflows, not guessed from a schema file.

**Not verified - genuinely unconfirmed**:
- A live run with the new CSV format hasn't happened yet - the previous
  live run (confirmed working end-to-end, `202 Accepted`) used the old
  CycloneDX/`code_scan`-style payload that turned out not to attribute
  CVEs to the host distinctly in Arena view. This CSV version is the fix
  for that, not yet itself confirmed live.
