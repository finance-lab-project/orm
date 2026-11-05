package org.financelab.orm;

import java.util.List;

public interface ReadSession {
    <R> List<R> query(Sql sql, List<Param<?>> params, RowMapper<R> mapper);
}
