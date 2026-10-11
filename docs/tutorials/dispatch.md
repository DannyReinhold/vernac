# Run the architecture: Vernac Dispatch

A delivery board looks simple until two plans change together, a vehicle is full,
or a carrier is unavailable. Dispatch makes those boundaries visible.

Open the [Dispatch application guide](https://github.com/DannyReinhold/vernac/tree/feature/redefining_the_language/examples/dispatch).
It contains exact startup commands, source links and a five-minute walkthrough.
You need the current 0.2.0-SNAPSHOT artifacts, JDK 25, Maven and Docker.

## Follow one decision through the system

Start with **moving Studio Nord from North to South**:

1. The browser sends source, target and stop IDs to the handwritten web adapter.
2. The adapter constructs domain IDs and calls the generated `MoveStop` usecase.
3. The usecase loads both aggregates inside one transaction.
4. `TourPlanning` applies the cross-aggregate decision. Each aggregate protects its
   own state and capacity. The target appends the existing entity to its list.
5. The generated repositories persist both aggregates, including list positions.
6. The browser reloads a projection of committed state.

Now try moving eight crates to a tour with only five free. The domain rejects the
request. Look at the precondition checks and at the transaction boundary: both have
jobs to do, and neither belongs in the JavaScript click handler.

## Follow a fact after commit

Release South, arm the simulated outage and dispatch it. The generated usecase saves
the aggregate and emits `TourDispatched`. The event and its delivery job commit with
the tour. A deferred listener calls the carrier through a domain port and generated
REST/DTO mappings. A temporary failure causes a later attempt. A successful attempt
writes an application-owned receipt and acknowledges delivery transactionally.

The carrier is local and simulated. The demo obtains a quote; it makes no real
booking. At-least-once delivery is visible, and no exactly-once external guarantee is
claimed. A real booking operation would require an explicit idempotency contract.

## Read, then change

Read the model files in this order: values, tours, planning, dispatch, carrier.
Then inspect the generated Java. The web controller contains conversion and
presentation work; the model contains business decisions.

Try adding another customer, choosing a smaller capacity, or naming a tour with
Unicode text. Restart to confirm that the plan is persisted. Finally, add a value
and follow the [migration tutorial](postgresql-migrations.md) to evolve storage.

Continue with [events](domain-events.md), [outbound ports](outbound-ports.md) or the
[complete specification](../specification/index.md) when you want to understand a
particular boundary more deeply.

## Change configuration without changing the model

Open `CarrierHttp` in `carrier.vernac`. Its `configuration` block binds `baseUri`
to `dispatch.carrier.base-url`. `application.yaml` supplies the environment-specific
value; no URI factory bean is necessary. To move the local carrier simulator,
set `DISPATCH_CARRIER_SIMULATOR_PORT` before starting Dispatch. The default URL
follows the selected port. An explicit `CARRIER_BASE_URL` overrides it.
Keep the simulator as the target while exploring the deliberate outage button.

This mechanism also supports technical strings, durations, paths, integers and
booleans for non-HTTP adapters. Such properties are available through `self` in
both inline and external behavior. They never become part of the domain port.
