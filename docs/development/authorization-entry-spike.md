---
title: Authorization entry and Spring integration feasibility
---
# Authorization entry and Spring integration feasibility

Status: **handwritten entrypoint evidence using the production authorization runtime**.
The [runtime foundation](../contracts/usecase-authorization-runtime.md) now replaces the
original parallel guard and classifier; these entrypoints/policies remain test fixtures.
Baseline: 2026-11-12, Spring Boot 3.4.2 dependency management, Java 21 application target.
This follows the [usecase authorization contract](usecase-authorization-contract.md).
It does not add permission declarations, `requires` syntax, generated guards, SaaS
identity or the reachable-usecase startup audit. The separate runtime contract defines
the implemented Java APIs and provider-binding startup checks. A03/A04/A19/A20 remain OPEN.

## Question and implementation

Can Vernac keep its existing Spring REQUIRED entrypoint, check a pure permission tree
inside the public method, read scoped facts through a MANDATORY policy, and consistently
distinguish authentication, authorization and technical failures at HTTP/MVC boundaries?

The isolated [experiment](https://github.com/DannyReinhold/vernac/tree/feature/0.6.0/experiments/tenant-isolation-spike)
now contains three handwritten, test-only fixtures:

- `AuthorizationEntryProbe`: an adapter to the production body guard and Spring actor resolver, typed fixture
  carriers, a Spring AuthorizationManager policy and entrypoints shaped like
  the intended generated Java. There is no second permission interceptor.
- `AuthorizationEntryPostgresTest`: real proxied REQUIRED/MANDATORY calls and scoped
  PostgreSQL reads/writes using the existing restricted runtime role and coordinator.
- `AuthorizationWebProbe`: application-owned SecurityFilterChain, MockMvc dispatch,
  API error bodies and MVC error views using the production terminal classifier.

They live only in `src/test/java` of the standalone experiment. They are not a
production repository SPI, generated entrypoint or complete security starter. Test actors,
permissions and resource records are fixture types; enum constants are not the final
qualified permission identity representation. The fixture uses a fixed, preverified
tenant selection, never a request header. It does not implement membership resolution.

## Proven boundaries

| Test | Evidence |
| --- | --- |
| `guardOrdersNullPermissionValidationBodyAndResult` | Required-null checks raise DomainValidationException; Deny stops before validates/body; Allow reaches validates, body and result in order and commits. |
| `nestedChecksRunOnlyWhenReachedAndRequiredFailureRollsBackAllWrites` | A public outer operation does not preauthorize unreachable inner calls; an actual inner call rechecks; propagated or caught Deny rolls back outer writes and versions. |
| `allAnyShortCircuitAndTechnicalFailureNeverBecomesAnotherAlternative` | Declaration order and short-circuit are preserved; unavailable, unexpected and abstaining policy outcomes fail technically; an unavailable branch cannot fall through to another grant. |
| `absentForeignAndWrongNominalResourcesDenyWithoutDisclosingExistence` | Equal UUIDs with another nominal ID type do not match; hidden/missing facts produce the same safe denial; missing scope fails before policy/body. |
| `directEntryMissingInfrastructureAndAnonymousNeverReachPolicyOrBody` | Manual entry without a real transaction and missing policy fail technically; missing/anonymous authentication is not a verified actor; public execution remains possible. |
| `nestedActorOrTenantReplacementFailsAndTechnicalActorsAreExplicit` | Nested calls cannot replace the captured actor or tenant; an explicitly mapped technical actor can satisfy authenticated without inheriting a human identity. |
| `reusedExecutorClearsInvocationTenantAndAuthenticationAfterFailure` | Sequential jobs on the same executor thread retain no authorization, authentication, tenant selection or transaction pin after failure. |
| `restAndMvcUseTerminalSafeFailuresBeforeGenericErrorHooks` | Deny gives 403; unavailable infrastructure gives 503; an unexpected backend exception gives safe 500 without its seeded secret. MVC uses a terminal error view; generic hooks and business writes do not run. |
| `applicationFilterChainKeepsLoginAndCsrfWithoutDuplicatingPermissionChecks` | Anonymous private-route and public-route/protected-usecase requests give 401; missing CSRF stops a POST before policy; valid CSRF with Allow runs the single policy and implementation. |

These nine tests run alongside the existing fourteen isolation and seven event-routing
proofs. A missing PostgreSQL test configuration fails the experiment rather than
skipping tests. CI checks the named cases and rejects failures, errors or skips.
The initial local run passed **30 tests, zero failures/errors/skips**, following a
clean install of the current runtime/JDBC reactor and its unit tests.

## Decisions supported by the experiment

1. Keep a guard in generated public execute methods. Spring supplies REQUIRED, while
   the body guard requires an actual synchronized transaction for protected entry.
   Policies can use MANDATORY repositories without opening an independent transaction.
2. Evaluate reached inner operations independently. Do not cache an outer Allow as
   authorization for another operation/resource or preauthorize all possible calls.
3. Preserve the actor and tenant across an invocation. Restore thread-local frames in
   finally blocks. Trusted ingress owns actor creation and verified tenant selection;
   ordinary Access must not expose replacement APIs.
4. Keep Deny distinct from unavailable or broken infrastructure. Null/abstaining policy
   results are configuration failures, never auto-Allow. An `any` tree cannot hide a
   technical failure by trying a different alternative.
5. Keep URL authentication/CSRF and usecase authorization complementary. The framework
   must not replace the application's filter chain or check its permission formula
   twice. Terminal auth errors must precede generic adapter policies/redisplay hooks.

## Limitations and required production work

The original experiment does not prove the complete AUTH-01–08 contract. The runtime
follow-up now supplies the guard, actor SPI, provider binding and helper classification;
remaining full-integration limits below still apply:

- Grammar, semantic diagnostics, generation, qualified permission identities, explicit
  exports, module metadata/compatibility, IDE/LSP and supported reference examples are
  not added here. Invalid requirement trees must ultimately fail at compile time.
- The actor mapper is a trusted fixture. Real authentication, disabled/expired accounts,
  tenant membership selection, provider binding, technical-worker grants and their integration with the implemented immutable
  invocation lifecycle still require end-to-end acceptance tests.
- The test controller/advice illustrates the required order. It is not generated HTTP
  or MVC code and does not prove generated adapter custom-hook integration. Configured
  browser login redirects, public registration forms, redisplay-loader suppression,
  return-target safety and Spring entrypoint messages need their own integrated tests.
- The broad SaaS startup audit, ambiguous/missing provider rejection at startup and
  reachable imported usecase requirements remain OPEN. The experiment demonstrates
  runtime failure on missing infrastructure; it does not substitute that for startup
  validation.
- The probe catches backend exceptions into safe public codes without retaining raw
  causes. Complete production observability still needs a deliberately safe operational classifier
  and correlation strategy. This is not a completed logging/monitoring implementation.
- No subscription policy, quota reservation/consumption or role precedence is inferred.
  Successful authorization is an invocation-time decision, not instantaneous revocation
  across processes. No thread-local security propagation to asynchronous work is added.
- Neither arbitrary handwritten Java nor manually called static companions are a
  sandboxed security boundary. The [repository extension follow-up](tenant-webhooks-and-repository-extensions.md)
  remains independently required; RLS and result provenance cannot replace authorization.

## Reproduce and next PR

Follow the experiment's [README](https://github.com/DannyReinhold/vernac/blob/feature/0.6.0/experiments/tenant-isolation-spike/README.md)
for its disposable PostgreSQL service and environment variables. After installing the
current runtime artifacts, run:

```shell
mvn -B -ntp -f experiments/tenant-isolation-spike/pom.xml clean verify
```

To run only this suite, add `-Dtest=AuthorizationEntryPostgresTest`. For CI/release
verification run all experiment suites and the named-report gate. Do not use the
application database or migration credentials as the runtime query role.

Next: integrate the implemented production runtime with grammar/generation/module/
tooling and complete the reachable-usecase audit and identity integration.
Keep HTTP/MVC and nested tenant usecase tests as acceptance criteria rather than
assuming the handwritten probe proves generated output. Review A01–A23 against the
[release ledger](release-0.6.0.md); this PR closes no complete SaaS acceptance row.
