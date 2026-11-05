package org.financelab.orm;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CodecsTest {
    private final List<String> calls = new ArrayList<>();
    private final List<Object> values = new ArrayList<>();
    private final PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, args) -> {
                calls.add(m.getName()); values.add(args[1]); return null;
            });

    @Test void exactBindingUsesOnlyTypedJdbcMethods() throws Exception {
        UUID id = UUID.randomUUID();
        Codecs.UUID.bind(statement, 1, Optional.of(id));
        Codecs.TEXT.bind(statement, 2, Optional.of("  Київ ' ; -- 😀  "));
        Codecs.INTEGER.bind(statement, 3, Optional.of(Integer.MIN_VALUE));
        Codecs.BIGINT.bind(statement, 4, Optional.of(Long.MAX_VALUE));
        Codecs.DATE.bind(statement, 5, Optional.of(LocalDate.of(2026, 11, 1)));
        Codecs.INSTANT.bind(statement, 6, Optional.of(Instant.parse("2026-11-05T01:02:03.123456Z")));
        Codecs.decimal(19, 2).bind(statement, 7, Optional.of(new BigDecimal("1.2")));
        Codecs.INTEGER.bind(statement, 8, Optional.empty());
        assertEquals(List.of("setObject", "setString", "setInt", "setLong", "setObject", "setObject", "setBigDecimal", "setNull"), calls);
        assertEquals(id, values.getFirst());
        assertEquals(ZoneOffset.UTC, ((OffsetDateTime) values.get(5)).getOffset());
        assertEquals(new BigDecimal("1.20"), values.get(6));
        assertEquals(Types.INTEGER, values.get(7));
    }

    @Test void invalidValuesFailBeforeBinding() {
        Codec<BigDecimal> decimal = Codecs.decimal(19, 2);
        for (String v : List.of("1.001", "1.000", "100000000000000000.00", "1E+2147483647"))
            assertThrows(IllegalArgumentException.class, () -> decimal.bind(statement, 1, Optional.of(new BigDecimal(v))));
        for (Instant v : List.of(Instant.parse("2026-11-05T01:02:03.123456001Z"), Instant.MAX, Instant.MIN))
            assertThrows(IllegalArgumentException.class, () -> Codecs.INSTANT.bind(statement, 1, Optional.of(v)));
        for (LocalDate v : List.of(LocalDate.MIN, LocalDate.MAX, LocalDate.of(-4713, 11, 24)))
            assertThrows(IllegalArgumentException.class, () -> Codecs.DATE.bind(statement, 1, Optional.of(v)));
        for (String v : List.of("a\0b", "a\ud800", "\udc00"))
            assertThrows(IllegalArgumentException.class, () -> Codecs.TEXT.bind(statement, 1, Optional.of(v)));
        assertTrue(calls.isEmpty());
        assertThrows(NullPointerException.class, () -> Param.of(Codecs.TEXT, null));
        assertThrows(IllegalArgumentException.class, () -> Codecs.decimal(0, 0));
        assertThrows(IllegalArgumentException.class, () -> Codecs.decimal(2, 3));
    }

    @Test void reviewedSqlHasOneDmlStatement() {
        for (String sql : List.of("COMMIT", "ROLLBACK", "SET TIME ZONE 'UTC'", "CREATE TABLE x(i int)",
                "SELECT 1; DELETE FROM budget.category", "/* note */ SELECT 1", "SELECT $$x$$"))
            assertThrows(IllegalArgumentException.class, () -> new Sql(sql));
        assertDoesNotThrow(() -> new Sql("-- review\n SELECT ';--''x'::text, ?::uuid;"));
        assertDoesNotThrow(() -> new Sql("WITH x AS (SELECT 1) SELECT * FROM x"));
    }
}
