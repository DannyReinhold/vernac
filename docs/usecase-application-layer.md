# Usecases and the application layer

The experimental usecase syntax has been replaced. See the
[usecase contract](contracts/usecases.md) and [usecase tutorial](tutorials/usecases.md).

Each usecase is one Spring-managed operation with typed domain inputs, an explicit
result, constructor-injected dependencies and REQUIRED transaction propagation.
Its behavior contains one execute implementation and optional private helpers.

Domain services are a separate language feature whose review is still pending;
the usecase changes do not redefine that feature.
