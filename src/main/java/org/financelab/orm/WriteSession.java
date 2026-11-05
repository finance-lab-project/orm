package org.financelab.orm;

import java.util.List;
import java.time.Instant;

public interface WriteSession extends ReadSession {
    <A, I> void insertAnchor(Anchor<A, I> anchor, I id);
    <A, I, V> void insertStatic(Scalar<A, I, V, Static> map, I id, V value);
    <A, I, C> void insertStatic(Knotted<A, I, C, Static> map, I id, C code);
    <A, I, B, J> void insertTie(StaticTie<A, I, B, J> map, I id, J target);
    <A, I, V> void append(Scalar<A, I, V, Recorded> map, I id, Instant at, V value);
    <A, I, C> void append(Knotted<A, I, C, Recorded> map, I id, Instant at, C code);
    int execute(Sql sql, List<Param<?>> params);
}
