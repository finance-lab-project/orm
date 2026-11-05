package org.financelab.orm;

import java.time.Instant;
import java.util.Objects;

public record Recorded(Column<Instant> changedAt) implements TimeShape {
    public Recorded {
        Objects.requireNonNull(changedAt);
        MappingChecks.compatible(changedAt.codec(), Codecs.INSTANT);
    }
}
