package org.financelab.orm;

import java.util.List;

/** The transaction ended successfully; cleanup diagnostics must never trigger a write retry. */
public record Completed<R>(R value, List<Throwable> cleanupFailures) {
    public Completed { cleanupFailures = List.copyOf(cleanupFailures); }
}
