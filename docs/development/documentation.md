# Documentation ownership and maintenance

## Audiences and authority

| Location                  | Purpose                                           | Tone                                          |
|---------------------------|---------------------------------------------------|-----------------------------------------------|
| README                    | Explain the product and lead to the first success | Concrete, inviting, evidence-based            |
| docs/index.md             | Website landing page and navigation               | Standalone introduction                       |
| tutorials                 | Guided learning and experiments                   | Engaging, explicit prerequisites and outcomes |
| faq                       | Questions and recovery paths                      | Direct, practical                             |
| language-reference.md     | Complete syntax/capability overview               | Precise and searchable                        |
| specification + contracts | One normative specification                       | Formal requirements and explicit limits       |
| development/lessons-learned | Reusable engineering lessons and failure diagnosis | Technical, evidence and limitations explicit |
| development               | Mechanisms, rationale and active proposals        | Technical; plans labelled as plans            |
| maintainers               | Releasing and operations                          | Procedural and auditable                      |

The delivery story connects values to usecases, storage, events and external systems.
Focused Task, naming and DTO exercises remain useful; explain where they fit rather
than force every small example into a full application.

Each normative rule has one authoritative home. Cross-link instead of maintaining
independent rule tables in tutorials. Contracts MUST distinguish supported behavior
from proposals. Test outcomes do not belong in permanent normative status paragraphs.

## Consolidation decisions

The old event-driven-architecture, outbound-port-mapping, repository-db-mapping and
usecase-application-layer pages are now short navigation entries so existing links
remain useful. Obsolete architecture/examples were removed, not retained as competing
reference material. Five historical progress pages (namespace refactoring, Unicode,
member naming, enums and collections) were consolidated into compiler-architecture.
Git history retains their patch-by-patch evidence. Candidate-state design, scalar
representation rationale and other still-relevant technical decisions remain.

Reference, contracts and tutorials describe the **0.5.0 release line**. Source-checkout
instructions explicitly distinguish the local **0.6.0-SNAPSHOT** development artifact
from a **0.5.0** release build. Release builds select their version through revision. Documentation MUST NOT declare a release
published before publication, or introduce a future development target prematurely.
Historical release records retain their original versions and are labelled historical.
The example application version (such as 0.1.0-SNAPSHOT) is independent of its
vernac.version dependency. Automatic website version injection is not introduced here.

## Finding documentation

The [documentation map](../documentation-map.md) groups entry points by reader goal.
The website header links to it from every page using the default layout. A curated
sidebar provides four audience-based groups; phones use a native expandable menu.
Keep `docs/_data/navigation.yml` as the shared menu source. New DDD readers start
with the two introductory chapters rather than an unexplained list of patterns. Keep it
curated: link to canonical tutorials/specification indexes rather than duplicating
all contracts or building a second feature-status registry.

The [lessons-learned collection](lessons-learned/index.md) belongs under development.
Record reusable mechanisms, failure symptoms and verification boundaries, not raw
chat logs or a chronological list of patches. Separate prospective transfers (such
as SQLJ) from accepted Vernac behavior. Update implementation/acceptance status
when the corresponding checks are reported.

## Checks

DocumentationExamplesTest extracts the complete entry-point models from Markdown,
compiles their generated Java and exercises domain behavior. Existing mapping/REST
tutorial execution tests remain. Complete tutorial models, including the DDD beginner chapters, are also checked
for semantic validity and code generation. These checks do not validate every partial snippet,
IDE gesture, website renderer or production runtime claim.

Run the documentation link checker from the repository root:

```shell
python3 tools/check-doc-links.py
```

It checks local Markdown/HTML links and heading anchors in README, docs, examples and
templates; external network URLs are not fetched. In particular, verify the deployed
website separately before release. Root public entry pages must not depend on relative
links to files outside the published docs tree.

No credentials, private diagnostics, generated build output or transient assistant
transcripts belong in documentation. Release commands use placeholders; production
connection values must remain outside the repository.


For the website, links to examples or source outside `docs/` use explicit GitHub
blob/tree URLs; they cannot be served from the documentation source directory.
The checker validates matching repository-main URLs against local paths too.
Inside the site, `jekyll-relative-links` translates Markdown targets to built pages.
Navigation data and layout destinations have separate offline checks in tools/tests.
