package org.financelab.orm;

import java.util.List;
import java.util.stream.Collectors;

/** Pure SQL generation. Every data value is a JDBC parameter. */
final class AnchorSql {
    private AnchorSql() {}
    static Sql insert(Table table, Column<?>... columns) {
        String names = List.of(columns).stream().map(c -> c.name().quoted()).collect(Collectors.joining(", "));
        String slots = List.of(columns).stream().map(c -> "?").collect(Collectors.joining(", "));
        return new Sql("INSERT INTO " + table.quoted() + " (" + names + ") VALUES (" + slots + ")");
    }
    static Sql lookup(Table table, Column<?> id, Column<?> value) {
        return new Sql("SELECT " + value.name().quoted() + " FROM " + table.quoted()
                + " WHERE " + id.name().quoted() + " = ?");
    }
    static Sql facts(Table table, Column<?> id, Column<?> value, Recorded time, boolean asOf, boolean history) {
        String at = time.changedAt().name().quoted();
        return new Sql("SELECT " + at + ", " + value.name().quoted() + " FROM " + table.quoted()
                + " WHERE " + id.name().quoted() + " = ?" + (asOf ? " AND " + at + " <= ?" : "")
                + " ORDER BY " + at + (history ? " ASC" : " DESC LIMIT 1"));
    }
}
