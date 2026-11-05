# Finance Lab ORM

Custom PostgreSQL Anchor mapper: explicit Java mapping definitions, JDBC transactions, exact codecs, SQL generation and current/historical projections.

First-release design and delivery work is tracked in this repository.

The canonical [architecture](https://github.com/finance-lab-project/app/blob/main/docs/architecture.md) and [release plan](https://github.com/finance-lab-project/app/blob/main/docs/roadmap.md) live in `app`. Track this module's work in [its issues](https://github.com/finance-lab-project/orm/issues) and its private project board.

Java without Spring. Business modules own their domain, application API, Vaadin adapters, persistence mappings and reviewed SQL migrations. The ORM is a library; business rules and aggregate revisions remain with the business module. Root assembly pins immutable module versions.

The [proposed API contract](docs/api-contract.md) and [contract-test brief](docs/contract-tests.md) cover the design in [issue #1](https://github.com/finance-lab-project/orm/issues/1). They require acceptance before runtime implementation; no runnable mapper is claimed. Separate learning issues are not part of this backlog. Use synthetic examples only; no real financial data, employer material, secrets or local paths.
