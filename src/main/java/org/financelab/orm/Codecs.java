package org.financelab.orm;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class Codecs {
    private Codecs() {}

    public static final Codec<UUID> UUID = new Exact<>("uuid", Types.OTHER) {
        void put(PreparedStatement s, int i, UUID v) throws SQLException { s.setObject(i, v); }
        UUID get(ResultSet r, int i) throws SQLException { return r.getObject(i, UUID.class); }
    };
    public static final Codec<String> TEXT = new Exact<>("text", Types.VARCHAR) {
        String checked(String v) {
            if (v.indexOf('\0') >= 0) throw new IllegalArgumentException("PostgreSQL text cannot contain NUL");
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= v.length() || !Character.isLowSurrogate(v.charAt(i)))
                        throw new IllegalArgumentException("Unpaired UTF-16 surrogate");
                } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Unpaired UTF-16 surrogate");
            }
            return v;
        }
        void put(PreparedStatement s, int i, String v) throws SQLException { s.setString(i, v); }
        String get(ResultSet r, int i) throws SQLException { return r.getString(i); }
    };
    public static final Codec<Integer> INTEGER = new Exact<>("integer", Types.INTEGER) {
        void put(PreparedStatement s, int i, Integer v) throws SQLException { s.setInt(i, v); }
        Integer get(ResultSet r, int i) throws SQLException { int v = r.getInt(i); return r.wasNull() ? null : v; }
    };
    public static final Codec<Long> BIGINT = new Exact<>("bigint", Types.BIGINT) {
        void put(PreparedStatement s, int i, Long v) throws SQLException { s.setLong(i, v); }
        Long get(ResultSet r, int i) throws SQLException { long v = r.getLong(i); return r.wasNull() ? null : v; }
    };

    // Common finite PostgreSQL/pgJDBC range; earlier dates are encoded as infinity by pgJDBC.
    private static final LocalDate MIN_DATE = LocalDate.of(-4712, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(5874897, 12, 31);
    private static final Instant MIN_INSTANT = MIN_DATE.atStartOfDay().toInstant(ZoneOffset.UTC);
    private static final Instant MAX_INSTANT = LocalDate.of(294276, 12, 31)
            .atTime(23, 59, 59, 999999000).toInstant(ZoneOffset.UTC);

    public static final Codec<LocalDate> DATE = new Exact<>("date", Types.DATE) {
        LocalDate checked(LocalDate v) {
            if (v.isBefore(MIN_DATE) || v.isAfter(MAX_DATE)) throw new IllegalArgumentException("Nonfinite/out-of-range date");
            return v;
        }
        void put(PreparedStatement s, int i, LocalDate v) throws SQLException { s.setObject(i, v); }
        LocalDate get(ResultSet r, int i) throws SQLException { return r.getObject(i, LocalDate.class); }
    };
    public static final Codec<Instant> INSTANT = new Exact<>("timestamptz(6)", Types.TIMESTAMP_WITH_TIMEZONE) {
        Instant checked(Instant v) {
            if (v.getNano() % 1000 != 0 || v.isBefore(MIN_INSTANT) || v.isAfter(MAX_INSTANT))
                throw new IllegalArgumentException("Instant requires finite PostgreSQL range and microsecond precision");
            return v;
        }
        void put(PreparedStatement s, int i, Instant v) throws SQLException { s.setObject(i, v.atOffset(ZoneOffset.UTC)); }
        Instant get(ResultSet r, int i) throws SQLException {
            OffsetDateTime v = r.getObject(i, OffsetDateTime.class);
            return v == null ? null : v.toInstant();
        }
    };

    public static Codec<BigDecimal> decimal(int precision, int scale) {
        if (precision < 1 || precision > 1000 || scale < 0 || scale > precision)
            throw new IllegalArgumentException("Require 1 <= precision <= 1000 and 0 <= scale <= precision");
        return new Exact<>("numeric(" + precision + "," + scale + ")", Types.NUMERIC) {
            BigDecimal checked(BigDecimal v) {
                if (v.scale() > scale) throw new IllegalArgumentException("Excess decimal scale");
                // Check before setScale, including huge negative scales.
                if (v.signum() != 0 && (long) v.precision() - v.scale() > precision - scale)
                    throw new IllegalArgumentException("Decimal precision overflow");
                return v.setScale(scale);
            }
            void put(PreparedStatement s, int i, BigDecimal v) throws SQLException { s.setBigDecimal(i, v); }
            BigDecimal get(ResultSet r, int i) throws SQLException { return r.getBigDecimal(i); }
        };
    }

    private abstract static class Exact<V> implements Codec<V> {
        private final String type;
        private final int nullType;
        Exact(String type, int nullType) { this.type = type; this.nullType = nullType; }
        public final String postgresType() { return type; }
        public final int jdbcNullType() { return nullType; }
        V checked(V value) { return value; }
        abstract void put(PreparedStatement s, int i, V value) throws SQLException;
        abstract V get(ResultSet r, int i) throws SQLException;
        public final void bind(PreparedStatement s, int i, Optional<V> value) throws SQLException {
            Objects.requireNonNull(value);
            if (value.isEmpty()) s.setNull(i, nullType);
            else put(s, i, checked(value.orElseThrow()));
        }
        public final Optional<V> read(ResultSet r, int i) throws SQLException {
            String columnType = r.getMetaData().getColumnTypeName(i);
            String expected = switch (type) {
                case "integer" -> "int4";
                case "bigint" -> "int8";
                case "timestamptz(6)" -> "timestamptz";
                default -> type.startsWith("numeric(") ? "numeric" : type;
            };
            if (!expected.equals(columnType)) throw new SQLException("Codec/column type mismatch", "22005");
            V value = get(r, i);
            return value == null ? Optional.empty() : Optional.of(checked(value));
        }
    }
}
