#!/usr/bin/env python3
"""Generates SYNTHETIC telemetry through the Operations API (-> Pub/Sub -> sink/ops stores).
Every sample is labelled synthetic=true; it is test data, never real GCP telemetry.

  simulate-telemetry.py --scenario normal --backfill-minutes 30      # baseline history (1 sample/10s per metric)
  simulate-telemetry.py --scenario spike --duration 120 --interval 5  # the brief's overload ramp:
      requests 100->1000/min, CPU 40->90%, DB connections 50->95%, latency 300->2500 ms
"""
import argparse, json, random, time, urllib.request
from datetime import datetime, timedelta, timezone

SERVICES = ["order-service", "inventory-service"]   # overridable with --services; the FIRST one is the stressed service
BASE = {"request_count": 100, "latency_ms": 300, "cpu_pct": 40, "memory_pct": 55, "db_connections_pct": 50,
        "error_rate_pct": 0.5, "event_latency_ms": 120, "pod_restarts": 0}
PEAK = {"request_count": 1000, "latency_ms": 2500, "cpu_pct": 90, "memory_pct": 78, "db_connections_pct": 95,
        "error_rate_pct": 9, "event_latency_ms": 1800, "pod_restarts": 2}
UNITS = {"request_count": "rpm", "latency_ms": "ms", "cpu_pct": "pct", "memory_pct": "pct", "db_connections_pct": "pct",
         "error_rate_pct": "pct", "event_latency_ms": "ms", "pod_restarts": "count"}
NOISE = {"request_count": 0.08, "latency_ms": 0.1, "cpu_pct": 0.05, "memory_pct": 0.02, "db_connections_pct": 0.04,
         "error_rate_pct": 0.2, "event_latency_ms": 0.1, "pod_restarts": 0}


def value(metric, progress, service):
    """progress 0..1 interpolates baseline -> peak. The first service is fully stressed; others only 25% (knock-on effect)."""
    p = progress if service == SERVICES[0] else progress * 0.25
    v = BASE[metric] + (PEAK[metric] - BASE[metric]) * p
    v *= 1 + random.uniform(-NOISE[metric], NOISE[metric])
    return round(max(v, 0), 2)


def post(api, service, metric, val, observed):
    body = {"service": service, "metric": metric, "value": val, "unit": UNITS[metric], "synthetic": True,
            "observedAt": observed.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"}
    req = urllib.request.Request(api + "/api/v1/telemetry/metrics", json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    urllib.request.urlopen(req, timeout=10).read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://localhost:8083")
    ap.add_argument("--scenario", choices=["normal", "spike"], default="normal")
    ap.add_argument("--duration", type=int, default=60, help="seconds of live generation")
    ap.add_argument("--interval", type=int, default=5, help="seconds between live ticks")
    ap.add_argument("--services", default=",".join(SERVICES), help="comma separated service names (first = stressed)")
    ap.add_argument("--backfill-minutes", type=int, default=0, help="post this much normal history first")
    a = ap.parse_args()
    random.seed()
    SERVICES[:] = [x.strip() for x in a.services.split(",") if x.strip()]
    sent = 0
    if a.backfill_minutes:
        now = datetime.now(timezone.utc)
        for s in range(a.backfill_minutes * 6):          # one sample per 10 s
            t = now - timedelta(seconds=(a.backfill_minutes * 60) - s * 10)
            for svc in SERVICES:
                for m in BASE:
                    post(a.api, svc, m, value(m, 0, svc), t); sent += 1
        print(f"backfilled {sent} synthetic samples ({a.backfill_minutes} min of normal history)")
    ticks = max(a.duration // a.interval, 1) if a.duration else 0
    for i in range(ticks):
        progress = (i / max(ticks - 1, 1)) if a.scenario == "spike" else 0
        t = datetime.now(timezone.utc)
        for svc in SERVICES:
            for m in BASE:
                post(a.api, svc, m, value(m, progress, svc), t); sent += 1
        print(f"tick {i + 1}/{ticks} scenario={a.scenario} progress={progress:.2f}")
        if i < ticks - 1:
            time.sleep(a.interval)
    print(f"done: {sent} synthetic samples posted")


if __name__ == "__main__":
    main()
