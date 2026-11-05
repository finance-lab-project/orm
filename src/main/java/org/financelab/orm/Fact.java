package org.financelab.orm;

import java.time.Instant;
import java.util.Objects;

public record Fact<V>(Instant changedAt, V value) {
    public Fact { Objects.requireNonNull(changedAt); Objects.requireNonNull(value); }
}
