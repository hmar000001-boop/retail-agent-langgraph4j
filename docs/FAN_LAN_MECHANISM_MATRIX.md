# Fan & Lan mechanism alignment matrix

This document uses the paper's B1, B2, B3, B4, B5 and B6 labels. The five residual hypotheses are B1, B2, B3, B4 and B6. B5 is treated as closed within the paper's reviewed corpus through GraSP; this project does not reclassify it as an additional open gap.

## Mechanism-by-mechanism status

| Bundle | Paper's central coupling | Implemented here | What is still missing / what not to claim |
|---|---|---|---|
| **B1 — activation-latency-utility** | Activation, predicted retrieval latency, downstream value and maintenance cost jointly influence memory selection; outcomes update accessibility/value | `MemoryCandidate` stores the attributes; `AdaptiveMemoryPolicy` selects under a memory budget; downstream success/unverified outcomes update activation/value | Local heuristic only. No calibrated latency predictor, learned activation model, or empirical ablation. Do not call it ACT-R implementation. |
| **B2 — typed impasse-substate-compilation** | Typed failure → protected parent state → bounded child recovery → typed resolution → safe compilation into reusable future control knowledge | `RecoverySubstate` records typed failure, parent fingerprint, protected goal, attempt and resolution; recovery is bounded; goal/method authority are separated | No resolution compiler; no transfer validation or reusable rule library. `eligibleForCompilation=false` is deliberate. Do not claim Soar chunking. |
| **B3 — content competition-workspace-broadcast learning** | Typed content candidates compete for a scarce shared workspace; a winner/admitted content is broadcast; downstream utility adapts future admission | `BoundedWorkspace` ranks candidates by an explicit local score under token budget; records rejected/admitted content and recipients; `learn` adjusts later admission | Score equation is an example, not prescribed by the paper. No cognitive broadcast dynamics or evidence-quality ablation. Do not equate a message bus with B3. |
| **B4 — intention-reconsideration-method authority** | Persistent intention/commitment and reconsideration authority; diagnosis can change the active method without silently changing the objective | `IntentionRecord` and `IntentionGovernor` preserve committed goal while allowing method change, suspension, completion and termination | No separate meta-level trace diagnosis/controller with learned method switching. Do not claim a complete BDI or MIDCA system. |
| **B5 — skill governance candidate** | The paper's comparison baseline GraSP combines calibrated routing, typed skill DAG, pre/postconditions, bounded repair and escalation | Not implemented as a residual bundle; existing deterministic actions provide only a simple action registry | This project does not implement GraSP. Keep B5 as a baseline/benchmark for future skill-governance work, not a sixth open hypothesis. |
| **B6 — uncertainty-resources-interruption-stopping** | Calibrated uncertainty propagates across admission, planning, tool authorization, execution monitoring, resource use, interruption and stopping | `UncertaintyEstimate` records provenance and explicitly marks model self-confidence uncalibrated; counts bound calls/actions/planning/iterations | No calibrated estimator, cross-stage risk model, deadline/latency accounting, interruption controller or empirical selective-risk test. Do not interpret low self-confidence as a calibrated probability. |

## Dependencies retained in the design

The paper describes a composition rather than independent plug-ins:

- **B4 → B2:** intention commitment defines the parent objective/state that recovery must preserve.
- **B2 → B5:** a safe typed recovery could become a skill only after applicability, dependencies and postconditions are validated.
- **B1 → B3:** memory retrieval supplies candidates to the bounded workspace.
- **B5 → B3:** compiled/approved skills could also supply workspace candidates if a skill governor is later added.
- **B3 → B4:** admitted workspace evidence can trigger intention reconsideration.
- **B6 → other controls:** uncertainty and resource constraints should govern verification, authorization, interruption and stopping across the runtime.

The current code makes some of these links visible but does not claim to have completed the full synthesis runtime.

## Seven-field mechanism review checklist

For each mechanism, record:

1. **Operative state substrate** — which state is consumed?
2. **Control locus** — where does control reside?
3. **Transition trigger** — what event activates it?
4. **Persistence boundary** — what survives the current step, run or session?
5. **Failure semantics** — how is failure typed and handled?
6. **Learning operator** — how do outcomes affect future operation?
7. **Resource or governance policy** — what budgets, authority and constraints apply?

The existence of a field is not evidence of operational authority. Tests should demonstrate that a trigger actually changes a controlled transition and that the relevant state survives the stated boundary.

## Evidence and rejection tests to add next

| Bundle | Minimum comparative experiment |
|---|---|
| B1 | Compare learned memory selector with top-k/relevance-only baseline; measure relevant recall, task success, token cost, stale-memory rate and selector latency. |
| B2 | Compare scoped typed recovery with plain retry; verify parent state fingerprint, safe return, failure-specific recovery rate, repeated-failure rate, state corruption and unsafe reuse. |
| B3 | Compare competition/admission against top-k and current context assembly; measure relevant-evidence density, distractor robustness, rare-evidence suppression, turnover and context cost. |
| B4 | Compare persistent intention + reconsideration authority against unconstrained replanning; measure goal drift, stale commitment, oscillation, method-switch benefit and completion. |
| B6 | Compare local confidence-only controls against uncertainty propagation; measure calibration, selective risk, irreversible-action error, verification cost, interruption latency and help precision. |

Use matched model, prompts, tools, environment and task budgets. Instrument internal transitions. Remove any added mechanism that does not justify its complexity through ablation.
