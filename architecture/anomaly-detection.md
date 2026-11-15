# Predictive anomaly detection (Phase 9)

Goal: move from reactive thresholds to proactive, **explainable** detection without pretending to predict the future.

## Two different claims, kept apart
| | DETECTED anomaly | FORECAST |
|---|---|---|
| Meaning | Metrics are statistically unusual **now** | A saturating metric is trending toward a ceiling |
| Method | z-score + EWMA control chart + significance gate | Least-squares line over the last 5 min, time to 95% |
| Confidence | Evidence-backed (every number shown) | **LOW by construction**: a trend line, not a model |
| Guard rails | none needed beyond thresholds | slope > 0, r² ≥ 0.6, ≥ 8 samples, ETA ≤ 30 min |
| Incident kind | `DETECTED` | `FORECAST` |

We never claim accurate failure prediction. With little history we say so: `INSUFFICIENT_DATA`, risk 0.

## Algorithm (all thresholds in `application.yml` under `cloudpulse.detection`)
1. **Baseline** per (service, metric): mean and sample std of samples from `[now-60m, now-3m]`; needs ≥ 30 samples spanning ≥ 5 min.
   The newest 3 minutes are excluded so an unfolding spike cannot contaminate "normal".
2. **Current level** = mean of the last 3 samples inside the last 90 s (fewer fresh samples ⇒ metric skipped and noted).
3. **Noise floor**: σ = max(std, 2% of mean, per-metric absolute floor) so near-constant series cannot produce absurd z-scores.
4. **Anomalous** iff all three hold: `z = (current - mean)/σ ≥ 3`, `relative deviation ≥ +20%`, and `EWMA(last 20, α=0.3) > mean + 3σ·sqrt(α/(2-α))`.
   Statistical significance (z, EWMA chart) AND practical significance (+20%) cut false alarms.
5. **Risk (0-100)** = 100 × Σ weight × clamp(0.4 + 0.6·relDeviation, 0.4, 1) over anomalous metrics.
   Weights: latency .25, error rate .20, CPU .20, DB connections .15, request rate / memory / event latency / pod restarts .05 each.
   Single weak signals stay below the incident threshold (40), corroboration raises it. Severity: ≥80 CRITICAL, ≥60 HIGH, ≥40 MEDIUM.
6. **Baseline freezing**: while an incident is open the pre-incident baseline is reused, so a long spike does not become the new normal.
   (Restarting the service while an incident is open recomputes it: known limitation.)

## Incident lifecycle
One OPEN incident per service (unique index). Opens on ANOMALY/FORECAST, refreshes while it continues, escalates when severity rises
or a forecast becomes a detection, and **auto-resolves after 6 consecutive clean evaluations**. Stale or insufficient data never
silently resolves an incident. Evidence (all metrics, z, EWMA, baselines, notes) is stored with the incident for audit.

## Recommendations
Rule-based mapping to a **predefined action catalogue** (`SCALE_DEPLOYMENT`, `INSPECT_ONLY`), always `requiresApproval=true`.
Nothing executes in Phase 9. E.g. DB connections high ⇒ "check pool before scaling" (more replicas multiply connections).
Phase 12 builds the restricted controller that can execute approved catalogue actions only.

## Events and APIs
`alert.raised` (topic `infrastructure-alerts`, state OPENED | ESCALATED | DEESCALATED | RESOLVED), schema `architecture/event-schemas/alert.raised.v1.schema.json`
with a contract test. Publishing is best-effort (the incident table is the source of truth).
`GET /api/v1/incidents[?status=]`, `GET /api/v1/incidents/{id}`, `GET /api/v1/detection/current`, `POST /api/v1/detection/evaluate`.

## Known limitations (honest list)
- Statistics assume roughly stable, non-seasonal baselines. Daily/weekly seasonality needs seasonal baselines (e.g. same hour last week) or a model.
- Synthetic data has very low noise, so ramps become detected anomalies before forecasts trigger; forecasts are covered by unit tests, not the e2e.
- Downstream impact uses a static map until the digital twin (Phase 10).
- Metrics come from the Operations hot store (Postgres); in cloud this should read Cloud Monitoring / BigQuery.
- No ML: with real traffic, compare against Cloud Monitoring anomaly alerts or a trained model before trusting thresholds.

## Interview notes
- **Why not ML first?** No labelled incidents or enough history; transparent statistics are debuggable, cheap and defensible. ML earns its place once we have data and a measured false-positive rate to beat.
- **Why EWMA?** Reacts to sustained shifts and ignores single-sample noise; the control limit has a closed form.
- **False positives/negatives:** z+EWMA+significance gating trades sensitivity for precision; tuning means replaying recorded incidents.
- **Scale:** per-service work is O(metrics × window); shard services across workers; move baselines to precomputed rollups (BigQuery scheduled queries) at 10M-user scale.
- **Cost:** reads hit a database each 10 s per service; trivial locally, in cloud use Monitoring/BigQuery with partition filters and cache baselines.
