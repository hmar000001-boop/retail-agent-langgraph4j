# Changelog

## Unreleased

- Fixed `CoreMechanismTest.adaptiveMemoryRefreshPreservesOutcomeLearnedActivationAndValue` to compare outcome-learned floating-point values with a `1e-9` tolerance instead of exact decimal equality. The production adaptive-memory policy is unchanged; the observed `0.7799999999999999` value is the normal IEEE-754 representation of the expected `0.78` result.
- Fixed LangGraph4j Spring binding for `AgentSettings` by keeping its canonical record constructor unambiguous; updated the hand-built test fixture to use the full canonical settings tuple.
- Made repeated LangGraph4j node visits observable by annotating trace events with outer-cycle and planning-round coordinates. This distinguishes an actual second retrieve/propose/evaluate/select pass from deduplicated identical trace labels.
- Corrected the total-provider-outage test to assert that the fallback recommendation matches a positively verified stock observation, rather than requiring `p-103` for a generic trail-running query that does not require waterproofing.
