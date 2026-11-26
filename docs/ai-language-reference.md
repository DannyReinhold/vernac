# Working on a Vernac application

Check the Vernac plugin and runtime version in the application's POM. For an edit, open the affected
`.vernac` file and its test. If the project already has a similar declaration, follow it, make the
smallest relevant change and run the test. If a spelling or generated API is unfamiliar, open the
one task below that answers the question. After a compiler error, use its file, line and symbol to
narrow the next lookup.

| Task | Read |
| --- | --- |
| Start with an empty model | [One small model with an ID, value, aggregate, repository and use cases](ai/start-model.md) |
| Change a state transition | [Add reopening and test identity and state](ai/change-aggregate.md) |
| Filter a repository result | [Add a typed query and call the generated interface](ai/add-query.md) |
| Add an application operation | [Load an aggregate, invoke its behavior and save](ai/add-usecase.md) |
| Check the action on a particular row | [Test a view action through the rendered form](ai/test-view-action.md) |
| Generate or review a migration | [Build and migration commands](ai-tools-reference.md#generate-the-initial-or-next-migration-candidate) |

On **0.5.4**, use the core examples with Java-backed bodies and the project's Java tests. The
Vernac suites and typed view example require **0.6.0 development features** or a compatible later
build. Check version-specific contracts in the matching release tag or development checkout.

For an exact rule, open the relevant [language contract](contracts/index.md). To find a passage in
the [detailed reference](ai-language-details.md), list its headings with
`rg -n '^#{2,3} ' docs/ai-language-details.md` in the Vernac checkout, then read the matching
line range. For a single-file prompt, `python3 tools/bundle-ai-reference.py` prints the full reference.
