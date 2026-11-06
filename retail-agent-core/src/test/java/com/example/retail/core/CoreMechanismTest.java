package com.example.retail.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.retail.agent.Proposal;
import com.example.retail.domain.ProductCatalog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreMechanismTest {
    private final ProductCatalog catalog = new ProductCatalog();

    @Test
    void proposerConfidenceCannotCausePrematureFinish() {
        Proposal dishonest = new Proposal("finish", "", "I am certain", 0.999, 0.999);
        Proposal evaluated = CandidateEvaluator.evaluateAll(List.of(dishonest),
                "waterproof trail running shoes under $150", List.of(), catalog).getFirst();
        assertThat(evaluated.confidence()).isEqualTo(0.999);
        assertThat(evaluated.score()).isZero();
    }

    @Test
    void goalIsSatisfiedOnlyByCurrentInStockMatchingEvidence() {
        String good = "[check_stock] p-103 stock=4 price=149.0";
        assertThat(EvidenceVerifier.goalSatisfied("waterproof trail running shoes under $150", List.of(good), catalog)).isTrue();
        assertThat(EvidenceVerifier.goalSatisfied("waterproof trail running shoes under $100", List.of(good), catalog)).isFalse();
        assertThat(EvidenceVerifier.goalSatisfied("waterproof trail running shoes under $150",
                List.of("[check_stock] p-103 stock=0 price=149.0"), catalog)).isFalse();
        assertThat(EvidenceVerifier.goalSatisfied("waterproof trail running shoes under $150",
                List.of("[check_stock] p-101 stock=12 price=89.0"), catalog)).isFalse();
    }


    @Test
    void evaluatorAllowsARevisedSearchButSuppressesAnExactRepeat() {
        List<String> observations = List.of(
                "[search_catalog] \"trail running shoes\" -> p-101|Trail Runner X|89.0");
        Proposal repeat = new Proposal("search_catalog", "trail running shoes", "repeat", 0.99, 0.0);
        Proposal revised = new Proposal("search_catalog", "waterproof trail running shoes alternatives", "broaden", 0.10, 0.0);

        assertThat(CandidateEvaluator.evaluate(repeat, "trail running shoes", observations, catalog)).isEqualTo(0.15);
        assertThat(CandidateEvaluator.evaluate(revised, "trail running shoes", observations, catalog)).isEqualTo(0.92);
    }

    @Test
    void evaluatorRejectsEmptyAndUnproductiveSearchesButAllowsNewEvidence() {
        List<String> observations = List.of(
                "[search_catalog] \"trail running shoes\" -> p-101|Trail Runner X|89.0");

        assertThat(CandidateEvaluator.evaluate(
                new Proposal("search_catalog", "unicorn telescope", "irrelevant", 0.9, 0.0),
                "trail running shoes", observations, catalog)).isEqualTo(0.05);
        assertThat(CandidateEvaluator.evaluate(
                new Proposal("search_catalog", "trail running shoes alternatives", "same result", 0.9, 0.0),
                "trail running shoes", observations, catalog)).isEqualTo(0.92);
        assertThat(CandidateEvaluator.evaluate(
                new Proposal("check_stock", "p-101", "check again", 0.9, 0.0),
                "trail running shoes", List.of("[search_catalog] \"trail running shoes\" -> p-101|Trail Runner X|89.0",
                        "[check_stock] p-101 stock=12 price=89.0"), catalog)).isEqualTo(0.05);
    }

    @Test
    void workspaceIsScarceAndBroadcastIsExplicit() {
        var workspace = new BoundedWorkspace();
        var items = List.of(
                new WorkspaceCandidate("goal", WorkspaceCandidate.CandidateType.ACTIVE_INTENTION,
                        "find verified shoes", 1.0, 20, List.of("planning", "governor", "execution")),
                new WorkspaceCandidate("noise", WorkspaceCandidate.CandidateType.MEMORY,
                        "unrelated past purchase", 0.1, 80, List.of("planning")));
        var admitted = workspace.admit(items, 30);
        assertThat(admitted.admitted()).extracting(WorkspaceCandidate::id).containsExactly("goal");
        assertThat(admitted.rejectedIds()).containsExactly("noise");
    }

    @Test
    void intentionMethodMayChangeWithoutChangingCommittedGoal() {
        var original = IntentionRecord.start("find waterproof trail shoes below 150");
        var result = new IntentionGovernor().afterEvidence(original, false, true, true);
        assertThat(result.decision()).isEqualTo(IntentionGovernor.Decision.CHANGE_METHOD);
        assertThat(result.intention().committedGoal()).isEqualTo(original.committedGoal());
        assertThat(result.intention().currentMethod()).isNotEqualTo(original.currentMethod());
    }


    @Test
    void adaptiveMemoryRefreshPreservesOutcomeLearnedActivationAndValue() {
        var memory = new AdaptiveMemoryPolicy();
        var original = new MemoryCandidate("catalog-p-103", "old description", 0.70, 20, 0.60, 0.10, 20);
        memory.remember(original);
        memory.recordDownstreamUse("catalog-p-103", true);

        memory.remember(new MemoryCandidate("catalog-p-103", "updated description", 0.20, 25, 0.20, 0.15, 22));
        MemoryCandidate refreshed = memory.snapshot().getFirst();

        assertThat(refreshed.content()).isEqualTo("updated description");
        // These values are produced by floating-point addition in withOutcome().
        // Compare with a tolerance instead of requiring a bit-for-bit decimal representation.
        assertThat(refreshed.activation()).isCloseTo(0.78, org.assertj.core.data.Offset.offset(1.0e-9));
        assertThat(refreshed.downstreamValue()).isCloseTo(0.70, org.assertj.core.data.Offset.offset(1.0e-9));
        assertThat(refreshed.predictedRetrievalLatencyMs()).isEqualTo(25);
        assertThat(refreshed.maintenanceCost()).isEqualTo(0.15);
        assertThat(refreshed.tokenCost()).isEqualTo(22);
    }

    @Test
    void bothFrameworkAdaptersUseOneSharedResourceEnvelopeByDefault() {
        AgentLimits limits = AgentLimits.defaults();
        assertThat(limits.maxIterations()).isEqualTo(3);
        assertThat(limits.maxPlanningRounds()).isEqualTo(3);
        assertThat(limits.maxFailures()).isEqualTo(4);
        assertThat(limits.maxToolCalls()).isEqualTo(3);
        assertThat(limits.maxModelCalls()).isEqualTo(8);
        assertThat(limits.maxRecoveryAttempts()).isEqualTo(2);
        assertThat(limits.memoryTokenBudget()).isEqualTo(180);
        assertThat(limits.workspaceTokenBudget()).isEqualTo(260);
    }

    @Test
    void uncertaintyRejectsNanAndInfiniteValues() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new UncertaintyEstimate(Double.NaN, "bad", false, "none", 0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new UncertaintyEstimate(Double.POSITIVE_INFINITY, "bad", false, "none", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void graphStateProposalIsSerializableForLangGraphStateCloning() throws Exception {
        Proposal proposal = new Proposal("check_stock", "p-103", "verify current availability", 0.8, 0.95);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(proposal); }
        Proposal restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (Proposal) in.readObject();
        }
        assertThat(restored).isEqualTo(proposal);
    }
}
