# rules

**Purpose.** The custom-rule DSL (E.6, FR-05-05): the JSON AST of the OpenAPI `RuleExpression`
(`all`, `any`, `not`, and `eq ne gt gte lt lte in not_in between is_null is_not_null`) parsed,
validated completely with the API's error codes and paths, and compiled to thread-safe predicates.
Comparisons with a missing value are UNKNOWN (Kleene logic) and a rule fires only on TRUE (ADR 0061).

**Boundaries.** `rules.dsl` is framework-free; `rules.json` reads the AST with Jackson. Fields are
request fields and the 44 registered features; `FieldCatalogueDriftTest` compares the list with the
Python registry through `uv` and is reported skipped when `uv` is absent.

**Test.** `../mvnw -pl rules verify`.
