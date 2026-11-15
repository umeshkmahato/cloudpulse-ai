import { api } from "../api";
import { usePolling } from "../hooks";
import type { MetricBucket } from "../types";

export interface MergedRow { bucket: string; [metric: string]: number | string }

/** Fetches several metrics for a service and merges them by time bucket (avg value). */
export function useMetricSeries(service: string, metrics: string[], minutes: number, bucketSeconds: number, intervalMs: number) {
  const polled = usePolling(
    async () => {
      const results = await Promise.all(metrics.map((m) => api.metricTrend(service, m, minutes, bucketSeconds)));
      return mergeBuckets(metrics, results);
    },
    intervalMs,
    [service, minutes, bucketSeconds, metrics.join(",")],
  );
  return polled;
}

export function mergeBuckets(metrics: string[], results: MetricBucket[][]): { rows: MergedRow[]; synthetic: boolean } {
  const byBucket = new Map<string, MergedRow>();
  let synthetic = false;
  results.forEach((rows, i) => {
    rows.forEach((r) => {
      const row = byBucket.get(r.bucket) ?? { bucket: r.bucket };
      row[metrics[i]] = Math.round(Number(r.avg) * 100) / 100;
      byBucket.set(r.bucket, row);
      synthetic = synthetic || r.synthetic;
    });
  });
  return { rows: [...byBucket.values()].sort((a, b) => a.bucket.localeCompare(b.bucket)), synthetic };
}
