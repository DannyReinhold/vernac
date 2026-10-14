# Build a board with independent pages

Status: **0.4.0-SNAPSHOT offset and cursor repository queries implemented**. This chapter shows
numbered pages, opt-in totals, eager projections and lazy traversal. Paginated Result and GET/DTO declarations are implemented.

## A board is more than one page

Save this model as `src/main/vernac/example/paging/paging.vernac`. It uses the same
PostgreSQL/JDBC setup and Flyway workflow as the [database tutorial](database-persistence.md).

```vernac
namespace example.paging;

id IssueId;
value Title(String);
value PageNumber(int) validates { require(self.intValue() > 0, "Page number must be positive"); };
value PageSize(int) validates { require(self.intValue() > 0, "Page size must be positive"); };
value CursorToken(String);
value IssueStatus = OPEN | CLOSED;
value IssueSummary(IssueId, Title) list IssueSummaries;
aggregate Issue[IssueId](Title, IssueStatus) list Issues;

repository IssueRepository for Issue {
    find offset Issues withStatus(IssueStatus status)
        where issueStatus = :status order by createdAt desc, id asc counting;
    find cursor Issues recent() order by createdAt desc, id asc;
}

usecase ReadBoard(PageNumber openPage, PageNumber closedPage, PageSize, CursorToken? cursorToken)
    returns (Title heading, counted offset IssueSummaries openIssues,
        counted offset IssueSummaries closedIssues, cursor IssueSummaries activity)
    uses IssueRepository
    behavior {
        java imports {
            org.vernac.runtime.pagination.OffsetRequest;
            org.vernac.runtime.pagination.CursorRequest;
            org.vernac.runtime.pagination.PageCursor;
        }
        execute {
            var open = self.issueRepository().withStatus(IssueStatus.OPEN)
                .page(OffsetRequest.ofPage(openPage.intValue(), pageSize.intValue()))
                .mapItems(IssueSummaries::of, issue -> summarize(issue));
            var closed = self.issueRepository().withStatus(IssueStatus.CLOSED)
                .page(OffsetRequest.ofPage(closedPage.intValue(), pageSize.intValue()))
                .mapItems(IssueSummaries::of, issue -> summarize(issue));
            var recentRequest = cursorToken
                .map(token -> CursorRequest.after(PageCursor.of(token.string()), pageSize.intValue()))
                .orElseGet(() -> CursorRequest.first(pageSize.intValue()));
            var activity = self.issueRepository().recent().page(recentRequest)
                .mapItems(IssueSummaries::of, issue -> summarize(issue));
            return Result.of(Title.of("Issue board"), open, closed, activity);
        }
        private IssueSummary summarize(Issue issue) { return IssueSummary.of(issue.id(), issue.title()); }
    }

adapter BoardApi inbound http uses ReadBoard readBoard {
    base "/api/board";
    board {
        GET "/";
        query int openPage;
        query int closedPage;
        query int pageSize;
        query String? cursor;
        execute readBoard {
            openPage <- query.openPage;
            closedPage <- query.closedPage;
            pageSize <- query.pageSize;
            cursorToken <- query.cursor;
        }
        respond 200 BoardDto using board;
    }
    schema IssueDto(UUID id, String title);
    schema BoardDto(String heading, counted offset list<IssueDto> openIssues,
        counted offset list<IssueDto> closedIssues, cursor list<IssueDto> activity);
    mapping issue: IssueDto <- IssueSummary { id <- source.issueId; title <- source.title; }
    mapping board: BoardDto <- ReadBoard.Result {
        heading <- source.heading;
        openIssues <- source.openIssues using issue;
        closedIssues <- source.closedIssues using issue;
        activity <- source.activity using issue;
    }
}
```

ReadBoard assembles a title, an open-issue page, a closed-issue page and a recent
activity page. The two numbered lists have independent positions; the cursor list
has an independent continuation. Nothing forces the entire result to be one Page.

The input PageNumber/PageSize types here are **application-defined value objects**.
Their intValue() calls are explicit projections into the supplied runtime request
factory; no type recognition by spelling occurs. You do not import the runtime
PageNumber/PageSize types into this model. CursorToken is also an ordinary value;
the repository provider validates the wrapped token when loading a continuation.

The repository loads whole aggregates. mapItems eagerly projects them to immutable
IssueSummary values, preserving each page's metadata. The Result header declares
`counted offset` rather than relying on Java-body inference. `offset` is the
uncounted variant; `cursor` carries an optional continuation instead of totals.
All page results require a declared **value list** and are non-optional. An empty
page is still a page. Returning aggregate pages or lazy sequences is not allowed.

The HTTP adapter wraps query numbers as domain inputs, calls the generated usecase
and maps the completed Result. `using issue` maps every page entry through the
adapter-local ACL; its enclosing page metadata is copied automatically. The ordinary
heading uses the existing scalar/VO conversion. No database work occurs in serialization.

Try GET `/api/board?openPage=2&closedPage=1&pageSize=20`. A response has this shape
(the entries and totals depend on your data):

```json
{
  "heading": "Issue board",
  "openIssues": {
    "items": [], "offset": 20, "pageSize": 20, "pageNumber": 2,
    "hasNext": false, "totalElements": 0, "totalPages": 0
  },
  "closedIssues": {
    "items": [], "offset": 0, "pageSize": 20, "pageNumber": 1,
    "hasNext": false, "totalElements": 0, "totalPages": 0
  },
  "activity": { "items": [], "pageSize": 20 }
}
```

When activity has a continuation, send its nextCursor as the next GET's `cursor`
parameter. Reapply all permission/tenant filters on every request. A modified or
incompatible repository cursor produces HTTP 400 with code INVALID_CURSOR and no
private token details. Ordinary domain validation remains 422; optimistic conflicts
remain 409. Offsets beyond the end retain the true total and requested page number.

The explicit createdAt/id order is unambiguous even when timestamps tie. Counting
and root selection share one statement snapshot **per page**; child loads verify
versions. Multiple board components are independent queries and do not implicitly
share a frozen snapshot beyond the existing usecase transaction's isolation.
Concurrent inserts/deletes can shift later offsets or move sort keys.

For a runnable application with real repositories, start
[Vernac Dispatch](../../examples/dispatch/README.md#paged-board-api). It provides the
same structure at `/api/tour-board`; no issue-tracker bootstrap data is assumed here.

Configure a positive maximum in application.yaml when needed:

```yaml
vernac:
  pagination:
    max-page-size: 500
```

The default is 1000. Requests above the limit fail before SQL, rather than being
clamped. Count and roots share one statement snapshot. Child loads verify root
versions: a concurrent update/delete can cause an optimistic-lock conflict. Separate
page loads may observe inserts/deletes and shifted offsets; neither counting nor
explicit ordering freezes a whole traversal. Long traversals still mean long
transactions; choose page sizes and indexes with real workloads in mind.

## Explore the same runtime without a database

Use a matching source-checkout compiler/runtime. Save this as
`src/main/vernac/example/paging/names.vernac`:

```vernac
namespace example.paging;

value Name(String) list Names;
value DisplayName(String) list DisplayNames;

usecase InspectNames() behavior {
    java imports {
        java.util.List;
        org.vernac.runtime.pagination.CountedOffsetQuery;
        org.vernac.runtime.pagination.CountedOffsetPage;
        org.vernac.runtime.pagination.OffsetRequest;
    }
    execute {
        var data = List.of(Name.of("Ada"), Name.of("Grace"), Name.of("Linus"));
        CountedOffsetQuery<Name, Names> sample = request -> {
            int start = (int) Math.min(request.offset(), data.size());
            int end = start + Math.min(request.pageSize(), data.size() - start);
            return CountedOffsetPage.of(Names.of(data.subList(start, end)), request, data.size());
        };
        sample.pages(OffsetRequest.ofPage(1, 2)).items()
            .forEach(name -> System.out.println(name.string()));
    }
}
```

Compile with the application's normal Maven build. The example loads pages from a
fixed in-memory fixture; no database is needed for this second model.
The first page contains Ada/Grace, the second Linus. Calling the generated usecase
requires an injected Spring bean and transaction manager, as in the
[usecase tutorial](usecases.md). To explore the runtime in a plain Java test, use the
same lambda directly without the usecase wrapper; the pagination library itself
requires no Spring or database.

## Fetch one page and inspect its count

With that same sample loader in Java:

```java
var page = sample.page(OffsetRequest.ofPage(2, 2));
page.items();         // Names containing Linus
page.pageNumber();    // OptionalLong[2]
page.totalElements(); // 3
page.totalPages();    // 2
page.hasNext();       // false
```

Try page 9: entries are empty, totalElements stays 3, pageNumber stays 9. Try
`OffsetRequest.ofOffset(1, 2)`: it returns Grace/Linus, but pageNumber is empty
because the requested window straddles ordinary numbered pages.

Supplied request values can be passed directly:

```java
OffsetRequest.ofPage(PageNumber.of(2), PageSize.of(2));
```

Import PageNumber/PageSize from org.vernac.runtime.pagination. These are technical
Java request values, not yet allowed as parameter types in a Vernac header. An
application-defined VO called PageNumber remains a different type.

## Project the completed page

```java
var projected = page.mapItems(
    DisplayNames::of,
    name -> DisplayName.of(name.string().toUpperCase(java.util.Locale.ROOT))
);
```

The entries become DisplayName values and items() returns DisplayNames. Page number,
size, total and continuation semantics stay intact. The factory is explicit because
a mapper producing DisplayName does not identify which of its possible named lists
you mean. Mapping occurs immediately; it never loads the next page. Filtering or
reordering in that factory would invalidate metadata and is rejected.

## Iterate pages or entries

```java
sample.pages(OffsetRequest.ofPage(1, 2))
    .forEach(part -> System.out.println(part.items()));

sample.pages(OffsetRequest.ofPage(1, 2)).items()
    .forEach(name -> System.out.println(name.string()));
```

Each traversal starts fresh and loads only as needed. This is sequential Iterable
processing, not a parallel Stream or a frozen snapshot. A consumer exception stops
before loading a following page. A database-backed traversal must stay inside its
transaction; consuming a large dataset still makes that transaction long.

## Continue after the last ordered key

With the generated repository from the first model:

```java
var first = self.issueRepository().recent().page(CursorRequest.first(20));
if (first.nextCursor().isPresent()) {
    var next = self.issueRepository().recent()
        .page(CursorRequest.after(first.nextCursor().get(), 50));
    System.out.println(next.items());
}
```

The next page begins strictly after the first page's last returned sort tuple. Size
can change; the filter status must stay the same. The final page has no nextCursor.
Deleting the last returned issue does not break continuation: the token carries its
boundary values. Concurrent inserts before that boundary are not revisited; changed
sort keys can still produce omissions/repetitions. This is not a frozen snapshot.

Use offset pages for numbered navigation; use cursors for ordered continuation. Both
return complete aggregate graphs and allow eager projection into a named value list.
A cursor requires non-optional sort keys and explicit final id; counting is offset-only.

The JDBC token is encrypted and authenticated. It rejects modified tokens or tokens
from different queries/filters/schema versions with InvalidCursorException. Locally,
the default random process key needs no setup, but restart invalidates existing tokens.
For durable/shared cursors, inject the same secret into every replica:

```yaml
vernac:
  pagination:
    max-page-size: 500
    cursor-key: ${VERNAC_CURSOR_KEY:}
```

Supply standard Base64 of 32 cryptographically random bytes through secret management.
Do not commit the value. A blank value uses the local process key; changing the key
invalidates outstanding cursors. Tokens have no automatic expiry and are not access
credentials: apply tenant/permission filters on every request. The full owned schema
fingerprint is bound conservatively, so any owned-schema change starts new traversals.

For an HTTP interface, return only completed, projected pages as declared in the
first model. Never return a lazy JDBC sequence. Generated DTO codecs write the wire
envelopes explicitly without serializing the internal request or changing a global
ObjectMapper. A DTO page field uses list<LocalDto> or list<scalar>; nested containers
and PATCH page fields are rejected. Existing outer DTO naming/optional-field rules
apply, while the technical envelope's metadata names stay fixed in lowerCamelCase.

Read the [runtime contract](../contracts/pagination.md) and
[remaining implementation plan](../development/pagination.md).
