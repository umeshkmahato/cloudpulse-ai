#!/usr/bin/env python3
"""Summarises a saved Terraform plan and flags resources that cost money while idle. Exit code 3 if any."""
import json, subprocess, sys
from collections import Counter

BILLABLE = {
    "google_container_cluster": "GKE cluster fee + pod/node compute",
    "google_container_node_pool": "GKE nodes (compute, billed while running)",
    "google_sql_database_instance": "Cloud SQL instance (billed 24x7, incl. idle)",
    "google_compute_router_nat": "Cloud NAT gateway (hourly + data processing)",
    "google_compute_address": "Static IP (billed when reserved/unused)",
    "google_compute_forwarding_rule": "Load balancer forwarding rule",
    "google_compute_instance": "VM instance",
    "google_dataflow_job": "Dataflow workers",
    "google_kms_crypto_key": "KMS key versions (small monthly cost; keys cannot be deleted)",
    "google_vertex_ai_endpoint": "Vertex AI endpoint",
}

plan_file, workdir = sys.argv[1], sys.argv[2]
raw = subprocess.run(["terraform", f"-chdir={workdir}", "show", "-json", plan_file],
                     check=True, capture_output=True, text=True).stdout
changes = [c for c in json.loads(raw).get("resource_changes", []) if c["change"]["actions"] != ["no-op"]]

by_action = Counter(a for c in changes for a in c["change"]["actions"])
print("\n=== PLAN SUMMARY ===")
print("actions:", dict(by_action))
types = Counter((c["type"], "/".join(c["change"]["actions"])) for c in changes)
for (t, a), n in sorted(types.items()):
    print(f"  {a:14} {t} x{n}")

hits = [c for c in changes if c["type"] in BILLABLE and "create" in c["change"]["actions"]]
destroys = [c for c in changes if "delete" in c["change"]["actions"]]
if hits:
    print("\n!!! BILLABLE-WHILE-IDLE RESOURCES IN THIS PLAN !!!")
    for c in hits:
        print(f"  {c['address']}: {BILLABLE[c['type']]}")
if destroys:
    print("\n!!! DESTRUCTIVE CHANGES !!!")
    for c in destroys:
        print(f"  {c['address']} ({'/'.join(c['change']['actions'])})")
sys.exit(3 if hits or destroys else 0)
