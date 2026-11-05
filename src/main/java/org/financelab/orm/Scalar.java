package org.financelab.orm;

import java.util.Objects;

public record Scalar<A, I, V, T extends TimeShape>(
        Anchor<A, I> anchor, Table table, Column<I> id, Column<V> value, T time) {
    public Scalar {
        Objects.requireNonNull(anchor); Objects.requireNonNull(table);
        Objects.requireNonNull(id); Objects.requireNonNull(value); Objects.requireNonNull(time);
        MappingChecks.compatible(anchor.id().codec(), id.codec());
        MappingChecks.roles(id, value, time);
    }
}
