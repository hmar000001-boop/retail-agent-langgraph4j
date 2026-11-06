# Phase 1 — Domain and application contracts

## Scope delivered

Added a framework-independent typed contract layer under `com.example.retail.agent` in `retail-agent-core`:

- `domain.Evidence`: sourced claim with authority, scope, observation time, optional expiry and provenance.
- `domain.Observation`: timestamped event with explicit trust classification and evidence references.
- `domain.ActionDefinition`: allow-listed action declaration with kind, risk, effects, preconditions, timeout, idempotency and impact flags. External, high/critical-risk, destructive, irreversible, financial and externally shared actions require human approval by default.
- `domain.ActionInvocation`: immutable invocation bound to a subject, intention revision and idempotency key.
- `domain.ActionOutcome`: typed outcome that distinguishes execution success from verified effects.
- `domain.Plan`: versioned plan and typed steps with duplicate, unknown dependency, self-dependency and cycle validation.
- `domain.Decision`: auditable choice with evidence references and explicit authority.
- `port.EvidenceRepository`, `ActionRegistry`, `ActionGateway`, and `DecisionRepository`: framework-neutral boundaries for persistence, allow-listed actions, execution and audit.
- Unit tests cover freshness, authority-sensitive action approval, immutable invocation arguments, outcome verification, plan graph validation and untrusted observations.

## Design constraints

- These are domain contracts, not an assertion that the existing runtime already uses them. Runtime migration is deliberately staged for Phase 2 onward.
- Model-generated content cannot declare new executable actions; execution is resolved through the allow-listed `ActionRegistry` and invoked through `ActionGateway`.
- A successful tool call is not equivalent to a verified business effect.
- Evidence freshness is evaluated against an explicit instant and must be considered by the consuming policy.
- The authenticated subject is an explicit invocation field. API authentication and trusted subject derivation are Phase 8 responsibilities; the model must never be the authority for that value.
- `retail-agent-langchain4j` remains unchanged and experimental.

## Verification status

- All `retail-agent-core` main sources compile with `javac --release 21`.
- POM XML and shell wrapper static validation passed in Phase 0.
- JUnit tests were added but the Maven test suite could not be run in this environment (Maven unavailable and outbound dependency downloads blocked). CI must run the complete reactor.

## Deliberately not claimed as complete

- Existing LangGraph4j and Agentic runtime paths have not yet been migrated to these contracts.
- There is no durable repository implementation, authorization service, approval workflow, transaction boundary or audit backend yet.
- Plan validation currently enforces dependency graph integrity; scheduling, concurrency and runtime precondition evaluation are later orchestration concerns.

## Next phase

Phase 2: migrate the primary LangGraph4j runtime to typed state and nodes, introduce the LangChain4j model/tool/RAG adapter on that path, and build modular subgraphs with explicit reducers and checkpoint/interrupt semantics. The Agentic comparison module remains frozen.
