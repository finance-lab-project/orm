package org.financelab.orm;

import java.util.Objects;

/** A functional immutable tie: at most one target for each source identity. */
public record StaticTie<A, I, B, J>(Anchor<A, I> source, Anchor<B, J> target,
        Table table, Column<I> sourceId, Column<J> targetId) {
    public StaticTie {
        Objects.requireNonNull(source); Objects.requireNonNull(target); Objects.requireNonNull(table);
        Objects.requireNonNull(sourceId); Objects.requireNonNull(targetId);
        MappingChecks.compatible(source.id().codec(), sourceId.codec());
        MappingChecks.compatible(target.id().codec(), targetId.codec());
        MappingChecks.distinct(sourceId, targetId);
    }
}
