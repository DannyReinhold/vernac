# Domain events and delivery

This page is a navigation entry for existing links. Current 0.4.0 documentation:

[Event tutorial](tutorials/domain-events.md) explains publication and listeners.
The [event contract](contracts/events.md) defines identity, relational recording,
per-listener jobs, retries and worker coordination. Publication uses self.emit;
there is no memory/outbox dispatch modifier on event types.
