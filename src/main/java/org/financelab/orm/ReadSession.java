package org.financelab.orm;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface ReadSession {
    <A, I> boolean exists(Anchor<A, I> anchor, I id);
    <A, I, V> Optional<V> readStatic(Scalar<A, I, V, Static> map, I id);
    <A, I, C> Optional<C> readStatic(Knotted<A, I, C, Static> map, I id);
    <A, I, B, J> Optional<J> readTie(StaticTie<A, I, B, J> map, I id);
    <A, I, V> Optional<Fact<V>> current(Scalar<A, I, V, Recorded> map, I id);
    <A, I, C> Optional<Fact<C>> current(Knotted<A, I, C, Recorded> map, I id);
    <A, I, V> Optional<Fact<V>> asOf(Scalar<A, I, V, Recorded> map, I id, Instant at);
    <A, I, C> Optional<Fact<C>> asOf(Knotted<A, I, C, Recorded> map, I id, Instant at);
    <A, I, V> List<Fact<V>> history(Scalar<A, I, V, Recorded> map, I id);
    <A, I, C> List<Fact<C>> history(Knotted<A, I, C, Recorded> map, I id);
    <R> List<R> query(Sql sql, List<Param<?>> params, RowMapper<R> mapper);
}
