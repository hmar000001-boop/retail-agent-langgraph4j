-- Evidence is separate from memory and is looked up under the same owning subject scope.
CREATE TABLE IF NOT EXISTS coala_evidence (
    subject_id TEXT NOT NULL, evidence_id TEXT NOT NULL, evidence_subject TEXT NOT NULL, predicate TEXT NOT NULL,
    evidence_value TEXT NOT NULL, source TEXT NOT NULL, authority TEXT NOT NULL, evidence_scope TEXT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NULL, provenance TEXT NOT NULL,
    PRIMARY KEY(subject_id, evidence_id),
    CHECK (source IN ('USER','FIRST_PARTY_SYSTEM','EXTERNAL_SYSTEM','RETRIEVED_DOCUMENT','MODEL_INFERENCE')),
    CHECK (authority IN ('USER_DECLARED','SYSTEM_OF_RECORD','VERIFIED_SOURCE','UNVERIFIED_SOURCE','MODEL_GENERATED')),
    CHECK (expires_at IS NULL OR expires_at >= observed_at)
);
CREATE INDEX IF NOT EXISTS ix_coala_evidence_freshness ON coala_evidence(subject_id, observed_at, expires_at);

-- CoALA memory stores are deliberately separate from LangGraph checkpoint tables.
CREATE TABLE IF NOT EXISTS coala_episodic_memory (
    subject_id TEXT NOT NULL, id TEXT NOT NULL, occurred_at TIMESTAMPTZ NOT NULL,
    goal TEXT NOT NULL, summary TEXT NOT NULL, outcome TEXT NOT NULL, evidence_refs TEXT NOT NULL,
    provenance_kind TEXT NOT NULL, provenance_source_id TEXT NOT NULL, provenance_description TEXT NOT NULL,
    provenance_captured_at TIMESTAMPTZ NOT NULL, lifecycle TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, retention_until TIMESTAMPTZ NULL,
    PRIMARY KEY(subject_id, id),
    CHECK (outcome IN ('PENDING','VERIFIED_SUCCESS','VERIFIED_FAILURE','PARTIAL','UNKNOWN')),
    CHECK (lifecycle IN ('CANDIDATE','ACTIVE','SUPERSEDED','ARCHIVED','REJECTED','EXPIRED'))
);
CREATE INDEX IF NOT EXISTS ix_coala_episode_recent ON coala_episodic_memory(subject_id, lifecycle, occurred_at DESC);

CREATE TABLE IF NOT EXISTS coala_semantic_memory (
    subject_id TEXT NOT NULL, id TEXT NOT NULL, subject_term TEXT NOT NULL, predicate TEXT NOT NULL,
    fact_value TEXT NOT NULL, confidence DOUBLE PRECISION NOT NULL, evidence_refs TEXT NOT NULL,
    provenance_kind TEXT NOT NULL, provenance_source_id TEXT NOT NULL, provenance_description TEXT NOT NULL,
    provenance_captured_at TIMESTAMPTZ NOT NULL, lifecycle TEXT NOT NULL,
    valid_from TIMESTAMPTZ NOT NULL, valid_until TIMESTAMPTZ NULL, supersedes_id TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(subject_id, id),
    CHECK (confidence >= 0 AND confidence <= 1),
    CHECK (lifecycle IN ('CANDIDATE','ACTIVE','SUPERSEDED','ARCHIVED','REJECTED','EXPIRED')),
    CHECK (valid_until IS NULL OR valid_until >= valid_from)
);
CREATE INDEX IF NOT EXISTS ix_coala_semantic_scope ON coala_semantic_memory(subject_id, lifecycle, valid_from);

CREATE TABLE IF NOT EXISTS coala_procedural_memory (
    subject_id TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL,
    applicability_tags TEXT NOT NULL, preconditions TEXT NOT NULL, steps TEXT NOT NULL, postconditions TEXT NOT NULL,
    successful_validations INTEGER NOT NULL, failed_validations INTEGER NOT NULL, successful_evidence_refs TEXT NOT NULL, failed_evidence_refs TEXT NOT NULL,
    provenance_kind TEXT NOT NULL, provenance_source_id TEXT NOT NULL, provenance_description TEXT NOT NULL,
    provenance_captured_at TIMESTAMPTZ NOT NULL, lifecycle TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, last_validated_at TIMESTAMPTZ NULL,
    PRIMARY KEY(subject_id, id),
    CHECK (successful_validations >= 0 AND failed_validations >= 0),
    CHECK (lifecycle IN ('CANDIDATE','ACTIVE','SUPERSEDED','ARCHIVED','REJECTED','EXPIRED'))
);
CREATE INDEX IF NOT EXISTS ix_coala_procedural_scope ON coala_procedural_memory(subject_id, lifecycle);

CREATE TABLE IF NOT EXISTS coala_working_memory (
    subject_id TEXT NOT NULL, execution_id TEXT NOT NULL, revision BIGINT NOT NULL,
    values_payload TEXT NOT NULL, updated_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NULL,
    PRIMARY KEY(subject_id, execution_id)
);

CREATE TABLE IF NOT EXISTS coala_memory_uses (
    subject_id TEXT NOT NULL, id TEXT NOT NULL, execution_id TEXT NOT NULL, memory_id TEXT NOT NULL,
    memory_kind TEXT NOT NULL, stage TEXT NOT NULL, consumer TEXT NOT NULL, occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(subject_id, id),
    CHECK (memory_kind IN ('EPISODIC','SEMANTIC','PROCEDURAL')),
    CHECK (stage IN ('RETRIEVED','ADMITTED_TO_WORKSPACE','CITED_AS_EVIDENCE','USED_IN_PLAN','USED_IN_ACTION','SHOWN_TO_USER'))
);
CREATE INDEX IF NOT EXISTS ix_coala_memory_uses_execution ON coala_memory_uses(subject_id, execution_id, occurred_at);

CREATE TABLE IF NOT EXISTS coala_memory_attributions (
    subject_id TEXT NOT NULL, id TEXT NOT NULL, execution_id TEXT NOT NULL, use_id TEXT NOT NULL, outcome_id TEXT NOT NULL,
    relation TEXT NOT NULL, basis TEXT NOT NULL, evidence_refs TEXT NOT NULL, attributed_by TEXT NOT NULL,
    attributed_at TIMESTAMPTZ NOT NULL, PRIMARY KEY(subject_id, id), UNIQUE(subject_id, use_id),
    FOREIGN KEY(subject_id, use_id) REFERENCES coala_memory_uses(subject_id, id),
    CHECK (relation IN ('SUPPORTED','CONTRADICTED','IRRELEVANT','UNDETERMINED'))
);
