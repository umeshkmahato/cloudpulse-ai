# ADR-0001: Local-first development with cost guardrails
Status: Accepted (Phase 1)

## Context
Budget is Free Tier / zero cost. GKE, Cloud SQL, Cloud NAT, external load balancers and Dataflow bill while idle.

## Decision
1. Develop on Docker Compose (Postgres, Valkey, Pub/Sub emulator, Prometheus, Grafana) and kind/minikube.
2. Use real Pub/Sub and BigQuery selectively for integration tests, within verified quotas.
3. Terraform modules for GKE/Cloud SQL/NAT are written and validated but only `plan`ned; `apply` requires explicit approval with a cost estimate.
4. A billing budget with alert thresholds (50/90/100%) is created in Phase 2 before any apply.
5. Pricing/free-tier claims are verified against current Google Cloud docs before being written into docs.

## Consequences
Faster inner loop and no surprise bills; cloud-only behaviours (Workload Identity, GKE HPA at scale) are demonstrated later with a short-lived, approved cluster.

## Alternatives rejected
Provision GKE on day 1 (idle cost, slow feedback); mock all GCP services (weak interview credibility).
