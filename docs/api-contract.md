# Minimal Anchor mapper API contract

Date: 2026-11-05. Status: proposed for acceptance in
[ORM issue #1](https://github.com/finance-lab-project/orm/issues/1).
This is the API/contract-test brief required before runtime implementation.
Acceptance is a maintainer decision recorded on its PR; this document does not
claim that review has happened. Implementation must wait for that acceptance.

The [canonical architecture, sections 4–6](https://github.com/finance-lab-project/app/blob/main/docs/architecture.md)
defines the boundaries. The concrete schema dependency is merged
[Budget PR #5](https://github.com/finance-lab-project/budget/pull/5), commit
`e19753ce63fd0a03f4b877ce41b82c483ca03860`. Use its
[V1 migration](https://github.com/finance-lab-project/budget/blob/e19753ce63fd0a03f4b877ce41b82c483ca03860/src/main/resources/db/migration/V1__budget_schema.sql)
and [write/read protocols](https://github.com/finance-lab-project/budget/blob/e19753ce63fd0a03f4b877ce41b82c483ca03860/docs/schema.md).
The architecture's older unmerged-schema status and illustrative knot key
`category_status(status_code)` are superseded by that migration: the knot key
is `category_status(code)`; the history reference is `status_code`.

## Ownership and supported surface

Business modules supply immutable mapping descriptors explicitly at assembly.
Budget keeps them in `adapter/persistence/mapping/`; domain classes have no ORM
imports or annotations. Persistence-local marker types distinguish anchors even
when both identities are UUIDs. Domain UUIDs and enums need no ORM base class.
The module maps domain status to a storage code in its persistence adapter.

The ORM generates PostgreSQL identity lookups, inserts, latest/as-of reads and
ordered fact history from these descriptors. Budget owns authorization, value
validation, business equality/change detection, guard SQL, locking order,
recording-coordinate selection, versions, revisions, catalog pinning and DTO
assembly. No generic `save(entity)`, automatic cascade, update/delete of Anchor
facts, JPA compatibility, dirty checking, lazy proxy, annotation scanning,
runtime DDL, alternate dialect, query language or automatic retry is introduced.
Operational guard updates use reviewed module SQL on the same scoped session.

## Typed API shape

The following single Java block is a compilable signature specimen, not library
implementation. Record constructors below show shape only; runtime validation
must enforce the invariants specified after the block. Methods are synchronous.

```java
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class ApiShape {
    record Identifier(String value) {}
    record Table(Identifier schema, Identifier name) {}
    interface Codec<V> {
        String postgresType();
        int jdbcNullType();
        void bind(PreparedStatement statement, int index, Optional<V> value)
                throws SQLException;
        Optional<V> read(ResultSet rows, int index) throws SQLException;
    }
    record Column<V>(Identifier name, Codec<V> codec) {}
    record Anchor<A, I>(Table table, Column<I> id) {}
    record Knot<C>(Table table, Column<C> code) {}

    sealed interface TimeShape permits Static, Recorded {}
    record Static() implements TimeShape {}
    record Recorded(Column<Instant> changedAt) implements TimeShape {}
    record Scalar<A, I, V, T extends TimeShape>(
            Anchor<A, I> anchor, Table table, Column<I> id,
            Column<V> value, T time) {}
    record Knotted<A, I, C, T extends TimeShape>(
            Anchor<A, I> anchor, Table table, Column<I> id,
            Column<C> value, Knot<C> knot, T time) {}
    // R1 has functional, immutable ties: one target per source identity.
    record StaticTie<A, I, B, J>(
            Anchor<A, I> source, Anchor<B, J> target,
            Table table, Column<I> sourceId, Column<J> targetId) {}
    record Fact<V>(Instant changedAt, V value) {}

    // Trusted module SQL only; '?' placeholders are positional JDBC parameters.
    record Sql(String text) {}
    record Param<V>(Codec<V> codec, Optional<V> value) {}
    interface Row {
        <V> Optional<V> get(int index, Codec<V> codec);
    }
    interface RowMapper<R> { R map(Row row); }

    interface ReadSession {
        <A, I> boolean exists(Anchor<A, I> anchor, I id);
        <A, I, V> Optional<V> readStatic(Scalar<A, I, V, Static> map, I id);
        <A, I, C> Optional<C> readStatic(Knotted<A, I, C, Static> map, I id);
        <A, I, B, J> Optional<J> readTie(StaticTie<A, I, B, J> map, I id);
        <A, I, V> Optional<Fact<V>> current(Scalar<A, I, V, Recorded> map, I id);
        <A, I, C> Optional<Fact<C>> current(Knotted<A, I, C, Recorded> map, I id);
        <A, I, V> Optional<Fact<V>> asOf(
                Scalar<A, I, V, Recorded> map, I id, Instant at);
        <A, I, C> Optional<Fact<C>> asOf(
                Knotted<A, I, C, Recorded> map, I id, Instant at);
        <A, I, V> List<Fact<V>> history(Scalar<A, I, V, Recorded> map, I id);
        <A, I, C> List<Fact<C>> history(Knotted<A, I, C, Recorded> map, I id);
        <R> List<R> query(Sql sql, List<Param<?>> params, RowMapper<R> mapper);
    }
    interface WriteSession extends ReadSession {
        <A, I> void insertAnchor(Anchor<A, I> anchor, I id);
        <A, I, V> void insertStatic(Scalar<A, I, V, Static> map, I id, V value);
        <A, I, C> void insertStatic(Knotted<A, I, C, Static> map, I id, C code);
        <A, I, B, J> void insertTie(StaticTie<A, I, B, J> map, I id, J target);
        <A, I, V> void append(
                Scalar<A, I, V, Recorded> map, I id, Instant at, V value);
        <A, I, C> void append(
                Knotted<A, I, C, Recorded> map, I id, Instant at, C code);
        int execute(Sql sql, List<Param<?>> params);
    }
    interface Work<S, R> { R run(S session) throws Exception; }
    record Completed<R>(R value, List<Throwable> cleanupFailures) {}
    enum WriteOutcome { NOT_COMMITTED, UNKNOWN }
    abstract static class PersistenceFailure extends RuntimeException {
        abstract WriteOutcome outcome();
        abstract Optional<String> sqlState();
    }
    interface Transactions {
        <R> Completed<R> write(Work<WriteSession, R> work);
        <R> Completed<R> snapshot(Work<ReadSession, R> work);
    }

    // Example Budget persistence descriptors; codecs supplied by assembly.
    static final class CategoryRow {}
    static final class LineRow {}
    record CategoryMapping(
            Anchor<CategoryRow, UUID> anchor,
            Scalar<CategoryRow, UUID, UUID, Static> owner,
            Scalar<CategoryRow, UUID, String, Recorded> name,
            Scalar<CategoryRow, UUID, Integer, Recorded> position,
            Knotted<CategoryRow, UUID, String, Recorded> status) {}
    static CategoryMapping category(
            Codec<UUID> uuid, Codec<String> text,
            Codec<Integer> integer, Codec<Instant> instant) {
        var id = new Column<>(new Identifier("category_id"), uuid);
        var anchor = new Anchor<CategoryRow, UUID>(table("category"), id);
        var time = new Recorded(new Column<>(new Identifier("changed_at"), instant));
        var statusKnot = new Knot<>(table("category_status"),
                new Column<>(new Identifier("code"), text));
        return new CategoryMapping(anchor,
                new Scalar<>(anchor, table("category_owner"), id,
                        new Column<>(new Identifier("owner_id"), uuid), new Static()),
                new Scalar<>(anchor, table("category_name"), id,
                        new Column<>(new Identifier("name"), text), time),
                new Scalar<>(anchor, table("category_position"), id,
                        new Column<>(new Identifier("position"), integer), time),
                new Knotted<>(anchor, table("category_status_history"), id,
                        new Column<>(new Identifier("status_code"), text), statusKnot, time));
    }
    static Table table(String name) {
        return new Table(new Identifier("budget"), new Identifier(name));
    }
}
```

`A`/`B` identify mapping roles; `I`/`J` are identity values; `V`/`C` are scalar
values/knot codes. A recorded descriptor cannot be passed to `insertStatic`, and
a static descriptor cannot be passed to `append`. The compiler also rejects a
line attribute paired with a category anchor or a decimal value for a name.
`Recorded` is the single recording dimension, not validity time or bitemporality.
`StaticTie` supports `line_in_budget` and `line_category`, with no cascades or
historized ties. Owner is a scalar UUID referencing a catalog guard, not a
fabricated Owner anchor. No knot insertion/edit operation is provided.

Descriptors must have non-null components, compatible identity/value codecs,
distinct role columns and one consistent qualified table definition. Identifiers
are separate schema/table/column tokens matching `[a-z_][a-z0-9_]*`, at most 63
ASCII bytes each; quote each token with PostgreSQL double quotes. Reject dotted
names, overlength tokens, whitespace, expressions,
quotes, comments and SQL fragments. Identifier validation is not authorization:
only trusted, explicitly registered module code constructs descriptors or `Sql`.
Never derive them from request text, an editable label, sort input or a client
owner ID. `Sql` accepts one reviewed statement, no transaction-control/DDL or
multiple statements; only the runner controls transaction lifecycle.

Every data value, identity, code and timestamp uses a `PreparedStatement` bound
parameter, including the reviewed-SQL escape hatch. Knotted values bind the TEXT
code directly; no lookup or insertion of a numeric surrogate occurs. No value
interpolation, identifier placeholders or handwritten escaping of data is allowed.
Generated insert/append must affect exactly one row; zero/multiple rows is a
failure. Never use `ON CONFLICT DO NOTHING`, an upsert, `DISTINCT` or value
comparison to suppress facts. A duplicate identity/static row/history coordinate
is an error even if the supplied value equals the existing value. Equal values at
different coordinates are both inserted. Budget's explicitly reviewed initializer
claim may use `ON CONFLICT(owner_id) DO NOTHING`; that is not generic mapper policy.

## Exact codecs and nulls

All mapped anchor, scalar, knot, tie and fact values are non-null in this profile.
Reject Java null before executing SQL. `Optional.empty()` from a lookup means no
row; it never means SQL NULL in a present fact. A present NULL in a mapped
NOT NULL column is an integrity failure. Reviewed SQL can explicitly bind NULL
with `Param(codec, Optional.empty())` and `setNull` using that codec's JDBC type;
its `Row.get` returns empty for SQL NULL. Reject parameter-count/type mismatches.

| Java / PostgreSQL | Required conversion |
| --- | --- |
| `UUID` / `uuid` | Bind the supplied UUID with `setObject`; read `getObject(index, UUID.class)`. No text/numeric coercion or generated database identity. Budget generates new UUID v4 values before insertion. |
| `String` / `text` | `setString`/`getString`; preserve Unicode, spaces and case exactly. Reject NUL/unsupported text rather than stripping it. Budget validates labels and maps domain enums to explicit known codes; unknown stored codes are integrity errors, never ordinal/default mappings. |
| `Integer` / `integer` | `setInt`/`getInt` with NULL detection; no truncation from wider numbers. |
| `Long` / `bigint` | `setLong`/`getLong` with NULL detection; no narrowing. Budget owns positive version checks and overflow-safe increments. |
| `LocalDate` / `date` | JDBC 4.2 `setObject`/`getObject(LocalDate.class)`; finite, representable dates only, no time-zone conversion. Budget maps `YearMonth` to day 1 and rejects other stored days. |
| `Instant` / `timestamptz(6)` | Require `nano % 1000 == 0`; reject excess precision before bind. Convert to UTC `OffsetDateTime` for `setObject`, read `OffsetDateTime` then `toInstant`. Reject infinity and values outside the common finite Java/PostgreSQL range; never round/truncate. |
| `BigDecimal` / `numeric(p,s)` | Descriptor codec carries `p,s`; `setBigDecimal`/`getBigDecimal`, no float/double/string path. Reject input `scale() > s` even for trailing zeros, normalize accepted values to scale `s` exactly, and reject precision overflow before bind. Reject nonfinite database numeric values; read must be exact. Budget supplies `numeric(19,2)` and validates nonnegative range before writing. |

Codec metadata retains PostgreSQL type and precision/scale for schema comparison.
No locale parsing/formatting belongs here. The pinned driver's documented temporal
bridge is `OffsetDateTime`, not direct `Instant` support:
[pgJDBC date/time mapping](https://jdbc.postgresql.org/documentation/query/#using-java-8-date-and-time-classes).
Finite-range checks include rejecting the driver's infinity sentinel values on
both bind/read. UTC session configuration does not convert a date into an instant.

## Transactions, scope and failures

`Transactions` borrows one connection from the root-supplied DataSource/pool per
callback. Write scope is READ COMMITTED, read/write, autocommit disabled.
Snapshot scope is read-only REPEATABLE READ, autocommit disabled. Configure both
before the first application statement and set the transaction-local time zone
to UTC. Every generated and reviewed statement uses that connection. A snapshot
keeps one PostgreSQL snapshot across all queries and finishes before returning
materialized results. End a successful read-only scope with rollback; it has no
writes to commit. No writer locks are needed to present the result.

Sessions are thread-confined, callback-scoped and invalidated on every exit path.
Any call after scope exit, from another thread or through a nested runner scope
fails immediately without borrowing another connection. There is no session
`commit`, `rollback`, `close`, raw Connection, cursor, stream or lazy object.
Queries/history return eagerly materialized lists. A `Row` is valid only while
its mapper runs. Close ResultSet and PreparedStatement after each operation,
including binding, decoding, mapping and execution failures. Trusted codec
implementations use JDBC handles only for conversion and cannot retain them.

On normal write callback return, close statement resources, then call commit
exactly once. Return `Completed.value` only after confirmed commit, including a
Budget `Unchanged` result. Any operation/binding/codec/mapping failure marks the
session rollback-only even if the callback catches its exception. Do not commit
that scope. Callback exceptions also roll back; preserve the original cause,
SQLSTATE and chained SQLExceptions in `PersistenceFailure`, with rollback and
cleanup problems suppressed. Fatal JVM Errors are rethrown after best-effort
rollback/cleanup. No failure path retries callback, statement or commit.

| Failure point | Contract |
| --- | --- |
| Acquire/setup, callback, statement, or resource close before commit | `PersistenceFailure(NOT_COMMITTED)`; roll back if possible and release/discard. Callback results never escape as saved. |
| Commit explicitly rejected by PostgreSQL, e.g. deferred FK violation | `NOT_COMMITTED`; whole transaction fails, including guards/revisions; preserve SQLSTATE `23503`. |
| Commit started but confirmation lost, timeout/disconnect, or no reliable proof of rejection | `UNKNOWN`, even if a later rollback returns normally. Discard connection; preserve the original failure. |
| Commit returned successfully, then reset/close/discard fails | Return the confirmed value with `Completed.cleanupFailures`; never throw a failed-save result or retry. Report cleanup separately to internal diagnostics. |
| Snapshot query/finish fails | Fail the read with original cause and cleanup details; it has no write outcome uncertainty. |

`NOT_COMMITTED` means commit was never attempted or authoritative server evidence
proves abortion; it does not assert that rollback/cleanup itself succeeded.
The implementation must conservatively classify unrecognized commit failures
as UNKNOWN. Return/reset only a healthy, ended connection to the pool. If rollback,
reset or health is uncertain, invalidate/discard the physical connection through
the selected pool; never enable autocommit on an unresolved transaction.
Root owns pool lifetime, the runner owns each borrowed connection's lifetime.
[JDBC Connection lifecycle](https://docs.oracle.com/en/java/javase/25/docs/api/java.sql/java/sql/Connection.html)
requires explicit transaction completion rather than relying on close.

Budget maps these facts to its Saved/Unchanged/Invalid/Conflict/Failed/
OutcomeUnknown outcomes. Constraint errors do not automatically become a
business Conflict: Budget interprets its known constraint/version cases.
Unknown outcome preserves the draft and requires reread before a user-directed
save. A matching reread is stored state, not proof of which command committed.
No operation receipt, exactly-once claim or automatic retry is part of R1.

## Projection semantics and SQL generation

Each read method issues one explicit statement; there is no hidden object graph
loading or cache. The module assembles its DTO inside `snapshot`, or uses one
reviewed SQL query for a combined projection. `current` selects the greatest
visible coordinate; `asOf` selects the greatest coordinate `<= at`. `history`
returns every fact in ascending coordinate order, including repeated values.
Only identity lookup is generated; arbitrary filters, joins and pagination are
not a new query language. Ties return the target identity without loading it.

For Category name, generated SQL is exactly this shape (identifiers quoted by
the generator; both values bound):

```sql
INSERT INTO "budget"."category_name" ("category_id", "changed_at", "name")
VALUES (?, ?, ?);

SELECT "changed_at", "name" FROM "budget"."category_name"
WHERE "category_id" = ? AND "changed_at" <= ?
ORDER BY "changed_at" DESC LIMIT 1;
```

Current omits the coordinate predicate; history omits LIMIT and orders ASC.
Knot projections read the stored code, without joining away an invalid reference.
An unknown identity or a missing requested fact returns empty at this low-level
API. Budget distinguishes authorized not-found, pre-creation absence and integrity
errors when assembling a required Category projection. ORM identity lookup alone
does not authorize a read.

Category anchors have no creation timestamp. Budget's reviewed read query first
checks `category_owner(category_id, owner_id)` using the authenticated owner, and
loads the earliest coordinate of each required history (name, position, status).
All three must exist and have the same initial coordinate; otherwise report
integrity failure. This is required even when all latest slots are missing: a
bare/incomplete Category must not disappear as though it were valid history.
If `at` precedes that initial coordinate, return absent; otherwise require all
three `asOf` facts. Static owner is timeless and must never itself make a category
visible before its initial facts. Current projection requires all three current
facts too. Do these checks and reads in the same snapshot. These checks belong
to Budget because the ORM cannot infer an entity's creation rule from an anchor.

Budget resolves catalog versions to times before calling `asOf`. An unknown
version or foreign owner returns not-found, never current state. A Budget
revision's `catalog_version` selects the catalog coordinate; do not compare the
two aggregates' clocks or use today's catalog for revision details. For amount
projections Budget first reads state at/before T: no state means no line yet;
UNSET means empty; SET requires amount at that exact state's coordinate. Using
an older amount or substituting zero is an integrity error.

## Complete Category walkthrough

All values here are synthetic. Create owner O's catalog revision 1 at T1 with
category C, Java-generated UUID v4, name `Транспорт`, position 4 and ACTIVE.
The same creation operations apply when adding C to a later existing catalog.

1. **Create:** Budget validates ownership/values. In one `write(tx -> ...)`, an
   existing catalog is locked/checked before choosing its next coordinate. For
   first initialization, sample the database clock once as T1, then claim the
   catalog guard at T1 before the owner fact's immediate FK check. Then call
   `insertAnchor(m.anchor(), C)`,
   `insertStatic(m.owner(), C, O)`, `append(m.name(), C, T1, "Транспорт")`,
   `append(m.position(), C, T1, 4)` and
   `append(m.status(), C, T1, "ACTIVE")`. Budget's reviewed SQL writes the guard
   and catalog revision 1 on that same session. Commit the complete initial
   catalog, including all ten defaults when initializing; failure rolls it all
   back. The ORM never generates C or chooses T1.
2. **Rename:** Budget locks `catalog_guard FOR UPDATE`, checks expected version
   1 and authorized current state. A stale version conflicts even if the label
   matches; a valid equal label is Unchanged. Otherwise compute T2 as the later
   of `clock_timestamp()` and T1 + one microsecond, append only
   `m.name(), C, T2, "Транспорт и такси"`, and use reviewed SQL to advance the
   guard and insert revision 2 at T2. No position/status or monthly amount is
   appended by the mapper automatically.
3. **Archive:** With expected version 2, Budget locks/checks/computes T3 and
   appends only `m.status(), C, T3, "ARCHIVED"`; guard and revision 3 share T3.
   No anchor/static/tie/history deletion occurs. Existing monthly lines retain
   C and their amounts, remain editable and included in totals. Budget excludes
   C from new monthly lines. A reorder similarly appends only changed positions,
   using one catalog coordinate/revision for the whole command.
4. **Read:** Budget authorizes and resolves the requested catalog revision inside
   one `snapshot`, then assembles the owner and required fact projections. The
   callback returns a fully materialized module DTO. No session survives the
   callback and no transaction stays open while the user views or edits it.

| Projection | Name / position / status |
| --- | --- |
| Before T1 | Absent |
| Revision 1, T1 inclusive, through before T2 | Транспорт / 4 / ACTIVE |
| Revision 2, T2 inclusive, through before T3 | Транспорт и такси / 4 / ACTIVE |
| Revision 3 or current after T3 | Транспорт и такси / 4 / ARCHIVED |
| Budget revision pinned to catalog 1, read after T3 | Транспорт / 4 / ACTIVE |

Name history contains two facts, position one, status two. Equal raw facts at a
new coordinate would still be recorded if explicitly requested from the ORM;
only Budget's command logic prevents a business no-op from requesting them.

## Acceptance and implementation gate

Review this API shape, the walkthrough and every case in the
[contract-test brief](contract-tests.md). Record acceptance (or the specific
requested changes) on the design PR before implementing the mapper or changing
the Budget adapter. Runtime implementation must use the accepted contract and
verify its metadata against the reviewed migration. A later schema/API change
requires coordinated review; no startup schema repair is allowed.
