package org.financelab.orm;

import java.util.Objects;

/** A trusted PostgreSQL identifier token, never request data or an expression. */
public record Identifier(String value) {
    public Identifier {
        Objects.requireNonNull(value);
        if (value.length() > 63 || !value.matches("[a-z_][a-z0-9_]*"))
            throw new IllegalArgumentException("Invalid PostgreSQL identifier token");
    }
    String quoted() { return "\"" + value + "\""; }
}
