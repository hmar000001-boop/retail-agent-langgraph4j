# CoALA memory model in this workspace (Phase 3)

This document describes the concrete implementation in the shared core and primary LangGraph4j runtime. It separates what the memory APIs guarantee from what remains an experiment or a later phase.

## 1. Memory is not one undifferentiated chat-history list

| CoALA memory dimension | Phase 3 implementation | Authoritative boundary |
|---|---|---|
| Working | `RetailState` is the active graph working memory; LangGraph4j checkpoints persist it. `WorkingMemorySnapshot` is a separate bounded, subject/execution-scoped snapshot API. | The compiled graph state/checkpointer is authoritative during a graph run. |
| Episodic | `EpisodicMemoryRecord` stores one event, goal, summary, outcome classification, evidence references, provenance, lifecycle and retention. | `EpisodicMemoryRepository` / `CoalaMemoryService`. |
| Semantic | `SemanticMemoryRecord` stores a scoped subject-predicate-value fact with confidence, evidence, provenance, validity interval, lifecycle and optional supersession link. | `SemanticMemoryRepository` / `CoalaMemoryService`. |
| Procedural | `ProceduralMemoryRecord` stores a candidate reusable method: applicability tags, preconditions, steps, postconditions and success/failure validation histories. | `ProceduralMemoryRepository` / `CoalaMemoryService`. |

The product catalogue and order history remain systems of record in this retail example. They are not automatically relabelled as personal semantic memory. Catalogue similarity search remains candidate retrieval only; a live catalogue/tool observation is needed for authoritative price and stock.

## 2. Lifecycle and promotion rules

`MemoryLifecycle` defines explicit transitions. Candidate memory is not retrieved as active memory merely because it was stored. Active episodic records are filtered by retention. Active semantic facts must have evidence references. Semantic activation checks that each evidence identifier exists in the same subject scope, is fresh at the activation instant, and is not model-generated or unverified. Replacing a semantic fact changes the old active fact to `SUPERSEDED` and writes the replacement in a transaction in PostgreSQL.

Procedures start as `CANDIDATE`. A reusable procedure becomes active only after the configured threshold (default: two) of distinct successful validations, each backed by system-of-record or verified-source evidence that exists in the same subject scope and is fresh at validation time. Replaying the same validation evidence ID is idempotent and does not inflate the count; one evidence event cannot be both a success and a failure. Failed validation references are retained separately from successful validation references. A newly verified failure against an active procedure archives it so it cannot continue to be selected silently; a replacement candidate must be proposed and validated. A count of successful executions without distinct supporting evidence is insufficient.

The lifecycle is deliberately conservative. Automatic consolidation, contradiction resolution across multiple candidate facts, and policy-driven forgetting are not yet implemented. Callers must make lifecycle decisions through the service/repository boundary rather than by mutating stored records.

## 3. Evidence is a separate store

`Evidence` is not the memory record itself. It carries source, authority, scope, observation time, optional expiry and provenance. `EvidenceRepository` is subject-scoped and has its own PostgreSQL table. Memory activation and causal outcome attribution validate references against this repository; the presence of a non-empty string ID alone is not sufficient. User-declared evidence may establish a personal semantic fact (for example, a declared preference), but verified episode outcomes, procedure validations and causal outcome attribution require `SYSTEM_OF_RECORD` or `VERIFIED_SOURCE` authority.

Evidence identifiers are immutable within a subject scope: an exact replay is accepted, while attempting to replace the payload under an existing ID is rejected. Evidence authority values `UNVERIFIED_SOURCE` and `MODEL_GENERATED` cannot activate a semantic fact or establish a successful/contradicted causal attribution. User-declared, system-of-record and verified-source evidence can be considered, subject to freshness and scope. The application still needs domain-specific rules to decide whether a given allowed authority is sufficient for a particular predicate; the generic memory service does not claim that all evidence is equally authoritative for all claims.

## 4. Use and outcome attribution

`MemoryUseRecord` records one memory ID, its owner, execution, downstream consumer, stage and time. Phase 3 records `ADMITTED_TO_WORKSPACE` for actual CoALA memories admitted to the LangGraph workspace. This is an exposure event, not proof that the model relied on the memory.

`MemoryAttributionRecord` is separate and per use. It can classify a use as `SUPPORTED`, `CONTRADICTED`, `IRRELEVANT` or `UNDETERMINED`. `SUPPORTED` and `CONTRADICTED` require fresh, subject-scoped evidence. A causal attribution is rejected if the use stage was only `RETRIEVED` or `ADMITTED_TO_WORKSPACE`; a memory must have been explicitly cited as evidence or recorded as used in a plan/action. A use can be attributed once. Replaying the same usage event ID is idempotent when all logical fields match; its first recorded timestamp is retained. Reusing that ID for a different memory, stage, consumer, execution or owner is rejected. Episodic event IDs follow the same append-once rule: replay retains the original observation and retention timestamps, while conflicting content under the same ID is rejected.

**Important:** an overall successful task does not make every retrieved or admitted memory useful. Phase 3 removes this blanket update from the primary LangGraph learning step. Admission scoring is not updated from an un-attributed task-level result. A future learning policy (Phase 6) can consume verified, per-item attribution records.

## 5. Persistence and scope

The primary runtime selects one `CoalaMemoryRepository` implementation and one `EvidenceRepository` implementation:

- `InMemoryCoalaMemoryRepository` + `InMemoryEvidenceRepository`: local development and tests only.
- `JdbcCoalaMemoryRepository` + `JdbcEvidenceRepository`: PostgreSQL-backed production candidate.

Schema: `retail-agent-langgraph4j/src/main/resources/db/migration/V003__coala_memory.sql`.

Configuration:

```yaml
app:
  memory:
    backend: postgres
    initialize-schema: true # set false when schema is applied by a controlled deployment process
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/langgraph4j
```

Equivalent environment variables are `APP_MEMORY_BACKEND`, `APP_MEMORY_INITIALIZE_SCHEMA`, `APP_MEMORY_JDBC_URL`, `APP_MEMORY_JDBC_USERNAME` and `APP_MEMORY_JDBC_PASSWORD`. The `production` profile fails startup unless `app.memory.backend=postgres`.

Every memory/evidence read and write uses the owning `subject_id`; caller identity must be authenticated by the API boundary before a subject ID reaches the service. This repository still has no production API/authentication layer, so direct in-process callers must not treat arbitrary caller-supplied IDs as authorization.

The PostgreSQL stores are separate from LangGraph's checkpoint tables. Updates to episodic, semantic and procedural records use `updated_at` compare-and-set checks; semantic replacement locks the superseded row inside a transaction. The migration applies database checks for enum values, validity intervals, evidence expiry and nonnegative validation counts. Configure both `app.memory.backend=postgres` and `app.graph.checkpoint.backend=postgres`; sharing a database is supported but not required. `WorkingMemorySnapshot` is a separate bounded API and is not a second copy of the graph's active state.

## 6. Runtime integration

The primary graph fans out four independent retrieval nodes:

```text
begin_retrieval
  ├── episodic memory
  ├── semantic memory
  ├── validated procedural memory
  └── catalogue candidate search
             ↓
       workspace admission
             ↓
           planning
```

Each branch writes to a separate `RetailState` field to avoid parallel writes to one last-value channel. The `RetailState` retrieval merge combines all four lists before workspace admission. Provenance/evidence references for semantic and procedural memory are included in the candidate content presented to planning.

The compatibility `EpisodicMemory` class remains for the pre-existing tests and the separate experimental Agentic module. The primary Spring runtime uses `CoalaMemoryService`. The `retail-agent-langchain4j` module was intentionally not modified in Phase 3.

## 7. Explicit limitations

- PostgreSQL JDBC implementation and schema have not been runtime-tested against a real PostgreSQL server in this environment.
- Evidence is now persisted, but the project still needs business-specific evidence-authority policies for each fact category.
- Semantic search is currently bounded text matching over stored subject/predicate/value fields; it is not hybrid/vector retrieval. Phase 8's retrieval work remains separate.
- Candidate consolidation and contradiction detection are explicit service operations, not autonomous background jobs.
- No memory deletion/export user interface, consent workflow, encryption-at-rest policy, or retention scheduler is delivered here. The `retentionUntil` field filters episodic recall; physical deletion remains a production-hardening task.
- The experimental LangChain4j Agentic module retains the original in-memory episodic memory and is not migrated to this Phase 3 store.
