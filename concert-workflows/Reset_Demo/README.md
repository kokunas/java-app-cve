# Reset_Demo - repeatability

Run this **before every demo**. Makes two things true again:

1. **The code is vulnerable again.** Reverts `pom.xml` and
   `VulnerableSearchRepository.java` on `main` back to the
   [`vulnerable-baseline`](https://github.com/kokunas/java-app-cve/releases/tag/vulnerable-baseline)
   git tag, via direct commits through the GitHub Contents API (no PR -
   this is demo housekeeping, not a reviewed change). The push
   automatically re-triggers `.github/workflows/build-and-push.yml`,
   republishing the vulnerable image to `ghcr.io/kokunas/java-app-cve`.
   Idempotent: if a file already matches the baseline, it's left alone
   (verified locally - reruns log `"already matches ... nothing to
   reset"` instead of creating no-op commits).
2. **Concert is empty.** Deletes every `application`, `source_repo`,
   `build_artifact`, `environment`, and `certificate` from the Concert
   instance via its own core API, so the next scan starts from nothing -
   matching the requirement that Concert has no demo data loaded before
   you start.

## Why not just re-baseline in git and leave Concert alone?

Because the demo's whole point is showing Concert **discover** the CVEs
from scratch (connect to GitHub, scan, see findings appear, prioritize).
If the `bankdemo` application/source_repo already exists with old scan
data, that first "watch it get discovered" beat doesn't land the same way
for a repeat audience.

## After running this, redeploy the running pods

Reset_Demo fixes the **source** and the **image** (new vulnerable image
lands in GHCR within ~1-2 minutes), but the **already-running** OpenShift
pods don't restart themselves. Before the next demo, roll them:

```
oc rollout restart deployment/bankdemo-app -n banco-kokunas
```

## How to import

Concert Workflows console -> Workflows -> Import -> `Reset_Demo.json`
(single file, no subflows - unlike [Remediate_All](../Remediate_All) this
one doesn't need to be zipped).

## Trigger payload for this demo

```json
{
  "gh_repo_url": "https://github.com/kokunas/java-app-cve",
  "gh_api_token": "<GitHub token - contents:write>",
  "baseline_ref": "vulnerable-baseline",
  "target_branch": "main",
  "reset_files": "[\"pom.xml\", \"src/main/java/com/kokunas/bankdemo/repository/VulnerableSearchRepository.java\"]",
  "concert_url": "https://concert-concert.apps.itz-4j78fp.pok-lb.techzone.ibm.com",
  "concert_api_key": "<Concert API key>",
  "concert_instance_id": "0000-0000-0000-0000"
}
```

## Verified locally

Ran this exact logic twice against the real repo and the real Concert
instance during development:
- **Idempotent run**: both files already matched baseline -> logged
  `already_reset`, no commits created; Concert had 1 application + 1
  source_repo -> both deleted successfully.
- **Real revert run**: pushed a simulated merged fix (log4j bumped to
  2.24.3) directly to `main`, then ran Reset_Demo again -> it correctly
  detected the drift and reverted `pom.xml` via a real authenticated PUT
  to the GitHub Contents API (commit `1cc3ca2`), while leaving the
  already-correct SQLi file untouched.
