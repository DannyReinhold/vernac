# Shared Java implementation contract

Status: normative, implemented in 0.5.0 development. This contract applies to
custom behaviors of values/enums/collections/entities/aggregates, domain service
and outbound adapter operations, usecase/listener execution and their private
Java helpers. It supersedes older context-specific Optional-parameter rules.
Read/modify/public classifications and transaction/validation timing are unchanged.

## Parameters and results

A declared `T?` parameter MUST become `@Nullable T` at every generated operation,
implementation companion, external implementation and private helper boundary.
There is no automatic conversion of an argument to Optional before delegation.
Required parameters retain T; optional primitives remain forbidden.

```vernac
value Title(String);
service Titles behavior {
    public Title? choose(Title? title) {
        return fallback(title);
    }
    private Title? fallback(Title? title) {
        return Optional.ofNullable(title);
    }
}
```

Both Java parameters are nullable Title. Both Java results are non-null
Optional<Title>. Inline authors MAY use ordinary null checks or explicitly build
an Optional locally when its operations fit the implementation. Returning a raw
nullable value from an optional-result method is not supported.

Optional field getters and validation-input getters MUST continue to return
Optional<T>. Required public domain/operation inputs MUST be checked before
validation or delegation using DomainChecks and MUST reject null with
DomainValidationException. Invalid required or Optional results MUST continue to
raise BehaviorContractException. Private helper inputs use JSpecify contracts;
helpers do not gain additional generated entry validation or transactions.

Framework dependency constructors, transport runtime APIs and technical mapping
contracts keep their existing technical checks; this rule does not reclassify
injection or mapping defects as domain validation failures. Result/DTO records
retain Optional components and their checked canonical constructor containers;
their of factories retain nullable inputs. Stored fields and all factory contracts
are unchanged. No Optional overload or omission overload is added automatically.

## External static implementations

An `implemented by` target MUST resolve using this ordered, shared rule:

1. A qualified Java name is used exactly as written.
2. A simple name matches an explicit import in its implementation's lexical
   `java imports` block, if that block exists.
3. Otherwise it names a class in the generated caller/companion's Java package.

This is not a Vernac type lookup, a classpath search or a search across packages.
Conflicting Java imports MUST be diagnosed; no arbitrary import wins. Existing
import shadowing and architectural checks MUST remain effective for resolved
short targets, including prohibited known repository/adapter/usecase targets.

```vernac
value Title(String) behavior {
    java imports { example.handwritten.TitleFormatting; }
    public String? decorate(String? suffix) implemented by TitleFormatting;
};
```

The handwritten method is public static, returns Optional<String>, and takes
Title self followed by @Nullable String suffix. The class itself is not injected
or instantiated by implemented by. Existing receiver contracts remain unchanged.
An unresolvable class or incompatible Java signature is diagnosed by Java
compilation; Vernac does not guess an implementation or generate a handwritten file.

| Context | Package used when no matching Java import exists |
| --- | --- |
| VO/enum/collection/entity/aggregate/service | `<namespace>.domain` |
| Usecase | `<namespace>.usecase` |
| Listener | `<namespace>.adapter.inbound.event` |
| Outbound behavior and REST error handler | `<namespace>.adapter.outbound` |
| Outbound mapping | `<namespace>.adapter.outbound.mapping` |
| Inbound mapping | `<namespace>.adapter.inbound.http.mapping` |
| Inbound HTTP/MVC error handler | `<namespace>.adapter.inbound.http` |

Imports are scoped to existing blocks; this contract does not add adapter-wide
Java import syntax or make one mapping inherit another implementation's imports.
Where the grammar has no applicable import block, a simple name uses the package
fallback. Existing mapping and error-handler calling conventions are unchanged.
Private helpers remain inline; external targets do not receive privileged field access.

## Java signatures and diagnostics

Overload identity MUST use erased Java parameter types in declaration order.
Nullable and required T have the same signature. A different return type does
not distinguish overloads. Different nullable types remain distinct because the
parameters are T, not Optional<T>. Implementation collision checks MUST include
any generated self receiver and MUST distinguish owner methods from companions.
Generated API collisions remain forbidden under their existing rules.

A collision MUST be reported as a Vernac diagnostic before Java generation with
the affected signature and a reason or useful instruction. No numbering or
implicit method renaming is introduced. Source/model names remain unchanged.

## Tooling and upgrade

Injected Java parameter contexts MUST mirror nullable generated parameters;
getter/result contexts MUST retain Optional. Native Java class navigation for
short implementation targets MUST use the same import/package resolution.
Builds regenerate companions and preserve existing source-map/debugger boundaries.
Applications upgrading from the old service/usecase/adapter convention must adapt
inline/external optional arguments, for example `return Optional.ofNullable(title)`
instead of `return title`, or pass `title` instead of `title.orElse(null)` when the
variable itself is a nullable input. Do not remove unwrapping from actual Optional
getters or results. When regenerating a module producer, update and rebuild its handwritten
implementations together with the generated code. Consuming a compiled dependency
does not rewrite its internal implementation signatures.

No database mapping, migration generation, Spring transaction propagation, event
ordering or read/modify effects are changed by this contract.
