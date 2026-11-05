package org.financelab.orm;

@FunctionalInterface
public interface RowMapper<R> { R map(Row row); }
