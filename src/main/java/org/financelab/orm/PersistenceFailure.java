package org.financelab.orm;

import java.sql.SQLException;
import java.util.Optional;

/** Original cause (including chained SQLExceptions) is retained without logging SQL or values. */
public final class PersistenceFailure extends RuntimeException {
    private final WriteOutcome outcome;
    PersistenceFailure(WriteOutcome outcome, Throwable cause) {
        super("Persistence scope failed: " + outcome, cause);
        this.outcome = outcome;
    }
    public WriteOutcome outcome() { return outcome; }
    public Optional<String> sqlState() {
        for (Throwable t = getCause(); t != null; t = t.getCause())
            if (t instanceof SQLException sql && sql.getSQLState() != null) return Optional.of(sql.getSQLState());
        return Optional.empty();
    }
}
