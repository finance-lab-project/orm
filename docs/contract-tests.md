# Anchor mapper contract-test brief

Status: proposed with the [API contract](api-contract.md) for
[issue #1](https://github.com/finance-lab-project/orm/issues/1).
These are implementation acceptance tests to build after design approval,
not a claim that a JDBC mapper or these integration tests exists today.

## Test boundary and fixtures

Use real disposable PostgreSQL and the root-selected Java/pgJDBC/pool versions.
Apply the exact Budget-owned V1 migration from commit
`e19753ce63fd0a03f4b877ce41b82c483ca03860` through Flyway; do not copy, recreate
or generate the schema in ORM tests. Pin the Budget fixture artifact/commit and
record its checksum. Later migration updates require explicit fixture review.
The root is responsible for deployment migration ordering, not the ORM runner.

Generate synthetic UUID v4 identities in Java, including two distinct owners;
seed only valid module fixtures through the Budget protocol. Use separate
physical connections for concurrency/snapshot tests. Inspect generated SQL and
bound parameters without logging real values, credentials or connection URLs.
Instrument a JDBC/pool wrapper for resource counts and failure injection;
it supplements PostgreSQL tests, not replaces them.

## Required cases and observable assertions

| ID | Case | Required result |
| --- | --- | --- |
| M1 | Category metadata vs migrated database | Match schema/table/column names, UUID/text/integer types, NOT NULL, `(category_id, changed_at)` history PKs and FKs. Knot PK is `category_status.code`, reference `category_status_history.status_code`; owner references catalog guard. |
| M2 | Other supported mapping forms | Check static month/currency, `line_in_budget`/`line_category` static ties, `line_kind_attribute` static knot, recorded amount/state. UUID references, first-of-month DATE, NUMERIC(19,2), BIGINT versions and timestamptz precision 6 match V1. Generated constraint-helper columns are not insertable facts. |
| M3 | Compile-time misuse | Compilation fails for append of a Static attribute, insertStatic of Recorded, mismatched anchor marker, or wrong value type. Domain classes compile without ORM imports/annotations; mappings are supplied explicitly. |
| M4 | Trusted identifier boundary | Valid tokens are individually quoted. Overlength tokens, dotted names, quote/comment/expression payloads fail before prepare; request text never supplies descriptors/SQL. Labels containing apostrophes, Unicode and SQL-looking text remain bound data and leave the schema intact. |
| W1 | Category create/rename/archive walkthrough | Commit creation with all required initial facts at T1, rename at T2, archive at T3. Stable identity/owner/ties; exactly two name, one position, two status rows. Guard/revisions share their command coordinates; inspect every step. |
| W2 | Explicit writes and duplicates | Anchor/static/tie duplicate fails, even with equal value; historical coordinate duplicate fails. An equal value at a different coordinate inserts another row. No hidden reads, conflict-ignore, value suppression, cascade or generated timestamp/identity. |
| W3 | Repeated SET and amount recurrence | SET(100) → SET(150) must insert another SET, then UNSET → SET(150) inserts another amount. Both coordinates/companions persist; history is not deduplicated. Zero and UNSET remain distinct. |
| W4 | One transaction for generated and reviewed SQL | Fail after an anchor/fact and guard update, before revision completion. No facts, anchors, static rows, guard advancement or revision survive; original cause/SQLSTATE retained. |
| W5 | Actual COMMIT-time pairing/completeness | Valid SET+amount in either insertion order and UNSET alone commit. SET without amount, orphan amount, mismatched coordinates and UNSET+amount fail at commit with `23503`, NOT_COMMITTED and full rollback. Incomplete required line/guard membership also fails. Do not replace actual COMMIT with only SET CONSTRAINTS. |
| W6 | Static reference/key consistency | Foreign-owner category, duplicate owner/month or line key, contradictory duplicated static facts and invalid knot code fail via V1 constraints. Budget interprets known conflict cases; ORM preserves database evidence. |
| P1 | Inclusive latest/as-of/history | Current returns latest visible fact; exact T1/T2/T3 boundaries and between-coordinate reads match walkthrough. History is ascending, includes repeats and is eagerly materialized. Each primitive performs one statement on the supplied connection. |
| P2 | Before creation and missing facts | Unknown/foreign Category is not-found. Before common initial coordinate Category is absent. Missing initial history, mismatched initial coordinates or missing required facts after creation are integrity errors, not empty/default DTOs. Timeless owner does not create historical visibility. |
| P3 | Catalog-pinned history | Rename/archive after a Budget revision; its detail still shows the pinned original name/order/status, excludes later categories and retains old lines. Unknown revision does not fall back to latest. Test two owners and verify neither can read the other's revisions. |
| P4 | Amount projection | Before first state is absent; UNSET is empty, SET(0.00) is zero; SET reads amount at exactly the selected state's coordinate. Corrupt/missing companions fail rather than reuse an older value. Use isolated corrupt fixtures/row decoding tests where constraints normally prevent corruption. |
| P5 | Consistent multi-query snapshot | Reader first reads a guard/revision; a second connection commits a rename/archive; reader's later queries still see its first snapshot. A new snapshot sees the change. Assert one physical connection, read-only REPEATABLE READ and no writer locks. |
| C1 | UUID/text/integer/BIGINT | Exact UUID and Unicode round trips, full int/long boundaries and explicit NULL behavior. Unknown enum code fails in module decoding; no numeric/ordinal conversion. NULL in a required field never becomes zero/empty string. |
| C2 | Decimal exactness | 0.00 and 99999999999999999.99 round-trip exactly; 1.2 normalizes exactly to 1.20; reject 1.001 and 1.000 before execute, precision overflow, negative Budget amount and nonfinite numeric. No double conversion; calculated totals are outside the per-input amount codec. |
| C3 | Date/time exactness | LocalDate round-trip without zone shift; YearMonth uses day 1. Reject infinity and unsupported range. Instant microseconds round-trip through UTC OffsetDateTime; reject nanoseconds before execute, including near a rounding boundary. Run under a non-UTC JVM default to prove independence. |
| B1 | Budget-owned change/version/time rules | A stale expected version conflicts even for equal values; current equal normalized command changes nothing. Multi-field/reorder command creates one revision/time. First revision uses DB clock; equal/backward readings select previous + 1µs; same coordinate is reused. ORM never makes these decisions. |
| B2 | Locking and initializer integration | Separate connections prove catalog FOR SHARE blocks a catalog FOR UPDATE change through Budget commit; catalog lock precedes Budget lock. Competing initialization creates one complete catalog or rolls back; existing/all-archived catalog stays unchanged. This is Budget integration coverage using the ORM, not a generic lock manager. |
| L1 | Scoped lifetime | Captured session/Row use after callback/mapper, cross-thread calls and nested runner calls fail before borrowing/executing. No ResultSet/statement/Connection/stream escapes; no new connection per attribute. |
| L2 | Resource closure | Count statement/result closure on success, prepare/bind/execute/read/decode/mapper/callback exceptions, cancellation and commit failure. End transaction before pool return. Broken/unresolved connections are discarded; pool itself remains open. |
| F1 | Swallowed operation error | Callback catches a write/codec/query error and returns a value. Runner remains rollback-only, never commits and never returns that value as saved. |
| F2 | Rollback/cleanup failures | Preserve primary exception/SQLSTATE/chained exceptions; attach later errors. Verify no callback rerun and no autocommit enablement on an unresolved connection. Failure to acquire/configure never invokes the callback. |
| F3 | Unknown commit outcome | A proxy forwards COMMIT to PostgreSQL, loses its response and throws a connection failure. Report UNKNOWN and discard; reconnect and confirm the rows actually committed. Also lose COMMIT before forwarding: classification is still conservative UNKNOWN. Never retry or claim exactly-once. |
| F4 | Confirmed commit, failed cleanup | Let commit return, then fail connection reset/close. Return confirmed result with cleanup diagnostics, never Failed/UNKNOWN; rows/revision exist exactly once and commit/callback were each called once. |
| F5 | Snapshot failures | Fail a query or snapshot finish: fail the read, clean/discard resources and retain cause. ReadSession exposes no writes; PostgreSQL read-only mode rejects reviewed SQL attempting mutation. |

For metadata agreement, inspect PostgreSQL catalogs in tests, including actual
column types/typmods, PK column order, referenced columns, and deferred FK flags
where the tested write protocol relies on them. Startup uses explicit mappings
without scanning/discovery or DDL. Assert a mismatch fails verification; never
repair a database to fit metadata. V1's CHECK/unique/deferred constraints remain
owned by its migration and Budget's schema tests; the ORM suite checks the
subset needed for its contract rather than creating a second schema authority.

## Evidence required before runtime delivery

Record exact Java, PostgreSQL, pgJDBC, pool, Flyway and fixture versions/checksum,
commands, case results, generated SQL/parameter-type assertions and any unrun
cases. A compile-only signature check proves Java syntax/types, not runtime
conversion, transaction correctness or acceptance. Existing Budget SQL design
checks do not substitute for Java/JDBC integration and failure injection.

The design PR must record maintainer acceptance of this brief before runtime
work starts. Any intentional API/schema deviation must be reviewed explicitly.
No merge, deployment, production database change or spending is authorized here.
