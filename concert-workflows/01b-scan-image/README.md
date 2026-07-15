# 01b - Scan image: Trivy Image Scan

Custom Concert Workflow (same `system/FaaS/Python` block schema as the
official `trivy-github-scan` sample) that scans the **built container
image** (`ghcr.io/kokunas/java-app-cve`) instead of the source repo -
picking up OS-level packages from the base image (`eclipse-temurin:17-jre-jammy`,
Ubuntu 22.04) plus the bundled Java libraries, on top of what
[01-scan](../01-scan) already finds from `pom.xml`.

## Why this exists

The "correct" way to register a container image in Concert is
`POST /core/api/v1/build_artifacts`. **Confirmed live that this endpoint
is unreliable in this installation**: it returns
`errMismatchInURIandName` even for a minimal, schema-valid payload
(`{"name": "java-app-cve", "type": "image"}`), and a follow-up retry with
an even simpler payload timed out instead of erroring - inconsistent
failure modes pointing to a genuine platform bug, not a payload mistake.
This is a second, independent bug from the CycloneDX version issue below,
in the same general area (application-resource ingestion).

Given that, this workflow reuses the same `/ingestion/api/v1/upload_files`
`code_scan` path already proven reliable for [01-scan](../01-scan), just
pointed at the image instead of the repo. Concert creates a second
`source_repo`-shaped entry named after the image
(`ghcr.io/kokunas/java-app-cve:latest`) associated with the same
`bankdemo` application, and its vulnerabilities merge into the
application's CVE list. **Not a true build-artifact entity** in Concert's
data model - but it gets the image's real vulnerability data attached to
the application, which is what matters for the demo. Revisit this once
IBM fixes the `build_artifacts` endpoint.

## Same CycloneDX fix as 01-scan

Also applies the `specVersion` 1.6 -> 1.5 rewrite documented in
[01-scan's README](../01-scan/README.md) - confirmed necessary here too
(same `errUnsupportedCycloneDXVersion` failure otherwise).

## How to import

Concert Workflows console -> Workflows -> Import -> `Trivy_Image_Scan.zip`.

## Trigger payload for this demo

Every field is already pre-filled as a default except `concert_api_key`:

```json
{
  "image_ref": "ghcr.io/kokunas/java-app-cve:latest",
  "concert_url": "https://concert-concert.apps.itz-4j78fp.pok-lb.techzone.ibm.com",
  "concert_api_key": "<your Concert API key>",
  "concert_instance_id": "0000-0000-0000-0000",
  "concert_allow_insecure": false,
  "application_name": "bankdemo",
  "application_version": "1.0.0"
}
```

## Verified locally

Ran the exact logic (apk/trivy install steps swapped for already-installed
local binaries) against the real `ghcr.io/kokunas/java-app-cve:latest`
image and the real Concert instance:
- Trivy found **215 components / 88 vulnerabilities** in the image (OS
  packages from Ubuntu 22.04 + bundled JARs) - versus 7 CVEs / 82 packages
  from the source-repo-only scan in [01-scan](../01-scan).
- Upload accepted (`202`), and `bankdemo`'s total CVE count went from 7 to
  88 after ingestion, confirming the image's findings attached correctly.
- Image digest was fetched directly from GHCR's registry API for
  reference during testing: confirm it's still reachable/public with
  `curl -H "Authorization: Bearer $(curl -s 'https://ghcr.io/token?service=ghcr.io&scope=repository:kokunas/java-app-cve:pull' | jq -r .token)" https://ghcr.io/v2/kokunas/java-app-cve/manifests/latest`
  (needs an `Accept: application/vnd.oci.image.index.v1+json` header since
  it's a multi-arch manifest list).
