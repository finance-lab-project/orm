# Anchor mappings and projections

Scope: [issue #3](https://github.com/finance-lab-project/orm/issues/3), following
the accepted [API contract](api-contract.md). Package: `org.financelab.orm`.
The module supplies `Anchor`, `Scalar`, `Knotted`, `Knot` and `StaticTie`
descriptors explicitly. Its domain classes need no ORM imports or annotations.
`Static` and `Recorded` distinguish permitted operations at compile time;
anchor marker types distinguish identities with the same Java representation.

## Use

Synthetic Category mapping, owned by a business module's persistence adapter:

```java
final class CategoryRow {}
var categoryId = new Column<>(new Identifier("category_id"), Codecs.UUID);
var category = new Anchor<CategoryRow, UUID>(
    new Table(new Identifier("budget"), new Identifier("category")), categoryId);
var name = new Scalar<>(category,
    new Table(new Identifier("budget"), new Identifier("category_name")), categoryId,
    new Column<>(new Identifier("name"), Codecs.TEXT),
    new Recorded(new Column<>(new Identifier("changed_at"), Codecs.INSTANT)));

// After module migrations, explicitly verify the full module descriptor list.
transactions.snapshot(s -> {
    MappingSchema.verify(s, List.of(category, name));
    return null;
});

// Within a complete module command, with its caller-selected recording coordinate:
transactions.write(s -> {
    s.append(name, existingCategoryId, changedAt, "Транспорт и такси");
    // The adapter also updates its guard/revision here using reviewed SQL.
    return null;
});
Optional<Fact<String>> historical = transactions.snapshot(
    s -> s.asOf(name, existingCategoryId, requestedCoordinate)).value();
```

The existing identity and recording coordinate come from the adapter. Creation
also explicitly inserts the anchor, static owner and every required initial
fact, plus the module's guard/revision companions. There are no cascades.
The complete synthetic Category and Budget descriptors live in
`src/test/java/org/financelab/orm/BudgetMappings.java`.

`MappingSchema.verify(session, mappings)` accepts only explicit descriptors,
also checking their referenced anchors/knots. Conflicting definitions of one
qualified table fail before any catalog queries. Verification checks mapped
columns' PostgreSQL types/precision/scale, NOT NULL, lack of generated/identity
expressions, ordered primary keys and declared anchor/knot/tie foreign keys.
Unmapped generated constraint helpers are permitted. Operational support-table
constraints and undeclared scalar references remain migration/module concerns;
integration tests additionally inspect the owner FK and deferred amount-pair
helpers from Budget V1. Verification never creates or repairs schema, runs no
discovery and is not repeated implicitly by each operation.

## SQL and read behavior

`insertAnchor`, `insertStatic`, `insertTie` and `append` each generate one
parameterized INSERT and require exactly one affected row. Identifiers are
validated individual ASCII tokens and quoted separately. All identities,
values, knot codes and coordinates are bound through their exact codecs.
Knot operations store/read the code directly, without lookup, joins or inserts
into the knot table. Database constraint errors retain their SQLSTATE/outcome.
Null input, excess precision and unexpected row counts poison the scope even
when caught by a callback. Equal facts at different coordinates remain separate
rows; duplicates at the same key fail.

Each read primitive issues one statement, eagerly materializes its results and
uses the callback's connection. `exists` checks identity; `readStatic` reads an
immutable value; `readTie` returns only the target identity. `current` selects
the greatest coordinate, `asOf` the greatest coordinate `<= at`, and `history`
every fact in ascending order. Missing rows return absence; a present NULL in
any mapped required column is an integrity failure. History lists are immutable.

The business adapter assembles explicit DTOs inside `snapshot`, which provides
one read-only REPEATABLE READ transaction. It decides authorization, required
fields and entity creation rules. A timeless owner does not make a Category
visible before its common initial name/position/status coordinate. Budget
selects a catalog revision before projecting pinned history. Amount state has
three distinct outcomes: no state means absent; UNSET means empty; SET requires
the amount at exactly that state's coordinate, including zero. An older amount
cannot fill a missing companion. These policies are demonstrated in test
adapters, rather than embedded as business defaults in the mapper.

## Verification

Run with the [pinned runtime/toolchain](jdbc-runtime.md#build-and-verification):

```sh
mvn -B -ntp test -Dsource.revision="$(git rev-parse HEAD)"
scripts/verify.sh
git diff --check
```

`MappingTest` checks SQL shape, identifier/descriptor invariants and actual
compiler rejection of static/recorded, anchor-marker and value-type misuse.
`AnchorMappingIT` uses the checksum-pinned Budget V1 migration on PostgreSQL
18.6 for metadata agreement/mismatch, Category creation/rename/archive and
before-creation gaps, exact bound Unicode/SQL-looking labels, repeated values,
static ties/knots, amount recurrence/zero/decimal boundaries, actual commit-time
pair rejection with guard/revision rollback, and concurrent snapshot reads.
Deliberately corrupt rows use a separate disposable fixture schema to test
NULL, unknown state and missing/older companion rejection; V1 itself forbids
those states. This does not change Budget's migration.

Representative observed query counts are explicit assertions: each primitive
uses one statement; the test Category DTO uses seven reads (owner, three
initial-history checks, three selected facts) on one borrowed connection.
This simple test adapter is not a production Budget query plan. No performance
claim, startup DDL, business no-op rule, category command implementation or
historized tie support is introduced.
