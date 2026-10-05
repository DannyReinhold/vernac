// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0

package org.vernac.runtime;

public class AggregateNotFoundException extends RuntimeException {

    private final Class<?> aggregateType;
    private final Object aggregateId;

    public AggregateNotFoundException(Class<?> aggregateType, Object aggregateId) {
        super("Aggregate " + aggregateType.getSimpleName() + " with id [" + aggregateId + "] not found");
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
    }

    public Class<?> aggregateType() {
        return aggregateType;
    }

    public Object aggregateId() {
        return aggregateId;
    }
}