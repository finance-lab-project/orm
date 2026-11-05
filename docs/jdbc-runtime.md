# Scoped JDBC runtime

Scope: [issue #2](https://github.com/finance-lab-project/orm/issues/2), using the
accepted [API contract](api-contract.md). Package: `org.financelab.orm`.
This increment supplies `Transactions`, callback-scoped `ReadSession` /
`WriteSession`, `Sql`, `Param`, `Row`, exact `Codecs`, `Completed` and
`PersistenceFailure`. Descriptor-driven SQL generation and projections are not
implemented in this issue. Future mapped operations use the same scoped session
and connection as `execute` / `query`; no separate transaction path is needed.

## Use and ownership

The composition root creates and closes a `HikariDataSource` with an autocommit
baseline. It supplies database credentials and bounded pool/connection timeouts
through protected configuration; the library has no embedded connection settings.
Enable pgJDBC `tcpKeepAlive` in that configuration, per the
[pool guidance](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby).
Construct `new JdbcTransactions(dataSource)`. Do not close the pool per command.

```java
Transactions transactions = new JdbcTransactions(dataSource);
Completed<UUID> saved = transactions.write(session -> {
    session.execute(new Sql("INSERT INTO budget.category (category_id) VALUES (?)"),
        List.of(Param.of(Codecs.UUID, categoryId)));
    // The adapter supplies the remaining owner/fact/guard/revision statements here.
    return categoryId;
});
// Only here is saved.value() a confirmed commit. Report cleanupFailures separately.
```

The excerpt illustrates connection ownership, not a complete valid Category
command. The Budget migration requires owner and guard/revision companions.
The adapter remains responsible for authorization, validation, affected-row
assertions, change detection and all business revision rules.

`write` uses READ COMMITTED, read/write, autocommit disabled, and commits exactly
once before returning. `snapshot` exposes only `ReadSession`, uses read-only
REPEATABLE READ, and rolls back before returning materialized results. Both use
transaction-local UTC. Queries return immutable lists; each Row is revoked when
its mapper returns. Sessions are invalidated on exit. Cross-thread and nested
scopes fail before executing/borrowing; caught operation failures still poison
the outer scope. Separate threads may run independent transactions.

Only trusted module code constructs `Sql` and codecs. `Sql` allows one reviewed
SELECT/INSERT/UPDATE/DELETE/WITH statement, optionally with a trailing semicolon.
Use bound parameters for every data value; use explicit casts for parameters
whose server type is otherwise ambiguous. The server's parameter metadata is
checked for count and base-type agreement before binding. Row codecs also check
the column's base type to prevent narrowing/coercion. SQL parsing here is a
restricted authoring guard, not a sandbox for untrusted SQL: block comments,
dollar quoting and backslash syntax are deliberately excluded. Reviewed SELECT
functions and CTEs must not change connection or transaction settings.

Codecs preserve UUID, Unicode text, integer/BIGINT, finite `LocalDate`,
microsecond `Instant` (via UTC `OffsetDateTime`) and `BigDecimal`. Decimal codecs
carry `numeric(p,s)` metadata, reject excess input scale including trailing
zeros and precision overflow, and normalize accepted values exactly. No locale
or floating-point conversion occurs. DATE supports the common finite
pgJDBC/PostgreSQL range `-4712-01-01` through `5874897-12-31`; Instant supports
the same lower date through `294276-12-31T23:59:59.999999Z`. Driver infinity
sentinels are rejected. Reviewed SQL may explicitly bind/read SQL NULL via
`Optional.empty()`; a future mapped NOT NULL lookup must reject NULL in a
present fact rather than confuse it with a missing row.

## Failure outcomes and cleanup

- Before commit, operation/callback/setup/resource errors produce
  `PersistenceFailure(NOT_COMMITTED)` and best-effort rollback. The original
  cause, SQLSTATE, chained SQLExceptions and suppressed cleanup errors survive.
- At commit, only a PostgreSQL server error proving rejection (constraint class
  23, serialization failure `40001`, deadlock `40P01`, aborted transaction
  `25P02`) produces NOT_COMMITTED. An unrecognized error, generic SQLException,
  lost response or disconnect produces UNKNOWN. Even successful later rollback
  does not change UNKNOWN. No callback, statement or commit is retried.
- A confirmed commit returns its value even when reset/release/discard fails;
  `Completed.cleanupFailures` carries those diagnostics. Never present these as
  a failed save or automatically retry. A failed snapshot query, finish or
  cleanup fails the read with its original evidence.
- Healthy, explicitly ended connections are reset and returned. Unresolved,
  invalid or failed-reset connections are evicted through Hikari. Autocommit is
  never enabled on an unresolved transaction. Fatal callback/operation Errors
  are rethrown after best-effort rollback/cleanup.

## Build and verification

Pinned versions: Java 27.0.1.0.3 (compile release 27), Maven 3.10.0,
pgJDBC 42.7.14, PostgreSQL 18.6, HikariCP 7.1.0, Flyway 13.12.0 and
JUnit Jupiter 6.1.3. Root-selected pins come from the
[runtime report](https://github.com/finance-lab-project/app/blob/main/docs/runtime-stack.md)
and bootstrap. The pool pin was checked against its
[publisher](https://github.com/brettwooldridge/HikariCP#artifacts) and Maven Central
metadata on 2026-11-05.

The exact root `build-parent:0.1.0` POM is vendored in `build-support/` because
the selected registry is not provisioned. Its upstream Git blob is
`f276c8cd9ed0cba562bf83853f6fc503fa5178f6`, SHA-256
`aa61d398a42e3df231b696532a545872296bba8d039b453cf5ad98f5b284a34f`.
This preserves a standalone clean-checkout build without an uncommitted sibling
checkout or an invented registry URL. Publication is disabled. Switching to the
approved registry is release-assembly work; no artifacts are published here.

With the pinned JDK/Maven on PATH, Docker already running, and network access:

```sh
mvn -B -ntp test -Dsource.revision="$(git rev-parse HEAD)"
scripts/verify.sh
git diff --check
```

`test` runs focused codec/SQL authoring tests. `verify.sh` creates an isolated
PostgreSQL 18.6 container on a random loopback port with synthetic trust-auth
fixtures, downloads Budget's original V1 migration from commit
`e19753ce63fd0a03f4b877ce41b82c483ca03860`, checks SHA-256
`33396e787b738b46ce9933d1ff17abacec8326430f49f96758cea466658f0f0c`,
and applies it through Flyway. The schema is not copied or re-created here.
The exit trap removes the disposable container. Integration tests require
`ORM_TEST_URL` / `ORM_TEST_USER`; they fail rather than silently skip when absent.

Integration coverage includes real commit visibility, partial-write rollback,
deferred commit rejection, same-connection guard/fact writes, concurrent
snapshot consistency, PostgreSQL read-only enforcement, exact codecs under a
non-UTC JVM, scope/Row revocation and injected acquire/setup/bind/execute/read/
mapper/close/rollback/commit/reset/release/discard failures. Both before-forward
and after-forward lost commits must report UNKNOWN; rereads prove whether the
synthetic row actually committed. Resource counters and the real pool's active
borrow count check the scoped failure cases. Mapping metadata, generated SQL,
Category projections and Budget business-rule cases in the full
[contract-test brief](contract-tests.md) remain with their delivery issues.

Local observed results are recorded in the implementing PR; no asynchronous
GitHub checks or deployment outcome is inferred from local tests.
