# AI incident assistant and digital twin (Phase 10)

## Digital twin (in operations-service, relational)
Tables `twin_components`, `twin_dependencies`, `twin_transactions`, `twin_transaction_components`, `twin_recovery_steps` (Flyway V3).
Chosen over a graph database: ~15 nodes, impact analysis is a small reverse walk done in code (`ImpactCalculator`), recursive SQL or a
graph DB would add operations cost for no benefit. Revisit around thousands of components or when path queries dominate.

- **Honest model:** `deployment_status` is `ACTIVE_LOCAL` or `PLANNED`. Planned items (GKE, Kubernetes deployments, NetworkPolicy) are in the
  target architecture only. `scope=current` ignores them; `scope=target` includes them.
- **Live overlay:** `/api/v1/twin/graph` joins structure with the anomaly detector's live status per service.
- **Impact rules:** a dependent is HARD-impacted while every hop is synchronous; crossing a topic makes it DELAYED (buffered, not lost); a
  delayed service delays only its synchronous callers; PROTECTS relations (security policies) never propagate failure.
  A transaction is BLOCKED if a component it needs failed or is hard-impacted, DELAYED if only a topic it flows through is backed up.
- **Answers "what if X fails?"**: dependents, impacted transactions with business impact, message recovery options, recovery and mitigation steps.
  It is a structural what-if, not a load/timing simulation.

## AI incident assistant (`services/ai-incident-assistant`, port 8085)
`POST /api/v1/assistant/ask {question}` answers the eight questions in the brief.
1. **Classify** the question with deterministic keyword rules; unrecognised input is `UNKNOWN` (no guessing, lists what it can answer).
2. **Retrieve evidence** from the Operations API (read-only): detector state, incidents, twin graph/impact, analytics. Each fact becomes an `Evidence`
   item `EV-n` (type, source URL, retrieval time, summary, raw data, synthetic flag).
3. **Compose** an answer where every claim carries evidence ids (`verifyClaims` fails fast if a claim has none). Causes are labelled
   *Hypothesis*; confidence is HIGH (measured), MEDIUM (model-based), LOW (hypothesis) or NONE.
4. **Optional LLM rewrite** (Vertex AI, `ASSISTANT_PROVIDER=vertex`, UNVERIFIED until GCP access): the LLM may only rephrase.
   `GroundingValidator` rejects its output unless it cites existing evidence ids, every number appears in the evidence or question,
   and it contains no code/commands; otherwise the rule-based answer is used. Default is rule-based: free, deterministic, testable.
5. **Audit**: every answer with its evidence is stored in `assistant.answers`.

### Behaviours worth demonstrating
| Situation | What the assistant does |
|---|---|
| Evidence source down | "I cannot reach the Operations API... I will not guess", confidence NONE |
| Service has no telemetry / healthy | Says it has no data / does not look slow, with the measured numbers |
| Cost question | Refuses to invent a dollar figure; explains the formula and what is missing (no cloud runtime, unverified pricing) |
| Several anomalous services | Ranks by risk using synchronous dependencies; LOW confidence when they are independent |
| Prompt-injection style input | `UNKNOWN`, no claims; question text never becomes an instruction; LLM prompt treats evidence as untrusted data |
| Synthetic evidence | Answer is flagged `containsSyntheticData` and the UI shows a SYNTHETIC badge |

## Security notes
Read-only access to one internal API; no tools, no shell, no write path. Question length capped (500). Correlation IDs on every request.
Vertex call (when enabled) uses ADC/Workload Identity with a role limited to `aiplatform.user`; prompt contains only evidence summaries.
Residual risk: evidence text (e.g. event summaries) is attacker-influenced data; the validator and the no-tools design bound the blast radius.

## Interview notes
- **Why rule-based first?** Determinism, testability, zero cost; an LLM is an optional presentation layer that cannot add facts.
- **Why not RAG over logs?** No log store exists yet; adding retrieval without ground-truth evidence would invite hallucination.
- **Scaling limits:** each question makes 1-4 HTTP calls; cache detector state, add pagination to incidents; LLM latency/cost dominate when enabled.
- **Failure modes:** evidence API down (handled), stale evidence (retrieval time shown), LLM refusal/latency (fallback), classifier miss (UNKNOWN).
- **Cost:** rule-based is free; Vertex is billed per token (pricing not verified here).
- **10M users:** per-tenant evidence scoping, streaming answers, evaluation sets with known-incident replays to measure answer accuracy.
