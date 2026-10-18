# Vernac

**Model your domain. Make its architecture executable.**

Vernac is an architecture language for Java applications built around Domain-Driven
Design. Describe values, identity, aggregate boundaries, application operations and
external capabilities in one coherent model. The compiler generates Java classes,
Spring transaction boundaries, JDBC repositories and outbound adapters that follow
those decisions.

Business behavior stays Java: write it inside a `behavior` block or delegate to a
handwritten class. Read the generated code, test it with ordinary Java tools, and
use it alongside an existing application. No ORM, reflective domain model or separate
runtime language is required.

## Clean architecture is useful before a project becomes large

Vernac makes Domain-Driven Design approachable for a solo developer, an internal
tool or a small SaaS as well as a larger application. Typed values, explicit
business operations and clear boundaries do not have to come with pages of
handwritten boilerplate. Start with one model and one useful operation; add
persistence, events and integrations when they solve a real problem.

New to DDD? [Learn the ideas with a task board](docs/tutorials/what-is-ddd.md), then
see [how little a small project needs](docs/tutorials/ddd-for-small-projects.md).
Prefer to start with a running product? The
[Spring Boot starter](docs/tutorials/spring-boot-starter.md) includes a browser UI,
PostgreSQL and migrations.

## One model, several architectural decisions

Imagine a delivery system. Completing a tour is a business operation, not just an
UPDATE. It needs a valid domain object, a transaction, persistence and an event:

```vernac
namespace example.dispatch;

id TourId;
value Title(String) validates {
    require(!self.string().isBlank(), "Title must not be blank");
};
value TourStatus = PLANNED | COMPLETED;
event TourCompleted(TourId);

aggregate Tour[TourId](Title, mut TourStatus) behavior {
    modify void complete() {
        self.tourStatus(TourStatus.COMPLETED);
    }
} list Tours;

repository TourRepository for Tour {
    find Tours withStatus(TourStatus status)
        where tourStatus = :status order by title;
}

usecase CompleteTour(TourId) uses TourRepository tourRepository behavior {
    execute {
        var tour = self.tourRepository().byId(tourId);
        tour.complete();
        self.tourRepository().save(tour);
        self.emit(TourCompleted.create(tour.id()));
    }
}
```

This model gives you:

- A validated, immutable Title and a typed TourId.
- A Tour with identity-based equality and explicitly mutable state.
- A declared collection and a type-checked query returning complete aggregates.
- A repository interface in the domain and a JDBC implementation outside it.
- A Spring usecase whose `execute` runs with REQUIRED transaction propagation.
- An immutable event occurrence. Add a synchronous or deferred listener to decide
  how another part of the application responds.

The example keeps transition policy deliberately small: a production application
must decide whether repeated completion is allowed. Events are emitted explicitly;
a field change does not invent an event, and a save does not publish one implicitly.

The same language expresses shared child entities, value collections, schema migration
candidates, domain services, outbound ports, transport DTOs, mappings and structured
integration failures. Each feature has an explicit contract rather than a hidden
framework convention.

## Start with a passing test

This documentation describes the **Vernac 0.5.0 language and API**. Use matching
compiler, runtime and IntelliJ plugin versions; earlier releases do not support all
constructs documented here. For a source checkout, the commands below build and install
the version configured in its root POM (currently `0.5.0-SNAPSHOT`).

Use JDK 25 and Maven 3.9.x (release automation pins 3.9.16). Generated application code targets Java 21.
From this repository, install the compiler and runtime, then try the minimal template:

```shell
mvn -pl vernac-maven-plugin -am clean install
mvn -f templates/vernac-basic/pom.xml clean test
```

Or create a project with the IntelliJ wizard. Its bundled language server provides
semantic diagnostics, completion and navigation; Java integration connects generated
APIs back to their Vernac declarations. Maven performs source generation.

- [Build your first domain model](docs/tutorials/your-first-domain-model.md)
- [See the architecture take shape](docs/tutorials/from-model-to-application.md)
- [Set up IntelliJ](docs/tutorials/intellij-plugin.md)
- [Create a real Spring Boot web application](docs/tutorials/spring-boot-starter.md) (UI, database and migrations)
- [Declare HTML pages and forms in Vernac](docs/tutorials/inbound-mvc.md) (declarative MVC adapters)
- [Choose an executable example](examples/README.md)

## Run the architecture

The [delivery walkthrough](examples/persistence-demo/README.md) exercises complete
aggregate graphs, optimistic locking, queries, Flyway migrations, synchronous and
durable event delivery, and REST/ACL integration. Runners verify expectations and
explain each outcome in the log. The HTTP walkthrough uses a local fixture and needs
no external account; persistence and event tests use PostgreSQL.

```shell
mvn -pl vernac-maven-plugin,vernac-jdbc-spring-boot-starter,vernac-runtime-http -am clean install
mvn -f examples/persistence-demo/pom.xml clean test -Dtest=PortDemoTest
```

## Explore the language

[Documentation website](https://vernac.org) · [Tutorials](docs/tutorials/index.md) ·
[Language reference](docs/language-reference.md) · [Specification and contracts](docs/specification/index.md) ·
[FAQ](docs/faq/index.md)

Vernac supplies architectural structure, not your business decisions. Validation
failures do not roll back Java objects. Database transactions do not undo external
calls. Migration candidates require application-owner review. These boundaries are
part of the documented contracts, not details to discover in production.

## Develop Vernac

The repository contains the compiler, Maven plugin, runtime modules for core/JDBC/JSON/HTTP,
and IntelliJ/LSP tooling. See [contributor documentation](docs/development/index.md)
and the [0.5.0 release plan](docs/development/release-0.5.0.md).
The compiler also generates HTTP inbound adapters around usecases;
see [the tutorial](docs/tutorials/inbound-http.md). MCP and messaging adapters remain
planned. Ordinary Java/Spring entry points continue to work alongside generated ones.

Apache License 2.0. See [LICENSE](LICENSE).

[Legal notice / Impressum](docs/impressum.md) · [Privacy](docs/privacy.md)

## Explore the interactive showcase

[**Vernac Dispatch**](examples/dispatch/README.md) is an interactive delivery-planning
application: move stops, enforce capacity, dispatch tours and observe durable event
delivery through a generated REST adapter. It runs locally with PostgreSQL and a
carrier simulator.


## Reuse architecture across applications

Publish a Vernac capability as a versioned Maven module. Export its public domain
contracts, keep implementation helpers internal, and consume the compiled JAR without
copying or regenerating its definitions. Applications explicitly select providers and
activate usecases, listeners or HTTP entrypoints; required modules activate transitively.

The [module lifecycle tutorial](docs/tutorials/type-modules.md) follows values from
publication through consumption and schema evolution. The
[persistent module application](examples/persistent-modules/README.md) demonstrates
Billing, Receipts, durable events, an HTTP API and independent migration histories.
Migration candidates remain subject to application-owner review and testing.


The [ubiquitous-language tutorial](docs/tutorials/ubiquitous-language.md) shows how
to keep domain meanings beside model definitions. Namespaced terms appear in type hover,
travel with exported module types and produce readable/machine-readable glossaries.

[See the architecture as a diagram](docs/tutorials/domain-diagrams.md): export a namespace
from Maven or open its diagram directly from IntelliJ.


## Debug the business rule, in its source

With the **0.5.0 compiler and plugin**, put a breakpoint inside an inline Vernac
behavior and inspect the live JVM state without first finding its generated Java
implementation. Step through the rule, inspect `self` and locals, evaluate Java
expressions and use conditional breakpoints.

The [debugging walkthrough](docs/tutorials/debugging.md) takes you through a
small runnable example, including private helpers, handwritten Java transitions
and logpoints. It explains the current coverage and limits. Use matching 0.5.0 release artifacts, or matching
0.5.0-SNAPSHOT artifacts when building this source checkout.

## Contributing and reporting problems

See [CONTRIBUTING.md](CONTRIBUTING.md) for contributor and maintainer documentation,
build checks and reporting guidance. Participation follows the
[Code of Conduct](CODE_OF_CONDUCT.md). Report vulnerabilities privately according
to the [security policy](SECURITY.md).
