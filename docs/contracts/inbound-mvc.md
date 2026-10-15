# Inbound MVC adapter contract

Status: 0.5.0 development. This contract extends the existing inbound HTTP boundary
with server-rendered views; it does not introduce a UI/template language.

## Declaration and ownership

`adapter Name inbound mvc uses Usecase ... { ... }` declares an internal HTTP
entrypoint. Like JSON inbound adapters, it generates a Spring controller and scoped
advice in `<namespace>.adapter.inbound.http`. Usecases own transactions and business
behavior. The controller MUST NOT create a transaction. Thymeleaf/templates, CSS,
JavaScript, authentication and CSRF configuration remain application-owned.

An MVC adapter MUST declare an absolute `base` path and a safe fallback
`error view "problem";`. HTTP route collision detection is shared with JSON adapters,
including placeholders. Activated modules register MVC entrypoints/advice using the
existing inbound component mechanism; entrypoints are not exported callable types.
The host application must supply its template engine and template resources.

## Requests and usecase arguments

An endpoint declares GET or POST, path/query/header/form scalar bindings, an
`execute dependency { argument <- group.field; }` block, then a view or redirect.
The same exact scalar/VO conversions and named mappings as JSON inbound apply.
There is no implicit normalization, conversion by name, repository access, or SQL.

- GET MUST NOT declare form bindings. Form bindings use URL-encoded POST requests.
- Binding types MUST be supported wire scalars; optional values require boxed types.
- A path placeholder MUST have exactly one matching non-optional path binding.
- Query and form bindings MAY specify `default "literal"`. The default applies only when
  absent, never to an explicitly submitted empty value. Defaults are parsed using
  the same wire format as supplied values; invalid default literals are compile errors.
  Defaults require non-optional query/form bindings.
- Missing required and duplicate scalar values MUST be rejected as invalid requests;
  values MUST NOT be selected arbitrarily from multiple candidates.
- `sensitive` before the binding (for example `sensitive form String password;`) excludes a binding from all rendered input models. Headers are always
  excluded. CSRF/reserved transport fields MUST NOT be declared as application inputs.
- Only declared form/query/path inputs may be exposed to templates. No arbitrary
  request-parameter, header, session or cookie map is copied into a view.

Form names and mapping paths are explicit. An absent optional field and an empty
String are distinct. Optional domain values remain governed by ordinary Vernac
contracts. File uploads, repeated collection fields, multipart bodies and arbitrary
Java request objects are outside this first contract.

## Successful responses

`render "board" model tasks;` passes a non-optional usecase result as attribute
`tasks`, status 200 by default. The result is the complete returned value, implicit
Result object or completed page; it is not flattened into untyped attributes.
`render "thanks";` is allowed for a void result. A returned value MUST have an
explicit model name; a void result MUST NOT claim one. A render status MAY be an
explicit 2xx other than 204/205. `inputs`, `error` and `notice` are reserved model names.

An optional result requires `on absent render "missing" status 404;` (404 or 410).
It is unwrapped only at this top-level boundary. Nested Optional values keep their
ordinary Java API. A successful `redirect "/";` discards any returned value
explicitly. It uses 303 after POST and 302 after GET. Redirect targets MUST be static,
application-local paths; external/protocol-relative targets and model propagation
into URLs are forbidden. Optional `message "Saved."` supplies a static flash notice.
Dynamic redirects are deferred rather than built from guessed string substitutions.

Templates are logical slash-separated names, not expressions, absolute files,
redirect/forward instructions or traversal paths. The template engine resolves
them; the compiler does not assume a template root or inspect template expressions.

GET endpoints and redisplay loaders MUST be designed for reading. The compiler
cannot prove arbitrary Java usecase bodies free of side effects. A committed
usecase is not rolled back if subsequent view rendering fails.

## Error policies and redisplay

Endpoint rules precede adapter rules, followed by safe defaults. Rules use existing
selectors: invalid request/cursor, validation, not found, optimistic conflict,
domain rejection, typed rejection code, and integration responsibility/category.
Codes remain typed enum constants where the existing HTTP contract requires them.
Only the public code/message selected by a rule or safe defaults are exposed.
Exception messages, causes, stack traces and infrastructure names are not exposed
implicitly. `errors implemented by` retains the HTTP static Reply contract and uses
the adapter's fallback error view.

`then render "conflict" status 409 "CODE" "Public message";` selects a dedicated
error page. `then respond 409 ...;` uses the fallback error view with that status;
it never switches an MVC endpoint to JSON. Statuses MUST be 4xx/5xx.

`then redisplay board status 422 "INVALID_TITLE" "Check the task title.";` reloads
the named GET endpoint's view model and then renders its view with the chosen error
status. This is a page-loader reference, not a template name. It MUST target a GET
endpoint with a direct, non-optional render result in the same adapter. Every
required binding needed by the loader MUST be available on the failed endpoint
under the same kind/external name as a required binding of the same wire type,
or have a loader default. No implicit usecase input is
invented. Redisplay is endpoint-specific and MUST NOT be declared as an adapter-wide
rule. It cannot recursively redisplay or rerun the failed POST usecase.

A loader uses its declared inputs/defaults; original allowed form values are then
restored into the public `inputs` model. Sensitive/header values are not restored.
The original POST's HTTP status is replaced with the rule's error status. If the
loader fails, the operation MUST end at the safe fallback error view with status
500; no recursive policy or optimistic retry is attempted. The original transaction
has already ended at the failed usecase boundary. Redisplay is a new usecase call;
it does not undo prior commits or external effects.

Safe defaults follow the JSON boundary: malformed/missing input 400, domain
validation/rejection 422, not found 404, optimistic conflict 409 and unknown
technical failure 500. Technical integration status customization uses the same
explicit policies, for example remote TIMEOUT → 503. Technical failures are logged;
the boundary itself MUST NOT log input maps. Custom exceptions/logging remain the
application author's responsibility.

Every error view receives `error` (safe code/message) and `inputs` (allowed raw
strings). This first version supports a form-wide public message; it MUST NOT
pretend to know which form field caused an arbitrary domain exception. Structured
field errors may be added later. Escaping is the template author's responsibility;
the supplied Thymeleaf starter uses escaped text/values, never `th:utext` for input.

Routing/content negotiation failures and security filters may reject a request
before the controller is selected. They keep the framework's HTTP behavior; MVC
policies are not a substitute for authentication, authorization or CSRF handling.

## Tooling and verification

The LSP MUST provide semantic diagnostics, dependency/argument/binding completion,
and navigation for redisplay endpoint references. Native type navigation and Java
mapping injection remain available. Shared keyword highlighting MUST recognize MVC
syntax. Source maps for copied Java mappings keep their existing contract; native
view-template editing/debugging remains the host framework's responsibility.

Tests MUST cover actual Spring MVC/Thymeleaf rendering, input preservation,
sensitive/undeclared data exclusion, malformed/duplicate values, optional absence,
local redirects, precedence, loader failures, technical errors and CSRF integration,
alongside compiler diagnostics and existing JSON inbound regressions.
