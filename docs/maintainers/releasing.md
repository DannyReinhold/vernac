# Publishing a Vernac release

These instructions prepare **0.5.0** from **feature/0.5.0**. Source checkouts keep
**0.5.0-SNAPSHOT**; the workflow selects the release version without changing every POM.
The [release checklist](../development/release-0.5.0.md) records remaining verification,
and the [release notes](releases/0.5.0.md) define the scope. Preparation does not mean
that publication or Marketplace approval has already happened.

## Scope and one version input

`revision` in the root POM drives Maven modules, the IntelliJ ZIP and the dependency
version inserted by the project wizard. A release invocation uses `-Drevision=0.5.0`;
the development default is not changed by that invocation. The workflow supplies
that argument; the source checkout defaults to 0.5.0-SNAPSHOT.
You do not need to edit every module POM or pre-install a 0.5.0 release locally.

Standalone application examples generally use their own `vernac.version` property. Select
`-Dvernac.version=0.5.0` to consume the release after publication. Their application
version, often 0.1.0-SNAPSHOT, is independent and need not match Vernac.
The type-modules source reactor instead inherits the root `revision`; build it with
matching local artifacts, or override `revision` consistently across that reactor.

The shared publication inventory in `tools/release.py` contains:

| Maven Central coordinate | Artifact |
| --- | --- |
| org.vernac:vernac | Parent POM |
| org.vernac:vernac-runtime | Core domain/application runtime |
| org.vernac:vernac-runtime-json | DTO/JSON runtime |
| org.vernac:vernac-runtime-http | Outbound HTTP and inbound HTTP support |
| org.vernac:vernac-runtime-jdbc | JDBC persistence/event runtime |
| org.vernac:vernac-jdbc-spring-boot-starter | Spring/JDBC integration |
| org.vernac:vernac-compiler | Compiler |
| org.vernac:vernac-maven-plugin | Build/schema tooling |

Examples are not published. The language server is bundled with the IntelliJ ZIP;
the plugin is distributed through GitHub and JetBrains Marketplace, not Central.
Its stable Marketplace ID is **org.vernac**. Source packages org.vernac.intellij and
the vernac-intellij Maven module keep their existing names.

## GitHub Actions

The established workflows remain manually dispatched:

- **Vernac Release** builds/tests with disposable PostgreSQL, checks documentation,
  signs publication artifacts and archives the plugin ZIP plus provenance manifest.
  A real run uploads to Central and then invokes the plugin workflow.
- **Publish IntelliJ Plugin** verifies the original archived candidate, waits for
  matching Central artifacts, builds a fresh consumer and uploads the **same ZIP**
  to the existing Marketplace listing. It does not redeploy Maven coordinates.

Neither workflow creates Git tags/GitHub Releases or publishes automatically on a
push. Record the exact selected commit. A GitHub manual launcher must exist on the
default branch; the existing launcher can select `feature/0.5.0` for its dry run.
After merge, use main for the real release.

### Existing secret configuration

Keep all credentials outside the repository. Only these GitHub secret **names**
belong in documentation:

| Secret | Purpose |
| --- | --- |
| CENTRAL_USERNAME | Central Portal token username |
| CENTRAL_PASSWORD | Central Portal token password |
| GPG_PRIVATE_KEY | Signing key used by the CI Bouncy Castle signer |
| GPG_PASSPHRASE | Signing-key passphrase |
| JETBRAINS_MARKETPLACE_TOKEN | Token permitted to update org.vernac |

Token labels in a password manager are independent of GitHub secret names. Never
paste values into workflow inputs, POMs, command arguments, issues or shared logs.
The workflow's ephemeral PostgreSQL credentials are disposable test-service values,
not production credentials. Dry runs require signing secrets but do not upload.

### 1. Verify and push the release preparation

From the repository root, with JDK 25 and the documented disposable PostgreSQL
variables configured, run the acceptance checks in the release checklist. The root
build does not include every standalone example or replace an installed-IDE smoke test.

```text
mvn clean install
python tools/check-doc-links.py
python -m unittest discover -s tools/tests -v -b
```

Review the Git diff and commit intended source only. Keep credentials, target output,
flattened POMs and tutorial migration experiments out of the commit. Push the branch.

### 2. Run the dry run on feature/0.5.0

Open **Actions → Vernac Release → Run workflow**. Select `feature/0.5.0`, enter
`version = 0.5.0`, and leave **dry_run = true**.

The workflow builds and signs without uploading to Central or Marketplace. Its test
gate rejects missing required suites and skipped/failed reactor reports, including
PostgreSQL, HTTP inbound, documentation and plugin distribution checks.
A green local build with skipped PostgreSQL tests cannot satisfy that gate.

Download `vernac-plugin-0.5.0-<attempt>` from the run's artifacts. Install the inner
plugin ZIP, not GitHub's outer download archive. Keep release.json with it. Check:

- Displayed version and IDE compatibility from the packaged plugin.xml.
- Project creation, coordinates, namespace paths and automatic Maven import.
- File/namespace actions, highlighting, diagnostics, completion and navigation.
- Inline Java assistance and Java-to-Vernac navigation after generation.

For an unpublished release, the dry-run plugin's wizard targets that new release
version, which is not on Central yet. For this smoke
check, use a disposable generated project with matching locally installed artifacts
or temporarily set its vernac.version to the matching source-checkout SNAPSHOT. This does not replace the
workflow's **post-publication fresh Central consumer** check. Restore experiments.

A dry-run candidate cannot later be uploaded by the plugin-only workflow. The real
release must build its own candidate from the reviewed source. A merge can change
the commit identity; record the final main commit and inspect its release run.

### 3. Merge and publish the real release

After the dry run and remaining acceptance checks:

1. Merge the reviewed branch into main and confirm its exact source commit.
2. Run **Vernac Release** on main with **version = 0.5.0**, **dry_run = false**.
3. Existing release tags/visible Central versions are rejected; published coordinates
   are immutable. Do not try to replace them.
4. When Central validation succeeds, open the **existing deployment** in the
   [Central Portal](https://central.sonatype.com/publishing/deployments), inspect the
   coordinates and choose Publish. `autoPublish=false` is deliberate.
5. The plugin job waits up to 30 minutes for all eight coordinates. It then extracts
   the archived template, selects 0.5.0, adds HTTP/JDBC dependencies and builds with
   a fresh Maven repository. Local installs cannot conceal missing Central artifacts.
6. Only after that succeeds does it submit the original ZIP to Marketplace Stable.
   Upload acceptance is not JetBrains approval. Verify the listing and installation
   after review; record each distribution's actual status.

Do not use **Re-run all jobs** after Central upload: that can repeat deployment.
If Central rejects an unpublished bundle, inspect/correct it and discard the failed
deployment before another real run. If it is Published, never redeploy that version.

### 4. Retry only the Marketplace step when needed

If Central takes longer than the wait window, the fresh-consumer check has a temporary
failure, or Marketplace rejects submission, inspect both services first. Then run
**Publish IntelliJ Plugin**, supplying version **0.5.0**, the original release **run ID**,
and original candidate **attempt** from its summary.

Use dry_run=true for provenance checks; dry_run=false waits for Central, checks the
fresh consumer and uploads. The original repository/commit/run/attempt, required
candidate steps, manifest and ZIP SHA-256 are verified. No new Maven deployment or
plugin rebuild occurs. No automatic upload retry occurs: a timeout can follow an
accepted upload, so inspect Marketplace before submitting again.

Artifacts are retained for 90 days, subject to repository policy. Preserve the verified
ZIP/manifest in the GitHub Release; automated recovery cannot retrieve an expired
artifact. Do not rebuild and label a new binary as the previously tested one.

### 5. Record tags, GitHub releases and next development

After publication and consumption verification, tag the **exact real-release commit**.
For the coordinated Maven/plugin release, follow the existing v0.2.0 convention:

```text
git tag -a v0.5.0 RELEASE_COMMIT_SHA -m "Release Vernac 0.5.0"
git push origin v0.5.0
```

Replace RELEASE_COMMIT_SHA with the recorded SHA. Create one GitHub Release from
v0.5.0 using the reviewed [notes](releases/0.5.0.md). Attach the original plugin ZIP
and release.json. Include actual verification outcomes and Marketplace status.
Never move an existing published tag. Historical separate maven-v/plugin-v tags
remain valid; use those when a distribution intentionally has a different source
commit or release version.

Only afterward create the next development branch and change root revision plus
standalone/template vernac.version defaults together to the next agreed SNAPSHOT version. Make that a separate commit. Published 0.5.0
reference pages can stay anchored to the release until a new language revision is
prepared; do not declare a future release complete by editing a status paragraph.

## Local publication fallback

Prefer the workflow above. For a deliberate local release use JDK 25 and Maven
**3.9.16**, matching CI. Verify `mvn -version`, `gpg --version`, and the signing key.
Keep the Portal server entry named `central` in the user's `.m2/settings.xml`, and
passphrases in GnuPG's agent. Never place them in the POM or command line.

Record a clean source SHA, run the complete acceptance checks, then build/sign:

```text
mvn -Prelease -Drevision=0.5.0 -pl vernac-runtime-http,vernac-runtime-jdbc,vernac-jdbc-spring-boot-starter,vernac-maven-plugin -am -Dgpg.keyname=YOUR_SIGNING_KEY_FINGERPRINT clean verify
```

Check main/source/Javadoc JARs, their `.asc` files and flattened publication POMs
for matching parent/dependency versions. The classless JDBC starter's Javadoc JAR
contains its explanatory README. The parent publishes a POM plus signature.
To upload the same reviewed source, replace `verify` with `deploy`. Validation stops
at the Portal; publication is still manual. Do not run this if the workflow has
already uploaded the release. Record the deployment ID and status.

After publication, consume the basic template from a separate directory with
`vernac.version=0.5.0` and a fresh Maven repository; inspect downloads from Central.
Also verify HTTP/JDBC starter consumption. The workflow performs that combined check.

For local plugin builds use `mvn -Drevision=0.5.0 -pl vernac-intellij -am clean verify`.
Retain and test `vernac-intellij/target/vernac-intellij-0.5.0-plugin.zip`. Publish
matching Maven artifacts first so newly created projects can resolve dependencies.
Keep the Marketplace ID stable; normal updates reuse the existing listing.

## Troubleshooting and historical context

- **GPG not found:** check PATH and restart IntelliJ after installation. The Maven
  runner and terminal can use different JDKs/settings/environments.
- **Javadoc fails:** repair comments, using `{@code ...}` for angle-bracket literals;
  do not disable validation to conceal errors.
- **Central says a directory has no POM:** inspect the uploaded bundle. Maven 3.10
  previously introduced staging metadata; workflows pin/checksum Maven 3.9.16. A
  verify-only dry run does not exercise Central's bundle validation.
- **Database tests skipped:** configure the documented disposable server and inspect
  reports. A running Docker CLI alone is not a test result.
- **Plugin template cannot resolve artifacts:** compare the injected Vernac version
  with what is actually Published on Central. Plugin upload and Maven publication
  are different services.

Historical tags remain unchanged: v0.1.0 identifies an earlier preview;
maven-v0.1.0 the first Central release; plugin-v0.1.0 the corrected Marketplace ID.
The original org.vernac.intellij plugin ID was replaced once with org.vernac. Existing
published 0.1.x/0.2.x artifacts and tags must not be moved or reused.

Offline release-tool tests mock network requests and never publish:

```text
python -m unittest discover -s tools/tests -v -b
```

API references: [JetBrains upload](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html),
[GitHub artifacts](https://github.com/actions/download-artifact),
[Central publishing](https://central.sonatype.org/publish/publish-portal-maven/).

## Community reporting channels

The root [contribution guide](https://github.com/DannyReinhold/vernac/blob/main/CONTRIBUTING.md),
[Code of Conduct](https://github.com/DannyReinhold/vernac/blob/main/CODE_OF_CONDUCT.md), [security policy](https://github.com/DannyReinhold/vernac/blob/main/SECURITY.md)
and GitHub issue/PR templates provide contributor entry points. Keep their contact
information and linked build guidance current.

Repository administrators should enable **Private vulnerability reporting** in
GitHub's security settings. Adding `SECURITY.md` does not enable that setting.
Until it is enabled, the policy's email fallback remains the private contact.
Review [Community Standards](https://github.com/DannyReinhold/vernac/community)
after changes reach the default branch; this checklist is not a code-quality or
security certification.
