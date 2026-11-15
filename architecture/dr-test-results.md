# DR drill results (measured)

Environment: **local kind cluster** (3 nodes, two fake zones) on one laptop, with a synthetic probe pod placing one order and reading incidents per second through the authenticating gateway.
These are rehearsal numbers for the procedure and the architecture, **not cloud measurements and not service-level commitments**. Targets: RTO 300 s, RPO 60 s (experimental).

| Scenario | Measured | Write outage (RTO) | Reads | Orders acked / lost (RPO) | Verdict |
|---|---|---|---|---|---|
| zone-failure | 2026-11-13 14:31 UTC | 50 s (worst latency 4.0 s, 5 failed requests) | outage 86 s, 13 failed | 83 / 0 | RTO met, RPO met |
| db-crash | 2026-11-13 14:11 UTC | 0 s (worst latency 1.91 s, 0 failed requests) | outage 0 s, 0 failed | 54 / 0 | RTO met, RPO met |
| broker-outage | 2026-11-13 14:52 UTC | 0 s (worst latency 0.14 s, 0 failed requests) | outage 0 s, 0 failed | 138 / 0 | RTO met, RPO met |

## Details

### zone-failure
- Injected: docker pause of one of two worker nodes (zone-b)
- Control plane noticed the dead node after: 47 s
- All desired replicas Ready again after: 93 s
- Orders stuck PENDING drained after: 0 s
- Write availability during the fault window: 87.2%, read availability 66.7%

### db-crash
- Injected: kubectl delete pod postgres-0 --force --grace-period=0 (crash recovery from the persistent volume)
- Postgres replacement Ready after: 2 s
- Orders stuck PENDING drained after: 0 s
- Write availability during the fault window: 100.0%, read availability 100.0%

### broker-outage
- Injected: pubsub-emulator scaled to 0 for 60 s, then restored (+ topic re-creation, emulator-only)
- Orders stuck PENDING drained after: 1 s
- Events waiting in the outbox at peak: 30
- Outbox events still unpublished at the end: 0
- Broker restored to all orders processed: 63 s
- Write availability during the fault window: 100.0%, read availability 100.0%

### pitr-restore (volume loss, restore from base backup + WAL archive)
- Measured 2026-11-13 14:47 UTC; base backup took 4 s; WAL archive interval 30 s.
- Three disaster instants within the archive interval, each restoring from the archive frozen at that instant:

| Disaster | Restore time | Newest row in production | Newest row recovered | Rows lost | Data-loss window | Orders restored / in production |
|---|---|---|---|---|---|---|
| #1 | 4 s | 803 | 778 | 25 | 46 s | 51018 / 51018 |
| #2 | 3 s | 818 | 804 | 14 | 15 s | 51018 / 51018 |
| #3 | 3 s | 833 | 832 | 1 | 0 s | 51018 / 51018 |

Worst restore 4 s (RTO target 300 s: met); worst data-loss window 46 s (RPO target 60 s: met).
The backup volume sits in the same cluster, so this proves the procedure, not regional survivability.

## Caveats
- One laptop, tiny data set, warm image caches: cloud restore/failover times will differ (larger data, image pulls, cloud control-plane delays).
- `docker pause` simulates an unreachable node (frozen kubelet and pods); real zone failures can also be partial or flapping.
- Probe resolution is 1 s; outage figures are accurate to about 1-2 s.
- The in-memory Pub/Sub emulator needs topic re-creation and consumer restarts after a restart; managed Pub/Sub does not.
- Not tested: regional failure, cross-region replica promotion, load balancer failover (design only; see dr-strategy.md).
