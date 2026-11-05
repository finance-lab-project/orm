package org.financelab.orm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.financelab.orm.BudgetMappings.*;
import static org.junit.jupiter.api.Assertions.*;

class MappingTest {
    @Test void identifiersAndDescriptorRolesAreValidated() {
        assertEquals("\"budget\".\"category_name\"", NAME.table().quoted());
        assertDoesNotThrow(() -> name("a".repeat(63)));
        for (String invalid : List.of("", "a".repeat(64), "budget.name", "Name", " name", "name\"", "x--", "x/*y*/", "x()", "имя"))
            assertThrows(IllegalArgumentException.class, () -> name(invalid), invalid);
        assertThrows(NullPointerException.class, () -> new Anchor<>(null, CATEGORY_ID));
        assertThrows(NullPointerException.class, () -> new Scalar<>(CATEGORY, table("x"), CATEGORY_ID, null, STATIC));
        assertThrows(IllegalArgumentException.class, () -> new StaticTie<>(CATEGORY, CATEGORY, table("x"), CATEGORY_ID, CATEGORY_ID));
        assertThrows(IllegalArgumentException.class, () -> new Scalar<>(CATEGORY, table("x"), CATEGORY_ID, CATEGORY_ID, STATIC));
        assertThrows(IllegalArgumentException.class, () -> new Scalar<>(CATEGORY, table("x"), CATEGORY_ID,
                column("changed_at", Codecs.TEXT), RECORDED));
        assertThrows(IllegalArgumentException.class, () -> MappingChecks.compatible(Codecs.TEXT, Codecs.INTEGER));
        assertThrows(IllegalArgumentException.class, () -> MappingChecks.compatible(Codecs.decimal(19, 2), Codecs.decimal(18, 2)));
    }

    @Test void generatedSqlHasOnlyQuotedIdentifiersAndPositionalParameters() {
        assertEquals("INSERT INTO \"budget\".\"category\" (\"category_id\") VALUES (?)", AnchorSql.insert(CATEGORY.table(), CATEGORY.id()).text());
        assertEquals("INSERT INTO \"budget\".\"category_owner\" (\"category_id\", \"owner_id\") VALUES (?, ?)",
                AnchorSql.insert(OWNER.table(), OWNER.id(), OWNER.value()).text());
        assertEquals("INSERT INTO \"budget\".\"category_name\" (\"category_id\", \"changed_at\", \"name\") VALUES (?, ?, ?)",
                AnchorSql.insert(NAME.table(), NAME.id(), RECORDED.changedAt(), NAME.value()).text());
        assertEquals("SELECT \"changed_at\", \"name\" FROM \"budget\".\"category_name\" WHERE \"category_id\" = ? AND \"changed_at\" <= ? ORDER BY \"changed_at\" DESC LIMIT 1",
                AnchorSql.facts(NAME.table(), NAME.id(), NAME.value(), RECORDED, true, false).text());
        assertEquals("SELECT \"changed_at\", \"status_code\" FROM \"budget\".\"category_status_history\" WHERE \"category_id\" = ? ORDER BY \"changed_at\" DESC LIMIT 1",
                AnchorSql.facts(STATUS.table(), STATUS.id(), STATUS.value(), RECORDED, false, false).text());
        assertTrue(AnchorSql.facts(NAME.table(), NAME.id(), NAME.value(), RECORDED, false, true).text().endsWith("ORDER BY \"changed_at\" ASC"));
        assertEquals("SELECT \"budget_id\" FROM \"budget\".\"line_in_budget\" WHERE \"line_id\" = ?",
                AnchorSql.lookup(IN_BUDGET.table(), IN_BUDGET.sourceId(), IN_BUDGET.targetId()).text());
    }

    @TempDir Path compilation;
    @Test void javaCompilerRejectsUnsupportedMappingOperations() throws Exception {
        String declarations = """
                import org.financelab.orm.*;
                import java.util.UUID;
                import java.time.Instant;
                class Usage {
                  static class Category {}
                  static class Line {}
                  void use(WriteSession s, Scalar<Category, UUID, String, Static> stat,
                    Scalar<Category, UUID, String, Recorded> recorded, Anchor<Line, UUID> line) {
                """;
        assertTrue(compile(declarations + "s.append(recorded, UUID.randomUUID(), Instant.EPOCH, \"ok\"); s.insertStatic(stat, UUID.randomUUID(), \"ok\"); }}"));
        for (String misuse : List.of(
                "s.append(stat, UUID.randomUUID(), Instant.EPOCH, \"bad\");",
                "s.insertStatic(recorded, UUID.randomUUID(), \"bad\");",
                "Scalar<Category, UUID, String, Static> bad = new Scalar<>(line, stat.table(), stat.id(), stat.value(), stat.time());",
                "s.append(recorded, UUID.randomUUID(), Instant.EPOCH, java.math.BigDecimal.ZERO);"))
            assertFalse(compile(declarations + misuse + "}}"), misuse);
    }
    private boolean compile(String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///Usage.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            return compiler.getTask(null, files, new DiagnosticCollector<>(),
                    List.of("-classpath", System.getProperty("java.class.path"), "-d", compilation.toString(), "-proc:none"),
                    null, List.of(unit)).call();
        }
    }
}
