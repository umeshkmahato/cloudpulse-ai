#!/usr/bin/env python3
"""Builds architecture/dr-test-results.md and the dashboard's results file from testing/dr/results/*.json."""
import json, os, datetime as dt, glob
R = "testing/dr/results"
order = ["zone-failure", "db-crash", "broker-outage", "pitr-restore"]
res = {}
for n in order:
    p = f"{R}/{n}.json"
    if os.path.exists(p):
        d = json.load(open(p)); d["measured_at"] = dt.datetime.fromtimestamp(os.path.getmtime(p), dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC"); res[n] = d
json.dump(dict(environment="local kind cluster (3 nodes, 2 fake zones) on one laptop; synthetic probe traffic; NOT a cloud measurement", targets=dict(rto_s=300, rpo_s=60, note="experimental POC targets, not commitments"), results=res),
          open("frontend/operations-dashboard/src/dr-results.json", "w"), indent=2)

def s(v): return "n/a" if v is None else (f"{v} s" if isinstance(v, (int, float)) else str(v))
L = ["# DR drill results (measured)", "",
     "Environment: **local kind cluster** (3 nodes, two fake zones) on one laptop, with a synthetic probe pod placing one order and reading incidents per second through the authenticating gateway.",
     "These are rehearsal numbers for the procedure and the architecture, **not cloud measurements and not service-level commitments**. Targets: RTO 300 s, RPO 60 s (experimental).", "",
     "| Scenario | Measured | Write outage (RTO) | Reads | Orders acked / lost (RPO) | Verdict |", "|---|---|---|---|---|---|"]
for n in ["zone-failure", "db-crash", "broker-outage"]:
    d = res.get(n)
    if not d: L.append(f"| {n} | not run | | | | |"); continue
    L.append(f"| {n} | {d['measured_at']} | {s(d['write_outage_s'])} (worst latency {d['write_worst_latency_s']} s, {d['write_failed_requests']} failed requests) | outage {s(d['read_outage_s'])}, {d['read_failed_requests']} failed | {d['orders_acknowledged']} / {d['acknowledged_orders_lost']} | RTO {'met' if d['rto_met'] else 'MISSED'}, RPO {'met' if d['rpo_met'] else 'MISSED'} |")
L += ["", "## Details"]
for n in ["zone-failure", "db-crash", "broker-outage"]:
    d = res.get(n)
    if not d: continue
    L += ["", f"### {n}", f"- Injected: {d.get('injected')}"]
    for k, label in [("node_notready_detected_s", "Control plane noticed the dead node after"), ("capacity_restored_s", "All desired replicas Ready again after"), ("postgres_ready_s", "Postgres replacement Ready after"),
                     ("pending_order_catchup_s", "Orders stuck PENDING drained after"), ("outbox_events_waiting_at_peak", "Events waiting in the outbox at peak"),
                     ("outbox_events_still_unpublished", "Outbox events still unpublished at the end"), ("broker_back_to_catchup_s", "Broker restored to all orders processed")]:
        if k in d and d[k] is not None: L.append(f"- {label}: {s(d[k]) if k.endswith('_s') else d[k]}")
    L.append(f"- Write availability during the fault window: {d['write_availability_pct_during_fault']}%, read availability {d['read_availability_pct_during_fault']}%")
p = res.get("pitr-restore")
if p:
    L += ["", "### pitr-restore (volume loss, restore from base backup + WAL archive)", f"- Measured {p['measured_at']}; base backup took {p['base_backup_s']} s; WAL archive interval {p['archive_timeout_s']} s.",
          "- Three disaster instants within the archive interval, each restoring from the archive frozen at that instant:", "",
          "| Disaster | Restore time | Newest row in production | Newest row recovered | Rows lost | Data-loss window | Orders restored / in production |", "|---|---|---|---|---|---|---|"]
    for r in p["runs"]:
        L.append(f"| #{r['disaster']} | {r['restore_s']} s | {r['production_last_marker']} | {r['restored_last_marker']} | {r['rows_lost']} | {r['data_loss_window_s']} s | {r['orders_restored']} / {r['orders_in_production']} |")
    L += ["", f"Worst restore {p['worst_restore_s']} s (RTO target 300 s: {'met' if p['rto_met'] else 'MISSED'}); worst data-loss window {p['worst_data_loss_window_s']} s (RPO target 60 s: {'met' if p['rpo_met'] else 'MISSED'}).",
          "The backup volume sits in the same cluster, so this proves the procedure, not regional survivability."]
L += ["", "## Caveats", "- One laptop, tiny data set, warm image caches: cloud restore/failover times will differ (larger data, image pulls, cloud control-plane delays).",
      "- `docker pause` simulates an unreachable node (frozen kubelet and pods); real zone failures can also be partial or flapping.",
      "- Probe resolution is 1 s; outage figures are accurate to about 1-2 s.",
      "- The in-memory Pub/Sub emulator needs topic re-creation and consumer restarts after a restart; managed Pub/Sub does not.",
      "- Not tested: regional failure, cross-region replica promotion, load balancer failover (design only; see dr-strategy.md)."]
open("architecture/dr-test-results.md", "w").write("\n".join(L) + "\n")
print("written architecture/dr-test-results.md and frontend/operations-dashboard/src/dr-results.json")
