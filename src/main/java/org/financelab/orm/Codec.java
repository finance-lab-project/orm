package org.financelab.orm;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Trusted JDBC conversion code; implementations must not retain JDBC handles. */
public interface Codec<V> {
    String postgresType();
    int jdbcNullType();
    void bind(PreparedStatement statement, int index, Optional<V> value) throws SQLException;
    Optional<V> read(ResultSet rows, int index) throws SQLException;
}
