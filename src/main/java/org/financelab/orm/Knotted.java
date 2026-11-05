package org.financelab.orm;

import java.util.Objects;

public record Knotted<A, I, C, T extends TimeShape>(
        Anchor<A, I> anchor, Table table, Column<I> id, Column<C> value, Knot<C> knot, T time) {
    public Knotted {
        Objects.requireNonNull(anchor); Objects.requireNonNull(table); Objects.requireNonNull(id);
        Objects.requireNonNull(value); Objects.requireNonNull(knot); Objects.requireNonNull(time);
        MappingChecks.compatible(anchor.id().codec(), id.codec());
        MappingChecks.compatible(value.codec(), knot.code().codec());
        MappingChecks.roles(id, value, time);
    }
    Scalar<A, I, C, T> scalar() { return new Scalar<>(anchor, table, id, value, time); }
}
