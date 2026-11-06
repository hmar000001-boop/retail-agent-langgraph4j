package com.example.retail.memory.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A reusable method candidate. It becomes ACTIVE only after repeated evidence-backed validation. */
public record ProceduralMemoryRecord(
        String id, String subjectId, String name, String description,
        List<String> applicabilityTags, List<String> preconditions, List<String> steps,
        List<String> postconditions, int successfulValidations, int failedValidations,
        List<String> successfulEvidenceIds, List<String> failedEvidenceIds, MemoryProvenance provenance,
        MemoryLifecycle lifecycle, Instant createdAt, Instant updatedAt, Instant lastValidatedAt)
        implements Serializable {
    public ProceduralMemoryRecord {
        id = required(id, "id"); subjectId = required(subjectId, "subjectId"); name = required(name, "name");
        description = required(description, "description"); applicabilityTags = clean(applicabilityTags);
        preconditions = clean(preconditions); steps = clean(steps); postconditions = clean(postconditions);
        if (steps.isEmpty()) throw new IllegalArgumentException("procedural memory requires at least one step");
        if (successfulValidations < 0 || failedValidations < 0) throw new IllegalArgumentException("validation counters cannot be negative");
        successfulEvidenceIds = clean(successfulEvidenceIds); failedEvidenceIds = clean(failedEvidenceIds);
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(lifecycle, "lifecycle"); Objects.requireNonNull(createdAt, "createdAt"); Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updatedAt precedes createdAt");
        if (lifecycle == MemoryLifecycle.ACTIVE && (successfulValidations < 2 || successfulEvidenceIds.size() < 2))
            throw new IllegalArgumentException("active procedural memory requires two distinct successful evidence-backed validations");
    }
    /** Stable reusable-method definition; validation evidence and lifecycle are maintained separately. */
    public boolean sameMethodDefinition(ProceduralMemoryRecord other) {
        return other != null && id.equals(other.id) && subjectId.equals(other.subjectId)
                && name.equals(other.name) && description.equals(other.description)
                && applicabilityTags.equals(other.applicabilityTags) && preconditions.equals(other.preconditions)
                && steps.equals(other.steps) && postconditions.equals(other.postconditions)
                && provenance.kind() == other.provenance.kind()
                && provenance.sourceId().equals(other.provenance.sourceId())
                && provenance.description().equals(other.provenance.description());
    }
    public ProceduralMemoryRecord recordValidation(boolean success, String evidenceId, Instant at, int activationThreshold) {
        if (activationThreshold < 2) throw new IllegalArgumentException("activationThreshold cannot be below two");
        String evidence = required(evidenceId, "evidenceId");
        var successRefs = new java.util.ArrayList<>(successfulEvidenceIds);
        var failureRefs = new java.util.ArrayList<>(failedEvidenceIds);
        if ((success && failureRefs.contains(evidence)) || (!success && successRefs.contains(evidence)))
            throw new IllegalArgumentException("one evidence event cannot validate both success and failure");
        if ((success && successRefs.contains(evidence)) || (!success && failureRefs.contains(evidence))) return this;
        if (success) successRefs.add(evidence); else failureRefs.add(evidence);
        List<String> successful = successRefs.stream().distinct().toList();
        List<String> failed = failureRefs.stream().distinct().toList();
        // Evidence IDs are event identities: replaying the same validation is not another validation.
        int successes = successful.size();
        int failures = failed.size();
        MemoryLifecycle next = lifecycle;
        if (!success && lifecycle == MemoryLifecycle.ACTIVE) next = MemoryLifecycle.ARCHIVED;
        if (success && successes >= activationThreshold && next == MemoryLifecycle.CANDIDATE) next = MemoryLifecycle.ACTIVE;
        return new ProceduralMemoryRecord(id, subjectId, name, description, applicabilityTags, preconditions, steps,
                postconditions, successes, failures, successful, failed, provenance, next, createdAt, at,
                at);
    }
    public boolean applicableTo(List<String> tags) {
        List<String> safeTags = tags == null ? List.of() : tags;
        return lifecycle == MemoryLifecycle.ACTIVE && (applicabilityTags.isEmpty() || safeTags.stream().anyMatch(applicabilityTags::contains));
    }
    private static List<String> clean(List<String> values) { return values == null ? List.of() : values.stream().map(v -> required(v, "list item")).distinct().toList(); }
    private static String required(String v, String n) { if (v == null || v.isBlank()) throw new IllegalArgumentException(n + " must not be blank"); return v.trim(); }
}
