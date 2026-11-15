# CloudPulse Operations Dashboard (Phase 8)

React 18 + TypeScript + Vite + Recharts. Dark "command center" UI, responsive down to phone width, keyboard accessible.

## Run
```bash
docker compose up -d                       # infra
scripts/run-local.sh start                 # 4 Java services (jars built with mvn -DskipTests package)
scripts/simulate-telemetry.py --scenario normal --backfill-minutes 15 --duration 0   # SYNTHETIC history
cd frontend/operations-dashboard && npm install && npm run dev                      # http://localhost:5173
scripts/simulate-telemetry.py --scenario spike --duration 60                        # watch services degrade
```
Vite proxies `/ops-api`, `/orders-api`, `/inventory-api` to the services, so no CORS config is needed locally. In cloud the build is served same-origin behind the ingress (Phase 11).

## Pages and data honesty
| Page | Data |
|---|---|
| Executive Overview, Infrastructure Monitoring, Application Performance | **Live** from operations/order/inventory APIs. Telemetry is labelled `SYNTHETIC`; order/event data is real local data |
| Predictive Incidents, AI Assistant, Kubernetes, Security | **Fixtures** under a `SAMPLE DATA · Phase N` badge; controls disabled until the backend exists |
| Cost | Estimate vs actual are separate panels; actual billing export is "not connected" |
| Disaster Recovery | Targets shown as experimental; no measured results |
| Digital Twin | Static model of the real architecture; failure simulation computed client-side (Phase 10 makes it live) |

## Test
`npm test` (19 tests: formatters, series merge, twin impact logic, page rendering, sample/synthetic labelling, backend-down behaviour), `npm run build`.
