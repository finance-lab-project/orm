package org.financelab.orm;

import java.util.Objects;

/** A is a persistence-local marker distinguishing anchors with the same ID type. */
public record Anchor<A, I>(Table table, Column<I> id) {
    public Anchor { Objects.requireNonNull(table); Objects.requireNonNull(id); }
}
