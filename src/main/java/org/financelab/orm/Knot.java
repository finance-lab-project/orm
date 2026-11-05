package org.financelab.orm;

import java.util.Objects;

public record Knot<C>(Table table, Column<C> code) {
    public Knot { Objects.requireNonNull(table); Objects.requireNonNull(code); }
}
