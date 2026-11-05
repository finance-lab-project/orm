package org.financelab.orm;

import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.financelab.orm.BudgetMappings.*;
import static org.financelab.orm.JdbcTransactionsIT.at;
import static org.financelab.orm.JdbcTransactionsIT.catalog;
import static org.financelab.orm.JdbcTransactionsIT.execute;
import static org.financelab.orm.JdbcTransactionsIT.id;
import static org.financelab.orm.JdbcTransactionsIT.scalar;
import static org.financelab.orm.JdbcTransactionsIT.text;
import static org.junit.jupiter.api.Assertions.*;

class AnchorMappingIT {
    static HikariDataSource pool;
    static JdbcTransactions tx;
    static final Instant T1 = JdbcTransactionsIT.T1;
    static final Instant T2 = T1.plusSeconds(1), T3 = T2.plusSeconds(1), T4 = T3.plusSeconds(1);

    @BeforeAll static void database() {
        String url = Objects.requireNonNull(System.getenv("ORM_TEST_URL"), "Run scripts/verify.sh");
        String user = Objects.requireNonNull(System.getenv("ORM_TEST_USER"));
        Flyway.configure().dataSource(url, user, "").locations("filesystem:target/test-fixture").load().migrate();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url); config.setUsername(user); config.setMaximumPoolSize(3); config.setMinimumIdle(0);
        config.setConnectionTimeout(3000); config.addDataSourceProperty("tcpKeepAlive", "true");
        pool = new HikariDataSource(config); tx = new JdbcTransactions(pool);
        assertEquals("Pacific/Honolulu", TimeZone.getDefault().getID());
    }
    @AfterAll static void shutdown() { if (pool != null) pool.close(); }
    @AfterEach void noLeaks() { assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections()); }

    record CategorySeed(UUID owner, UUID category) {}
    static CategorySeed seedCategory() {
        var seed = new CategorySeed(UUID.randomUUID(), UUID.randomUUID());
        tx.write(s -> { catalog(s, seed.owner()); createCategory(s, seed); return null; });
        return seed;
    }
    static void createCategory(WriteSession s, CategorySeed seed) {
        s.insertAnchor(CATEGORY, seed.category()); s.insertStatic(OWNER, seed.category(), seed.owner());
        s.append(NAME, seed.category(), T1, "Транспорт"); s.append(POSITION, seed.category(), T1, 4);
        s.append(STATUS, seed.category(), T1, "ACTIVE");
    }
    static void revision(WriteSession s, CategorySeed seed, long version, Instant time) {
        execute(s, "UPDATE budget.catalog_guard SET current_version=?, last_changed_at=? WHERE owner_id=?",
                Param.of(Codecs.BIGINT, version), at(time), id(seed.owner()));
        execute(s, "INSERT INTO budget.catalog_revision VALUES (?, ?, ?)", id(seed.owner()), Param.of(Codecs.BIGINT, version), at(time));
    }
    record CategoryDto(String name, int position, String status) {}
    // Module-level creation/ownership semantics; the mapper never guesses an entity's creation time.
    static Optional<CategoryDto> category(ReadSession s, CategorySeed seed, Instant time) {
        if (!s.readStatic(OWNER, seed.category()).filter(seed.owner()::equals).isPresent()) return Optional.empty();
        Instant nameStart = s.history(NAME, seed.category()).stream().findFirst().orElseThrow().changedAt();
        Instant positionStart = s.history(POSITION, seed.category()).stream().findFirst().orElseThrow().changedAt();
        Instant statusStart = s.history(STATUS, seed.category()).stream().findFirst().orElseThrow().changedAt();
        if (!nameStart.equals(positionStart) || !nameStart.equals(statusStart)) throw new IllegalStateException("Inconsistent Category creation facts");
        if (time != null && time.isBefore(nameStart)) return Optional.empty();
        Fact<String> name = (time == null ? s.current(NAME, seed.category()) : s.asOf(NAME, seed.category(), time)).orElseThrow();
        Fact<Integer> position = (time == null ? s.current(POSITION, seed.category()) : s.asOf(POSITION, seed.category(), time)).orElseThrow();
        Fact<String> status = (time == null ? s.current(STATUS, seed.category()) : s.asOf(STATUS, seed.category(), time)).orElseThrow();
        if (!Set.of("ACTIVE", "ARCHIVED").contains(status.value())) throw new IllegalStateException("Unknown Category status");
        return Optional.of(new CategoryDto(name.value(), position.value(), status.value()));
    }

    @Test void descriptorsMatchReviewedMigrationIncludingKnotKeysAndHelperConstraints() {
        tx.snapshot(s -> {
            MappingSchema.verify(s, ALL);
            assertEquals("catalog_guard:owner_id", scalar(s, """
                    SELECT rc.relname::text || ':' || ra.attname::text
                    FROM pg_catalog.pg_constraint con
                    JOIN pg_catalog.pg_class rc ON rc.oid=con.confrelid
                    JOIN pg_catalog.pg_attribute ra ON ra.attrelid=rc.oid AND ra.attnum=con.confkey[1]
                    WHERE con.conrelid='budget.category_owner'::regclass AND con.contype='f'
                      AND rc.relname='catalog_guard'
                    """, Codecs.TEXT));
            assertEquals(List.of("amount_requires_set_fk:true:true:3:3", "set_requires_amount_fk:true:true:2:2"), s.query(new Sql("""
                    SELECT conname::text || ':' || condeferrable::text || ':' || condeferred::text
                        || ':' || cardinality(conkey)::text || ':' || cardinality(confkey)::text
                    FROM pg_catalog.pg_constraint WHERE conname IN ('amount_requires_set_fk','set_requires_amount_fk') ORDER BY conname
                    """), List.of(), r -> r.get(1, Codecs.TEXT).orElseThrow()));
            assertEquals(2L, scalar(s, """
                    SELECT count(*) FROM pg_catalog.pg_attribute
                    WHERE attrelid IN ('budget.line_amount'::regclass,'budget.line_amount_state'::regclass) AND attgenerated='s'
                    """, Codecs.BIGINT));
            return null;
        });
    }

    @Test void schemaMismatchesAndConflictingRegistrationsFailWithoutRepair() {
        var wrongMoney = new Scalar<>(LINE, AMOUNT.table(), LINE_ID, column("amount", Codecs.decimal(18, 2)), RECORDED);
        var wrongType = new Scalar<>(CATEGORY, NAME.table(), CATEGORY_ID, column("name", Codecs.INTEGER), RECORDED);
        var wrongColumn = new Scalar<>(CATEGORY, NAME.table(), CATEGORY_ID, column("label", Codecs.TEXT), RECORDED);
        var wrongKey = new Scalar<>(CATEGORY, NAME.table(), CATEGORY_ID, NAME.value(), STATIC);
        var wrongReference = new Knotted<>(CATEGORY, STATUS.table(), CATEGORY_ID, STATUS.value(), knot("amount_state"), RECORDED);
        var helper = new Scalar<>(LINE, STATE.table(), LINE_ID, column("set_line_id", Codecs.UUID), RECORDED);
        for (Object bad : List.of(wrongMoney, wrongType, wrongColumn, wrongKey, wrongReference, helper))
            assertThrows(PersistenceFailure.class, () -> tx.snapshot(s -> { MappingSchema.verify(s, List.of(bad)); return null; }));
        FaultPool tracked = new FaultPool(pool);
        assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).snapshot(s -> {
            MappingSchema.verify(s, List.of(NAME, wrongType)); return null;
        }));
        assertEquals(0, tracked.queries);
        tx.snapshot(s -> { MappingSchema.verify(s, ALL); return null; });
    }

    @Test void categoryWalkthroughInclusiveHistoryAbsenceAndRepresentativeQueryCounts() {
        CategorySeed seed = seedCategory(); UUID c = seed.category();
        tx.write(s -> { s.append(NAME, c, T2, "Транспорт и такси"); revision(s, seed, 2, T2); return null; });
        tx.write(s -> { s.append(STATUS, c, T3, "ARCHIVED"); revision(s, seed, 3, T3); return null; });
        FaultPool tracked = new FaultPool(pool);
        CategoryDto current = new JdbcTransactions(tracked).snapshot(s -> category(s, seed, null).orElseThrow()).value();
        assertEquals(new CategoryDto("Транспорт и такси", 4, "ARCHIVED"), current);
        assertEquals(7, tracked.queries); assertEquals(1, tracked.borrows); assertEquals(0, tracked.statements); assertEquals(0, tracked.results);
        tx.snapshot(s -> {
            assertTrue(s.exists(CATEGORY, c)); assertFalse(s.exists(CATEGORY, UUID.randomUUID()));
            assertEquals(Optional.empty(), s.current(NAME, UUID.randomUUID()));
            assertTrue(s.history(STATUS, UUID.randomUUID()).isEmpty());
            assertTrue(category(s, seed, T1.minusNanos(1000)).isEmpty());
            assertTrue(category(s, new CategorySeed(UUID.randomUUID(), c), T3).isEmpty());
            assertEquals(Optional.of(new CategoryDto("Транспорт", 4, "ACTIVE")), category(s, seed, T1));
            assertEquals("Транспорт и такси", category(s, seed, T2).orElseThrow().name());
            assertEquals("ACTIVE", category(s, seed, T3.minusNanos(1000)).orElseThrow().status());
            assertEquals("ARCHIVED", category(s, seed, T3).orElseThrow().status());
            // Resolve the catalog revision explicitly; after archival it still projects its original label/status.
            Instant pinned = scalar(s, "SELECT changed_at FROM budget.catalog_revision WHERE owner_id=? AND version=?",
                    Codecs.INSTANT, id(seed.owner()), Param.of(Codecs.BIGINT, 1L));
            assertEquals(new CategoryDto("Транспорт", 4, "ACTIVE"), category(s, seed, pinned).orElseThrow());
            assertEquals(List.of(new Fact<>(T1, "Транспорт"), new Fact<>(T2, "Транспорт и такси")), s.history(NAME, c));
            assertEquals(1, s.history(POSITION, c).size()); assertEquals(2, s.history(STATUS, c).size());
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings={"none", "missing status", "different initial time"})
    void incompleteCategoryIsAnIntegrityFailureEvenBeforeCreation(String mode) {
        CategorySeed seed = new CategorySeed(UUID.randomUUID(), UUID.randomUUID());
        tx.write(s -> {
            catalog(s, seed.owner()); s.insertAnchor(CATEGORY, seed.category()); s.insertStatic(OWNER, seed.category(), seed.owner());
            if (!mode.equals("none")) {
                s.append(NAME, seed.category(), T1, "Транспорт"); s.append(POSITION, seed.category(), T1, 4);
                if (!mode.equals("missing status")) s.append(STATUS, seed.category(), T2, "ACTIVE");
            }
            return null;
        });
        assertThrows(PersistenceFailure.class, () -> tx.snapshot(s -> category(s, seed, T1.minusSeconds(1))));
        assertThrows(PersistenceFailure.class, () -> tx.snapshot(s -> category(s, seed, null)));
    }

    @Test void repeatedValuesAndSqlLookingLabelsRemainExactBoundFacts() {
        CategorySeed seed = seedCategory(); UUID c = seed.category();
        String label = "Транспорт '); DROP TABLE budget.category; -- 🚎";
        FaultPool tracked = new FaultPool(pool);
        new JdbcTransactions(tracked).write(s -> { s.append(NAME, c, T2, label); s.append(NAME, c, T3, label); return null; });
        assertEquals(2, tracked.updates); assertEquals(0, tracked.queries);
        assertEquals(List.of("setObject", "setObject", "setString"), tracked.traces.getFirst().bindings().stream().map(FaultPool.Binding::method).toList());
        assertEquals(c, tracked.traces.getFirst().bindings().get(0).value());
        assertEquals(T2.atOffset(ZoneOffset.UTC), tracked.traces.getFirst().bindings().get(1).value());
        assertEquals(label, tracked.traces.getFirst().bindings().get(2).value());
        assertFalse(tracked.traces.getFirst().sql().contains(label));
        List<Fact<String>> history = tx.snapshot(s -> s.history(NAME, c)).value();
        assertEquals(List.of(new Fact<>(T1, "Транспорт"), new Fact<>(T2, label), new Fact<>(T3, label)), history);
        assertThrows(UnsupportedOperationException.class, () -> history.add(new Fact<>(T4, "bad")));
        assertTrue(tx.snapshot(s -> s.exists(CATEGORY, c)).value());
    }

    @ParameterizedTest @ValueSource(strings={"anchor", "static", "coordinate", "knot", "foreign key"})
    void duplicateOrInvalidGeneratedWritesFailWithDatabaseEvidence(String mode) {
        CategorySeed seed = seedCategory();
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> tx.write(s -> {
            switch (mode) {
                case "anchor" -> s.insertAnchor(CATEGORY, seed.category());
                case "static" -> s.insertStatic(OWNER, seed.category(), seed.owner());
                case "coordinate" -> s.append(NAME, seed.category(), T1, "Транспорт");
                case "knot" -> s.append(STATUS, seed.category(), T2, "UNKNOWN");
                default -> s.append(NAME, UUID.randomUUID(), T1, "Транспорт");
            }
            return null;
        }));
        assertEquals(WriteOutcome.NOT_COMMITTED, failure.outcome());
        assertEquals(mode.equals("knot") || mode.equals("foreign key") ? "23503" : "23505", failure.sqlState().orElseThrow());
    }

    @ParameterizedTest @ValueSource(ints={0, 2})
    void unexpectedAffectedRowCountPoisonsTheScopeEvenWhenCaught(int count) {
        CategorySeed seed = seedCategory(); FaultPool tracked = new FaultPool(pool); tracked.reportedUpdates = count;
        assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).write(s -> {
            assertThrows(PersistenceFailure.class, () -> s.append(NAME, seed.category(), T2, "must roll back")); return null;
        }));
        assertEquals(0, tracked.commits);
        assertEquals(1, tx.snapshot(s -> s.history(NAME, seed.category()).size()).value());
    }

    @Test void caughtNullAndPrecisionErrorsPoisonMappedScope() {
        CategorySeed seed = seedCategory();
        for (int mode = 0; mode < 3; mode++) {
            int selected = mode; FaultPool tracked = new FaultPool(pool);
            assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).write(s -> {
                assertThrows(PersistenceFailure.class, () -> {
                    if (selected == 0) s.append(NAME, seed.category(), T2, null);
                    if (selected == 1) s.asOf(NAME, seed.category(), null);
                    if (selected == 2) s.append(NAME, seed.category(), T2.plusNanos(1), "bad precision");
                }); return null;
            }));
            assertEquals(0, tracked.commits); assertEquals(0, tracked.updates); assertEquals(0, tracked.queries);
        }
    }

    @Test void generatedAndReviewedWritesRollBackTogether() {
        CategorySeed seed = seedCategory(); UUID added = UUID.randomUUID();
        assertThrows(PersistenceFailure.class, () -> tx.write(s -> {
            s.insertAnchor(CATEGORY, added); s.insertStatic(OWNER, added, seed.owner()); s.append(NAME, added, T2, "Новая");
            revision(s, seed, 2, T2); s.append(STATUS, added, T2, "INVALID"); return null;
        }));
        tx.snapshot(s -> {
            assertFalse(s.exists(CATEGORY, added)); assertTrue(s.readStatic(OWNER, added).isEmpty()); assertTrue(s.history(NAME, added).isEmpty());
            assertEquals(1L, scalar(s, "SELECT current_version FROM budget.catalog_guard WHERE owner_id=?", Codecs.BIGINT, id(seed.owner())));
            assertEquals(1L, scalar(s, "SELECT count(*) FROM budget.catalog_revision WHERE owner_id=?", Codecs.BIGINT, id(seed.owner())));
            return null;
        });
    }

    @Test void multiQueryProjectionUsesOneSnapshotAcrossConcurrentCommit() throws Exception {
        CategorySeed seed = seedCategory(); FaultPool tracked = new FaultPool(pool);
        CountDownLatch firstRead = new CountDownLatch(1), committed = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<CategoryDto> reader = executor.submit(() -> new JdbcTransactions(tracked).snapshot(s -> {
                assertFalse(s instanceof WriteSession);
                assertEquals("repeatable read", scalar(s, "SELECT current_setting('transaction_isolation')", Codecs.TEXT));
                assertEquals("on", scalar(s, "SELECT current_setting('transaction_read_only')", Codecs.TEXT));
                assertEquals(1L, scalar(s, "SELECT current_version FROM budget.catalog_guard WHERE owner_id=?", Codecs.BIGINT, id(seed.owner())));
                firstRead.countDown(); assertTrue(committed.await(5, TimeUnit.SECONDS));
                return category(s, seed, null).orElseThrow();
            }).value());
            assertTrue(firstRead.await(5, TimeUnit.SECONDS));
            try {
                tx.write(s -> { s.append(NAME, seed.category(), T2, "Новое имя"); s.append(STATUS, seed.category(), T2, "ARCHIVED"); revision(s, seed, 2, T2); return null; });
            } finally { committed.countDown(); }
            assertEquals(new CategoryDto("Транспорт", 4, "ACTIVE"), reader.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, tracked.borrows); assertEquals(0, tracked.commits);
        assertTrue(tracked.traces.stream().noneMatch(t -> t.sql().contains("FOR SHARE") || t.sql().contains("FOR UPDATE")));
        assertEquals(new CategoryDto("Новое имя", 4, "ARCHIVED"), tx.snapshot(s -> category(s, seed, null).orElseThrow()).value());
    }

    record BudgetSeed(CategorySeed category, UUID budget, UUID income, UUID investment, UUID spending) {}
    static BudgetSeed seedBudget() {
        BudgetSeed seed = new BudgetSeed(seedCategory(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UUID b = seed.budget(), owner = seed.category().owner();
        LocalDate month = LocalDate.of(2026, 11, 1);
        tx.write(s -> {
            s.insertAnchor(BUDGET, b); s.insertStatic(BUDGET_OWNER, b, owner); s.insertStatic(MONTH, b, month); s.insertStatic(CURRENCY, b, "RUB");
            execute(s, "INSERT INTO budget.budget_guard (budget_id,owner_id,month,income_line_id,investment_line_id,current_version,last_changed_at) VALUES (?,?,?,?,?,1,?)",
                    id(b), id(owner), Param.of(Codecs.DATE, month), id(seed.income()), id(seed.investment()), at(T1));
            for (UUID line : List.of(seed.income(), seed.investment(), seed.spending())) {
                String kind = line.equals(seed.income()) ? "INCOME" : line.equals(seed.investment()) ? "INVESTMENT" : "SPENDING";
                s.insertAnchor(LINE, line); s.insertTie(IN_BUDGET, line, b); s.insertStatic(KIND, line, kind);
                Optional<UUID> category = kind.equals("SPENDING") ? Optional.of(seed.category().category()) : Optional.empty();
                category.ifPresent(c -> s.insertTie(LINE_CATEGORY, line, c));
                execute(s, "INSERT INTO budget.line_key VALUES (?, ?, ?, ?, ?, ?)",
                        id(line), id(b), id(owner), text(kind), new Param<>(Codecs.UUID, category), at(T1));
                s.append(STATE, line, T1, "UNSET");
            }
            execute(s, "INSERT INTO budget.budget_revision VALUES (?, 1, ?, ?, 1)", id(b), at(T1), id(owner));
            return null;
        });
        return seed;
    }

    @Test void staticScalarKnotAndBothTieFormsAreExplicitSingleQueryLookups() {
        BudgetSeed seed = seedBudget(); FaultPool tracked = new FaultPool(pool);
        new JdbcTransactions(tracked).snapshot(s -> {
            assertEquals(LocalDate.of(2026, 11, 1), s.readStatic(MONTH, seed.budget()).orElseThrow());
            assertEquals("RUB", s.readStatic(CURRENCY, seed.budget()).orElseThrow());
            assertEquals("SPENDING", s.readStatic(KIND, seed.spending()).orElseThrow());
            assertEquals(seed.budget(), s.readTie(IN_BUDGET, seed.spending()).orElseThrow());
            assertEquals(seed.category().category(), s.readTie(LINE_CATEGORY, seed.spending()).orElseThrow());
            assertTrue(s.readTie(LINE_CATEGORY, seed.income()).isEmpty());
            return null;
        });
        assertEquals(6, tracked.queries); assertEquals(1, tracked.borrows);
        assertTrue(tracked.traces.stream().noneMatch(t -> t.sql().contains("JOIN")));
        PersistenceFailure duplicate = assertThrows(PersistenceFailure.class, () -> tx.write(s -> { s.insertTie(IN_BUDGET, seed.spending(), seed.budget()); return null; }));
        assertEquals("23505", duplicate.sqlState().orElseThrow());
        PersistenceFailure knot = assertThrows(PersistenceFailure.class, () -> tx.write(s -> { s.insertStatic(KIND, UUID.randomUUID(), "UNKNOWN"); return null; }));
        assertEquals("23503", knot.sqlState().orElseThrow());
    }

    record AmountDto(Instant changedAt, Optional<BigDecimal> amount) {}
    static Optional<AmountDto> amount(ReadSession s, UUID line, Instant time) {
        return amount(s, line, time, STATE, AMOUNT);
    }
    static Optional<AmountDto> amount(ReadSession s, UUID line, Instant time,
            Knotted<Line, UUID, String, Recorded> stateMap, Scalar<Line, UUID, BigDecimal, Recorded> amountMap) {
        Optional<Fact<String>> state = s.asOf(stateMap, line, time);
        if (state.isEmpty()) return Optional.empty();
        Fact<String> selected = state.orElseThrow();
        Optional<Fact<BigDecimal>> value = s.asOf(amountMap, line, selected.changedAt());
        if (selected.value().equals("UNSET")) {
            if (value.filter(v -> v.changedAt().equals(selected.changedAt())).isPresent()) throw new IllegalStateException("UNSET has an amount");
            return Optional.of(new AmountDto(selected.changedAt(), Optional.empty()));
        }
        if (!selected.value().equals("SET")) throw new IllegalStateException("Unknown amount state");
        Fact<BigDecimal> exact = value.filter(v -> v.changedAt().equals(selected.changedAt())).orElseThrow();
        return Optional.of(new AmountDto(selected.changedAt(), Optional.of(exact.value())));
    }
    static void set(WriteSession s, UUID line, Instant time, String value) {
        s.append(STATE, line, time, "SET"); s.append(AMOUNT, line, time, new BigDecimal(value));
    }

    @Test void amountRecurrencePreservesAbsentUnsetZeroAndExactCoordinates() {
        BudgetSeed seed = seedBudget(); UUID line = seed.income(); Instant t5 = T4.plusNanos(1000), t6 = t5.plusNanos(1000), t7 = t6.plusNanos(1000);
        tx.write(s -> {
            set(s, line, T2, "100.00"); set(s, line, T3, "150.00"); s.append(STATE, line, T4, "UNSET");
            set(s, line, t5, "150.00"); set(s, line, t6, "0.00"); set(s, line, t7, "99999999999999999.99"); return null;
        });
        tx.snapshot(s -> {
            assertTrue(amount(s, line, T1.minusNanos(1000)).isEmpty());
            assertEquals(Optional.empty(), amount(s, line, T1).orElseThrow().amount());
            assertEquals(Optional.of(new BigDecimal("100.00")), amount(s, line, T2).orElseThrow().amount());
            assertEquals(Optional.of(new BigDecimal("150.00")), amount(s, line, T3).orElseThrow().amount());
            assertEquals(Optional.empty(), amount(s, line, T4).orElseThrow().amount());
            assertEquals(Optional.of(new BigDecimal("150.00")), amount(s, line, t5).orElseThrow().amount());
            assertEquals(Optional.of(new BigDecimal("0.00")), amount(s, line, t6).orElseThrow().amount());
            assertEquals(Optional.of(new BigDecimal("99999999999999999.99")), amount(s, line, t7).orElseThrow().amount());
            assertEquals(List.of("UNSET", "SET", "SET", "UNSET", "SET", "SET", "SET"), s.history(STATE, line).stream().map(Fact::value).toList());
            assertEquals(List.of(T2, T3, t5, t6, t7), s.history(AMOUNT, line).stream().map(Fact::changedAt).toList());
            return null;
        });
        tx.write(s -> { set(s, line, t7.plusNanos(1000), "1.2"); return null; });
        assertEquals(new BigDecimal("1.20"), tx.snapshot(s -> s.current(AMOUNT, line).orElseThrow().value()).value());
    }

    @ParameterizedTest @ValueSource(booleans={true, false})
    void amountPairCommitsWithEitherGeneratedInsertionOrder(boolean amountFirst) {
        UUID line = seedBudget().investment();
        tx.write(s -> {
            if (amountFirst) s.append(AMOUNT, line, T2, BigDecimal.ZERO);
            s.append(STATE, line, T2, "SET");
            if (!amountFirst) s.append(AMOUNT, line, T2, BigDecimal.ZERO);
            return null;
        });
        assertEquals(Optional.of(new BigDecimal("0.00")), tx.snapshot(s -> amount(s, line, T2).orElseThrow().amount()).value());
    }

    @ParameterizedTest @ValueSource(strings={"missing amount", "orphan amount", "mismatched coordinates", "UNSET with amount"})
    void actualCommitRejectsBadGeneratedPairsAndRollsBackGuardRevision(String mode) {
        BudgetSeed seed = seedBudget(); UUID line = seed.income(); FaultPool tracked = new FaultPool(pool);
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).write(s -> {
            execute(s, "UPDATE budget.budget_guard SET current_version=2,last_changed_at=? WHERE budget_id=?", at(T2), id(seed.budget()));
            execute(s, "INSERT INTO budget.budget_revision VALUES (?,2,?,?,1)", id(seed.budget()), at(T2), id(seed.category().owner()));
            if (!mode.equals("orphan amount")) s.append(STATE, line, T2, mode.equals("UNSET with amount") ? "UNSET" : "SET");
            if (!mode.equals("missing amount")) s.append(AMOUNT, line, mode.equals("mismatched coordinates") ? T3 : T2, BigDecimal.ZERO);
            return "must not escape";
        }));
        assertEquals(1, tracked.commits); assertEquals(WriteOutcome.NOT_COMMITTED, failure.outcome()); assertEquals("23503", failure.sqlState().orElseThrow());
        tx.snapshot(s -> {
            assertEquals(List.of(new Fact<>(T1, "UNSET")), s.history(STATE, line)); assertTrue(s.history(AMOUNT, line).isEmpty());
            assertEquals(1L, scalar(s, "SELECT current_version FROM budget.budget_guard WHERE budget_id=?", Codecs.BIGINT, id(seed.budget())));
            assertEquals(1L, scalar(s, "SELECT count(*) FROM budget.budget_revision WHERE budget_id=?", Codecs.BIGINT, id(seed.budget())));
            return null;
        });
    }

    @ParameterizedTest @ValueSource(strings={"1.001", "1.000", "100000000000000000.00", "-0.01"})
    void mappedDecimalRejectsLossAndDatabaseRejectsNegativeBudgetAmounts(String value) {
        UUID line = seedBudget().income(); FaultPool tracked = new FaultPool(pool);
        PersistenceFailure failure = assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).write(s -> {
            s.append(AMOUNT, line, T2, new BigDecimal(value)); return null;
        }));
        assertEquals(0, tracked.commits);
        if (value.startsWith("-")) assertEquals("23514", failure.sqlState().orElseThrow()); else assertEquals(0, tracked.updates);
        assertTrue(tx.snapshot(s -> s.history(AMOUNT, line)).value().isEmpty());
    }

    @Test void corruptFixturesCannotTurnNullOrMissingCompanionsIntoValidProjections() throws Exception {
        // Deliberately unconstrained, isolated fixtures: V1 correctly forbids these corrupt states.
        Identifier schema = name("corrupt_" + UUID.randomUUID().toString().replace("-", ""));
        Table stateTable = new Table(schema, name("state")), amountTable = new Table(schema, name("amount"));
        var stateMap = new Knotted<>(LINE, stateTable, LINE_ID, STATE.value(), STATE.knot(), RECORDED);
        var amountMap = new Scalar<>(LINE, amountTable, LINE_ID, AMOUNT.value(), RECORDED);
        try (Connection connection = pool.getConnection(); Statement ddl = connection.createStatement()) {
            ddl.execute("CREATE SCHEMA " + schema.quoted());
            try {
                ddl.execute("CREATE TABLE " + stateTable.quoted() + " (line_id uuid, changed_at timestamptz(6), state_code text)");
                ddl.execute("CREATE TABLE " + amountTable.quoted() + " (line_id uuid, changed_at timestamptz(6), amount numeric(19,2))");
                for (String mode : List.of("missing", "older", "UNSET with amount", "unknown code", "NULL state", "NULL amount")) {
                    UUID line = UUID.randomUUID();
                    tx.write(s -> {
                        Optional<String> code = mode.equals("NULL state") ? Optional.empty()
                                : Optional.of(mode.equals("unknown code") ? "UNKNOWN" : mode.equals("UNSET with amount") ? "UNSET" : "SET");
                        execute(s, AnchorSql.insert(stateTable, LINE_ID, RECORDED.changedAt(), STATE.value()).text(),
                                id(line), at(T3), new Param<>(Codecs.TEXT, code));
                        if (Set.of("older", "UNSET with amount", "NULL amount").contains(mode))
                            execute(s, AnchorSql.insert(amountTable, LINE_ID, RECORDED.changedAt(), AMOUNT.value()).text(),
                                    id(line), at(mode.equals("older") ? T2 : T3),
                                    new Param<>(AMOUNT.value().codec(), mode.equals("NULL amount") ? Optional.empty() : Optional.of(BigDecimal.ZERO)));
                        return null;
                    });
                    assertThrows(PersistenceFailure.class, () -> tx.snapshot(s -> amount(s, line, T3, stateMap, amountMap)), mode);
                }
                PersistenceFailure nullable = assertThrows(PersistenceFailure.class, () -> tx.snapshot(s -> {
                    MappingSchema.verify(s, List.of(amountMap)); return null;
                }));
                assertTrue(nullable.getCause().getMessage().contains("Schema column mismatch"));
                ddl.execute("DELETE FROM " + stateTable.quoted() + " WHERE state_code IS NULL");
                ddl.execute("ALTER TABLE " + stateTable.quoted() + " ALTER COLUMN line_id SET NOT NULL");
                ddl.execute("ALTER TABLE " + stateTable.quoted() + " ALTER COLUMN state_code SET NOT NULL");
                ddl.execute("ALTER TABLE " + stateTable.quoted() + " ALTER COLUMN changed_at SET NOT NULL");
                ddl.execute("ALTER TABLE " + stateTable.quoted() + " ALTER COLUMN changed_at TYPE timestamptz(3)");
                PersistenceFailure precision = assertThrows(PersistenceFailure.class, () -> tx.snapshot(s -> {
                    MappingSchema.verify(s, List.of(stateMap)); return null;
                }));
                assertTrue(precision.getCause().getMessage().endsWith(".changed_at"));
            } finally { ddl.execute("DROP SCHEMA " + schema.quoted() + " CASCADE"); }
        }
    }

    @Test void mappedReadFacadeRetainsScopeAndThreadChecks() throws Exception {
        CategorySeed seed = seedCategory(); AtomicReference<ReadSession> escaped = new AtomicReference<>();
        tx.snapshot(s -> { escaped.set(s); return s.current(NAME, seed.category()); });
        assertThrows(PersistenceFailure.class, () -> escaped.get().current(NAME, seed.category()));
        FaultPool tracked = new FaultPool(pool);
        assertThrows(PersistenceFailure.class, () -> new JdbcTransactions(tracked).snapshot(s -> {
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> assertThrows(PersistenceFailure.class, () -> s.history(NAME, seed.category()))).get(5, TimeUnit.SECONDS);
            } return null;
        }));
        assertEquals(0, tracked.queries); assertEquals(1, tracked.borrows);
    }
}
