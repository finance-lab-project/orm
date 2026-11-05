package org.financelab.orm;

import java.util.Objects;

public record Column<V>(Identifier name, Codec<V> codec) {
    public Column { Objects.requireNonNull(name); Objects.requireNonNull(codec); }
}
