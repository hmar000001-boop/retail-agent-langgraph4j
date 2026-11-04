package com.example.retail.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** JSON contracts the model must honour. Unknown fields are ignored; missing/invalid ones are a typed failure. */
public final class LlmDtos {
    private LlmDtos() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProposalsDto(List<ProposalDto> proposals) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProposalDto(String tool, String args, String rationale, Double confidence) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScoresDto(List<Double> scores) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReflectionDto(Boolean goalSatisfied, String summary) {}
}
