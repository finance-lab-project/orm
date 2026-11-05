package org.financelab.orm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Synthetic module-owned descriptors; production Budget mappings belong in its adapter. */
final class BudgetMappings {
    static final class Category {}
    static final class Budget {}
    static final class Line {}
    static Identifier name(String value) { return new Identifier(value); }
    static Table table(String value) { return new Table(name("budget"), name(value)); }
    static <V> Column<V> column(String value, Codec<V> codec) { return new Column<>(name(value), codec); }
    static final Column<UUID> CATEGORY_ID = column("category_id", Codecs.UUID);
    static final Column<UUID> BUDGET_ID = column("budget_id", Codecs.UUID);
    static final Column<UUID> LINE_ID = column("line_id", Codecs.UUID);
    static final Anchor<Category, UUID> CATEGORY = new Anchor<>(table("category"), CATEGORY_ID);
    static final Anchor<Budget, UUID> BUDGET = new Anchor<>(table("budget"), BUDGET_ID);
    static final Anchor<Line, UUID> LINE = new Anchor<>(table("budget_line"), LINE_ID);
    static final Static STATIC = new Static();
    static final Recorded RECORDED = new Recorded(column("changed_at", Codecs.INSTANT));
    static final Scalar<Category, UUID, UUID, Static> OWNER = new Scalar<>(CATEGORY, table("category_owner"),
            CATEGORY_ID, column("owner_id", Codecs.UUID), STATIC);
    static final Scalar<Category, UUID, String, Recorded> NAME = new Scalar<>(CATEGORY, table("category_name"),
            CATEGORY_ID, column("name", Codecs.TEXT), RECORDED);
    static final Scalar<Category, UUID, Integer, Recorded> POSITION = new Scalar<>(CATEGORY, table("category_position"),
            CATEGORY_ID, column("position", Codecs.INTEGER), RECORDED);
    static Knot<String> knot(String table) { return new Knot<>(table(table), column("code", Codecs.TEXT)); }
    static final Knotted<Category, UUID, String, Recorded> STATUS = new Knotted<>(CATEGORY, table("category_status_history"),
            CATEGORY_ID, column("status_code", Codecs.TEXT), knot("category_status"), RECORDED);
    static final Scalar<Budget, UUID, UUID, Static> BUDGET_OWNER = new Scalar<>(BUDGET, table("budget_owner"),
            BUDGET_ID, column("owner_id", Codecs.UUID), STATIC);
    static final Scalar<Budget, UUID, LocalDate, Static> MONTH = new Scalar<>(BUDGET, table("budget_month"),
            BUDGET_ID, column("month", Codecs.DATE), STATIC);
    static final Knotted<Budget, UUID, String, Static> CURRENCY = new Knotted<>(BUDGET, table("budget_currency"),
            BUDGET_ID, column("currency_code", Codecs.TEXT), knot("currency"), STATIC);
    static final StaticTie<Line, UUID, Budget, UUID> IN_BUDGET = new StaticTie<>(LINE, BUDGET, table("line_in_budget"), LINE_ID, BUDGET_ID);
    static final StaticTie<Line, UUID, Category, UUID> LINE_CATEGORY = new StaticTie<>(LINE, CATEGORY, table("line_category"), LINE_ID, CATEGORY_ID);
    static final Knotted<Line, UUID, String, Static> KIND = new Knotted<>(LINE, table("line_kind_attribute"),
            LINE_ID, column("kind_code", Codecs.TEXT), knot("line_kind"), STATIC);
    static final Knotted<Line, UUID, String, Recorded> STATE = new Knotted<>(LINE, table("line_amount_state"),
            LINE_ID, column("state_code", Codecs.TEXT), knot("amount_state"), RECORDED);
    static final Scalar<Line, UUID, BigDecimal, Recorded> AMOUNT = new Scalar<>(LINE, table("line_amount"),
            LINE_ID, column("amount", Codecs.decimal(19, 2)), RECORDED);
    static final List<?> ALL = List.of(OWNER, NAME, POSITION, STATUS, BUDGET_OWNER, MONTH, CURRENCY, IN_BUDGET, LINE_CATEGORY, KIND, STATE, AMOUNT);
}
