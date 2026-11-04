# Changelog

## Unreleased

- Fixed `CoreMechanismTest.adaptiveMemoryRefreshPreservesOutcomeLearnedActivationAndValue` to compare outcome-learned floating-point values with a `1e-9` tolerance instead of exact decimal equality. The production adaptive-memory policy is unchanged; the observed `0.7799999999999999` value is the normal IEEE-754 representation of the expected `0.78` result.
