# CloudPulse AI - High-Level Design (living document)

## Flow (target)
Customer -> order-service (REST, PostgreSQL) -> Pub/Sub `orders-created` -> inventory-service -> `inventory-updated` -> analytics sink (BigQuery in cloud, local store in dev) -> operations-service (baselines, anomalies, incidents) -> dashboard / AI assistant -> remediation-controller (approval-gated, predefined actions only).

## Services
| Service | Role |
|---|---|
| order-service | Orders API, persistence, event publish |
| inventory-service | Idempotent consumer, stock management |
| operations-service | Telemetry, EWMA/stddev anomalies, risk score, incidents, digital twin |
| remediation-controller | Allow-listed actions (e.g. scale deployment), audit log |
| ai-incident-assistant | Evidence-grounded answers; Vertex AI optional |

## Environments
Local (Compose + emulator + kind) first; GCP deferred (ADR-0001, ADR-0002).

## Principles
Label synthetic data; never claim unverified pricing/free tier; AI never executes commands.

Detailed design: see `LLD.md`.
