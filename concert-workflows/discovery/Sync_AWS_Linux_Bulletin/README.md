# 01d - Prerequisite: Sync AWS Linux Bulletin

**Unmodified copy of IBM's own published sample** from
[`IBM/Concert`'s `concert-workflows/Vulnerability/Scans`](https://github.com/IBM/Concert/blob/main/concert-workflows/Vulnerability/Scans/Sync_AWS_Linux_Bulletin.zip)
(same convention this repo already uses for
[Trivy_GitHub_Scan](../Trivy_GitHub_Scan) and
[Maven_Package_Upgrade](../../remediation/Maven_Package_Upgrade)).

## Why this is needed for [Trivy_SSH_Host_Scan](../Trivy_SSH_Host_Scan)/[Remediate_SSH_Host](../../remediation/Remediate_SSH_Host)

Uploading a `vm_scan` CSV with a real, detected CVE (confirmed live:
`CVE-2023-45853` on a deliberately downgraded `zlib`) gets accepted by
Concert and shows up as a CVE Finding under the application - but **no
native `OS`-type auto-remediation action gets generated**, only `Package`-
type ones (from the Java/Maven-based workflows). This isn't a bug in the
scan workflow - Concert's "OS" action generation depends on a separate
knowledge base, `os_advisory_cache`, that has to be populated first by
**this** workflow (or one of its siblings -
`Discover_Redhat_Advisories`/`Discover_Ubuntu_Advisories`/etc. for other
distros).

This workflow scrapes the real, public Amazon Linux Security Advisory
pages (`alas.aws.amazon.com/alas1.html`, `/alas2.html`, `/alas2023.html`),
extracts each advisory's CVE IDs, severity, and (for ALAS2023) the exact
`dnf update --advisory=...` fix command, and pushes them to Concert via
`system/IBM/Concert v2/Advisory/Create Advisory Cache Data` - each entry
keyed as `<CVE-ID>_OS_amazonlinux_<version>`. Concert's own (internal,
not a workflow) correlation logic is presumably what then matches this
cache against a host's reported CVEs to generate the `OS`-type action -
not something this repo controls directly.

## How to run

**No inputs required at all** - every variable (`Endpoints`,
`awsAdvisoryBaseUrl`, batch counters) already has a working default.
Just import and trigger with an empty/default payload. Note the
`system/IBM/Concert v2/Advisory/Create Advisory Cache Data` block takes
no `authKey` either - it authenticates implicitly as the current Concert
instance running the workflow, unlike the ingestion/action blocks used
elsewhere in this repo's SSH-host workflows.

Run this **once** (or periodically, to keep the cache fresh) before
expecting `OS`-type actions from `Trivy_SSH_Host_Scan`'s findings - it's
infrastructure setup, not part of the per-host scan/remediate loop.

## How to import

Concert Workflows console -> Workflows -> Import -> `Sync_AWS_Linux_Bulletin.zip`.

## Verified vs. not verified

**Not verified live yet** - copied unmodified from IBM's repo on the
strength of it being an official published sample, same trust level this
repo already extends to `Trivy_GitHub_Scan`/`Maven_Package_Upgrade`.
Whether it actually causes Concert to generate an `OS`-type action for
our `CVE-2023-45853`/`zlib` finding (as opposed to just populating the
cache with no visible effect) is the next thing to confirm by running it
and re-checking the application's Action Center.
