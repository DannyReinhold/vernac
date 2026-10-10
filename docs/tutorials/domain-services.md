# Plan a stop transfer with a domain service

This exercise uses `examples/persistence-demo`. Start with its unchanged schema;
adding a service or usecase does not change persistence mapping.

See [the service contract](../contracts/domain-services.md) for the complete API
and [usecases](usecases.md) for transaction boundaries.

The included `tour-planning.vernac` provides TourPlanning.moveStop and the usecase
MoveStopToTour. TourPlanning receives two loaded aggregates, removes the stop from
all source relationships using Tour.removeStop, and adds it to the target tour.
The Stop instance and its StopId are preserved. This example defines a stop as a
movable unit, rather than an assignment that must receive a new identity.

Inject `org.example.delivery.usecase.MoveStopToTour` into an application component
and call `execute(sourceTourId, targetTourId, stopId)`. The usecase loads both tours,
calls the service and saves both in its REQUIRED transaction. Call through the
Spring bean, not a manually constructed instance. Source and target must be
existing different tours, and the stop must occur in the source.

Try these variations:

1. Pass the same tour twice: the service precondition rejects the operation before
   changing either aggregate.
2. Transfer a stop that occurs repeatedly in allStops and also in niceStops or
   preferredStop. The source's existing removeStop behavior clears all those links.
3. Add a target capacity rule to the domain model. Let the target aggregate enforce
   it; the service should not replace the aggregate's own invariants.
4. Move the Java body to an external implementation using implemented by.

For step 4, replace the method body (keeping its validates block) with:

```vernac
implemented by org.example.delivery.application.TourPlanningImplementation;
```

Provide a hand-written Java class:

```java
package org.example.delivery.application;

import org.example.delivery.domain.StopId;
import org.example.delivery.domain.Tour;
import org.example.delivery.domain.access.TourPlanningAccess;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class TourPlanningImplementation {
    private TourPlanningImplementation() {}

    public static void moveStop(TourPlanningAccess self,
                                Tour source, Tour target, StopId stopId) {
        var stop = source.allStops().by(stopId).orElseThrow();
        source.removeStop(stopId);
        target.addStop(stop);
    }
}
```

No Spring annotation is necessary on this implementation class. The generated bean
continues to validate and delegate. self is available even when this particular
method needs no dependencies or other service operations.

The example deliberately has no target duplicates policy or scheduling rules.
Decide those rules for your domain before treating it as a real dispatch planner.
If a later operation fails, Java object state is not restored; the surrounding
transaction can roll back database writes. Do not mistake an in-memory transfer
for an independently committed database operation.
