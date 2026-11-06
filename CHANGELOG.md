# Changelog

## Phase 3 — CoALA memory and evidence boundaries

- Added framework-neutral typed working, episodic, semantic and procedural memory records, explicit lifecycle transitions, provenance, validity/retention and bounded working-memory snapshots.
- Added per-memory usage and evidence-backed outcome-attribution records. Workspace exposure is distinct from downstream use; task-level success no longer updates every admitted memory's utility score.
- Added subject-scoped repository ports and in-memory adapters, plus PostgreSQL JDBC repositories and `V003__coala_memory.sql` for evidence, four memory dimensions, memory use and attribution.
- Added evidence existence/freshness/authority checks for verified episodes, semantic activation, procedure validation and causal attribution. Evidence IDs are immutable within a subject scope.
- Added replay-safe, idempotent memory-use, episodic append, evidence insertion and per-use attribution recording; conflicting ID reuse is rejected and memory kind is included in deterministic use identity.
- Made procedure validation evidence-event based (no count inflation on replay); a newly verified failure archives an active reusable procedure rather than leaving it silently selectable.
- Added compare-and-set updates for episodic, semantic and procedural state changes, bounded procedure-validation retry, and row locking for PostgreSQL semantic replacement.
- Added PostgreSQL schema checks for lifecycle/outcome/enumeration values, confidence, validity/expiry intervals and validation counts; semantic matching now uses literal substring matching consistent with the in-memory adapter.
- Wired four independent retrieval branches (episodic, semantic, validated procedural and catalogue) into the primary LangGraph4j graph with separate state channels.
- Added core lifecycle, subject isolation, evidence, optimistic concurrency, idempotency and attribution tests; documented limitations and PostgreSQL setup in `docs/COALA_MEMORY.md`.
- Kept `retail-agent-langchain4j/` byte-for-byte unchanged as the experimental Agentic comparison module.

## Phase 2 — Primary LangGraph4j runtime

- Split orchestration into the application facade, graph topology/thread lifecycle, and graph node operations.
- Added an inner planning subgraph and parallel retrieval fan-out/fan-in with distinct channels.
- Made LangChain4j the native asynchronous production chat integration for the primary graph; retained Spring AI for the existing vector-store adapter and simulator compatibility.
- Preserved opt-in cassette recording/replay and scripted/fault-injection execution.
- Added memory and PostgreSQL checkpoint saver configuration; production profile requires PostgreSQL.
- Added checkpointed human approval interrupt/resume with authenticated-subject binding, a typed exact-action approval request, approval-ID-bound resume, pending-state checks, execution-time action snapshot re-validation and simulator gateway defence in depth.
- Added approval/lifecycle/cassette tests and rejected non-finite uncertainty probabilities.
- Strengthened the authority contract so every non-READ effect requires approval by default; empty/unspecified effects are rejected instead of being treated as read-only; added immediate execution-boundary registry revalidation.
- Added an end-to-end graph test for interrupt, owner-bound checkpoint update, resume and denial, plus simulator idempotency collision/retry tests.
- Made simulator idempotency atomic per key, used a canonical length-prefixed SHA-256 request fingerprint, rejected key reuse with a different payload, and rebound cached outcomes to the current retry invocation ID.
- Corrected the checkpoint YAML property nesting and removed stale Spring AI real-chat wiring.

## Unreleased

- Fixed `CoreMechanismTest.adaptiveMemoryRefreshPreservesOutcomeLearnedActivationAndValue` to compare outcome-learned floating-point values with a `1e-9` tolerance instead of exact decimal equality. The production adaptive-memory policy is unchanged; the observed `0.7799999999999999` value is the normal IEEE-754 representation of the expected `0.78` result.
- Fixed LangGraph4j Spring binding for `AgentSettings` by keeping its canonical record constructor unambiguous; updated the hand-built test fixture to use the full canonical settings tuple.
- Made repeated LangGraph4j node visits observable by annotating trace events with outer-cycle and planning-round coordinates. This distinguishes an actual second retrieve/propose/evaluate/select pass from deduplicated identical trace labels.
- Corrected the total-provider-outage test to assert that the fallback recommendation matches a positively verified stock observation, rather than requiring `p-103` for a generic trail-running query that does not require waterproofing.

## CI dependency resolution correction

- Pin LangChain4j core and OpenAI integration to `1.22.0`.
- Pin the separately versioned experimental `langchain4j-agentic` module to `1.22.0-beta32`; the Agentic artifact does not publish a `1.23.0` release in Maven Central.

- Added an explicit LangChain4j `propose` trace event around the actual `ProposalAgent` invocation. The model-call reservation remains a separate `budget:model:proposal` event, so resource governance is not confused with proposal execution. Strengthened the runtime test to require exactly two proposal events across its two decision cycles.
- Updated verification documentation to reflect the latest CI run: core 9/9 and LangGraph4j 18/18 passed; LangChain4j compiled and ran 4 tests with one trace-assertion failure, now addressed in source and awaiting a fresh CI run.
