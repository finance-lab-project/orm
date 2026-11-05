package org.financelab.orm;

import java.util.Objects;

public record Table(Identifier schema, Identifier name) {
    public Table { Objects.requireNonNull(schema); Objects.requireNonNull(name); }
    String quoted() { return schema.quoted() + "." + name.quoted(); }
}
