package org.financelab.orm;

import java.util.*;

/** Explicit, read-only verification against migrated PostgreSQL catalogs; no scanning or DDL. */
public final class MappingSchema {
    private MappingSchema() {}

    public static void verify(ReadSession session, List<?> mappings) {
        Objects.requireNonNull(session);
        Map<Table, Definition> definitions = new LinkedHashMap<>();
        for (Object mapping : List.copyOf(mappings)) register(definitions, mapping);
        for (var entry : definitions.entrySet()) verify(session, entry.getKey(), entry.getValue());
    }

    private record Type(String postgres, int jdbc) {
        Type(Column<?> column) { this(column.codec().postgresType(), column.codec().jdbcNullType()); }
    }
    private record Reference(String columns, String schema, String table, String targetColumns) {
        Reference(Column<?> column, Table target, Column<?> targetColumn) {
            this(column.name().value(), target.schema().value(), target.name().value(), targetColumn.name().value());
        }
    }
    private record Definition(Map<String, Type> columns, List<String> primaryKey, Set<Reference> references) {}

    private static void define(Map<Table, Definition> definitions, Table table, List<Column<?>> columns,
                               List<Column<?>> primaryKey, Set<Reference> references) {
        Map<String, Type> types = new LinkedHashMap<>();
        for (Column<?> column : columns) types.put(column.name().value(), new Type(column));
        Definition definition = new Definition(Map.copyOf(types), primaryKey.stream().map(c -> c.name().value()).toList(), references);
        Definition previous = definitions.putIfAbsent(table, definition);
        if (previous != null && !previous.equals(definition))
            throw new IllegalArgumentException("Conflicting mapping definitions for " + table.quoted());
    }
    private static void register(Map<Table, Definition> definitions, Object mapping) {
        switch (mapping) {
            case Anchor<?, ?> a -> define(definitions, a.table(), List.of(a.id()), List.of(a.id()), Set.of());
            case Knot<?> k -> define(definitions, k.table(), List.of(k.code()), List.of(k.code()), Set.of());
            case Scalar<?, ?, ?, ?> s -> attribute(definitions, s.anchor(), s.table(), s.id(), s.value(), s.time(), Set.of());
            case Knotted<?, ?, ?, ?> k -> {
                register(definitions, k.knot());
                attribute(definitions, k.anchor(), k.table(), k.id(), k.value(), k.time(),
                        Set.of(new Reference(k.value(), k.knot().table(), k.knot().code())));
            }
            case StaticTie<?, ?, ?, ?> t -> {
                register(definitions, t.source()); register(definitions, t.target());
                define(definitions, t.table(), List.of(t.sourceId(), t.targetId()), List.of(t.sourceId()),
                        Set.of(new Reference(t.sourceId(), t.source().table(), t.source().id()),
                                new Reference(t.targetId(), t.target().table(), t.target().id())));
            }
            default -> throw new IllegalArgumentException("Expected an explicit Anchor, Knot, Scalar, Knotted or StaticTie");
        }
    }
    private static void attribute(Map<Table, Definition> definitions, Anchor<?, ?> anchor, Table table,
                                  Column<?> id, Column<?> value, TimeShape time, Set<Reference> valueReferences) {
        register(definitions, anchor);
        Set<Reference> references = new HashSet<>(valueReferences);
        references.add(new Reference(id, anchor.table(), anchor.id()));
        if (time instanceof Recorded recorded)
            define(definitions, table, List.of(id, recorded.changedAt(), value), List.of(id, recorded.changedAt()), Set.copyOf(references));
        else define(definitions, table, List.of(id, value), List.of(id), Set.copyOf(references));
    }

    private static final Sql COLUMNS = new Sql("""
            SELECT a.attname::text, pg_catalog.format_type(a.atttypid, a.atttypmod)::text,
                   a.attnotnull::text, a.attgenerated::text, a.attidentity::text
            FROM pg_catalog.pg_attribute a
            JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname::text = ? AND c.relname::text = ? AND c.relkind = 'r'
              AND a.attnum > 0 AND NOT a.attisdropped
            """);
    private static final Sql KEYS = new Sql("""
            SELECT con.contype::text, string_agg(a.attname::text, ',' ORDER BY k.ordinality),
                   rn.nspname::text, rc.relname::text,
                   string_agg(ra.attname::text, ',' ORDER BY k.ordinality)
            FROM pg_catalog.pg_constraint con
            JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY k(attnum, ordinality)
            JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
            LEFT JOIN pg_catalog.pg_class rc ON rc.oid = con.confrelid
            LEFT JOIN pg_catalog.pg_namespace rn ON rn.oid = rc.relnamespace
            LEFT JOIN pg_catalog.pg_attribute ra ON ra.attrelid = rc.oid AND ra.attnum = con.confkey[k.ordinality::integer]
            WHERE n.nspname::text = ? AND c.relname::text = ? AND con.contype IN ('p', 'f') AND con.convalidated
            GROUP BY con.oid, rn.nspname, rc.relname
            """);
    private record ActualColumn(String name, String type, boolean notNull, boolean generated) {}
    private record Key(String kind, String columns, String schema, String table, String targetColumns) {}
    private static String text(Row row, int index) { return row.get(index, Codecs.TEXT).orElseThrow(); }

    private static void verify(ReadSession session, Table table, Definition expected) {
        List<Param<?>> params = List.of(Param.of(Codecs.TEXT, table.schema().value()), Param.of(Codecs.TEXT, table.name().value()));
        Map<String, ActualColumn> columns = new HashMap<>();
        for (ActualColumn column : session.query(COLUMNS, params, r -> new ActualColumn(text(r, 1), text(r, 2),
                text(r, 3).equals("true"), !text(r, 4).isEmpty() || !text(r, 5).isEmpty()))) columns.put(column.name(), column);
        for (var column : expected.columns().entrySet()) {
            ActualColumn actual = columns.get(column.getKey());
            String expectedType = column.getValue().postgres().equals("timestamptz(6)")
                    ? "timestamp(6) with time zone" : column.getValue().postgres();
            if (actual == null || !actual.type().equals(expectedType) || !actual.notNull() || actual.generated())
                throw new IllegalStateException("Schema column mismatch: " + table.quoted() + "." + column.getKey());
        }
        List<Key> keys = session.query(KEYS, params, r -> new Key(text(r, 1), text(r, 2),
                r.get(3, Codecs.TEXT).orElse(""), r.get(4, Codecs.TEXT).orElse(""), r.get(5, Codecs.TEXT).orElse("")));
        if (keys.stream().noneMatch(k -> k.kind().equals("p") && k.columns().equals(String.join(",", expected.primaryKey()))))
            throw new IllegalStateException("Schema primary key mismatch: " + table.quoted());
        for (Reference reference : expected.references()) {
            if (keys.stream().noneMatch(k -> k.kind().equals("f") && new Reference(k.columns(), k.schema(), k.table(), k.targetColumns()).equals(reference)))
                throw new IllegalStateException("Schema foreign key mismatch: " + table.quoted() + "." + reference.columns());
        }
    }
}
