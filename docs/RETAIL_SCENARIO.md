# Worked retail scenario: iterative planning and evidence-gated completion

## User goal

> Find waterproof trail-running shoes under $150 and recommend only an item whose current stock is verified.

The intention is the customer's full constraint set. It is not silently weakened when a candidate fails.

## Catalogue facts

| ID | Product | Description relevant to request | Price | Fixture stock |
|---|---|---|---:|---:|
| p-101 | Trail Runner X | Trail running, but not described as waterproof | $89.00 | 12 |
| p-103 | Peak Trail GTX | Waterproof trail running with rock plate | $149.00 | 4 |

## Successful path

1. **Retrieve.** Search the catalogue and episodic memory. Candidate memories and the active intention compete for a bounded workspace. The admitted contents and broadcast recipients are recorded.
2. **Propose.** The simulated model proposes multiple next actions: read purchase history and search the catalogue. These are text proposals, not tool invocations.
3. **Evaluate.** Java scores each candidate from action eligibility and state/evidence. The model's confidence is retained only as explicitly uncalibrated metadata; it does not decide the score.
4. **Select.** The runtime chooses `search_catalog`, because it has not yet searched. The history candidate is not selected in this scenario.
5. **Execute and observe.** The local catalogue returns `p-103` with its description and price. This is retrieval evidence, not stock-verification evidence.
6. **New decision cycle.** The runtime re-enters retrieval and exposes the search observation to the next proposal. The model proposes `check_stock(p-103)`.
7. **Evaluate and select again.** Java confirms that the ID appeared in search results and exists in the catalogue, then authorizes the whitelisted stock tool.
8. **Ground and verify.** The tool returns `[check_stock] p-103 stock=4 price=149.0`. `EvidenceVerifier` checks the observed count and price against the fixture source of truth, positive stock, waterproof/trail/running constraints, and the $150 limit.
9. **Learn.** The outcome is recorded; selected memories and admitted workspace candidates receive local usefulness updates.
10. **Answer.** The answer agent drafts prose only from verified product facts. Java rejects an ungrounded draft and has a deterministic fallback.

## The key planning rejection branch

If the model proposes `finish` before stock has been checked, independent evaluation assigns it a score of zero. Selection rejects the candidate set and routes back to retrieval/proposal with rejection feedback. No external action is executed. This is the inner planning iteration that a simple fixed `propose → evaluate → select` chain cannot express.

## Failure/recovery branch

If a grounding operation returns a typed tool failure, the runtime records the failure and opens a bounded recovery substate. The parent goal is copied into the recovery context and preserved. A resolution may change the method (for example, retrieve an alternative evidence source) without changing the committed goal. An unknown or exhausted failure terminates safely. Reusable recovery compilation is intentionally not enabled.

## What this example does not prove

- It does not show a live merchant inventory check; stock is fixture data.
- It does not prove that the model's confidence is calibrated.
- It does not validate the proposed B1/B2/B3/B4/B6 hypotheses experimentally.
- It does not implement full ACT-R, Soar, LIDA, BDI, MIDCA, EPIC or Sigma mechanisms.
