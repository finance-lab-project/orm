package org.financelab.orm;

final class MappingChecks {
    private MappingChecks() {}
    static void compatible(Codec<?> left, Codec<?> right) {
        if (!left.postgresType().equals(right.postgresType()) || left.jdbcNullType() != right.jdbcNullType())
            throw new IllegalArgumentException("Incompatible mapping codecs");
    }
    static void distinct(Column<?> left, Column<?> right) {
        if (left.name().equals(right.name())) throw new IllegalArgumentException("Mapping role columns must differ");
    }
    static void roles(Column<?> id, Column<?> value, TimeShape time) {
        distinct(id, value);
        if (time instanceof Recorded recorded) {
            distinct(id, recorded.changedAt()); distinct(value, recorded.changedAt());
        }
    }
}
