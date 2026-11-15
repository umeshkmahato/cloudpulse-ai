import { describe, expect, it } from "vitest";
import { ago, fmtMs, fmtNum, successRate, toneForPct } from "./format";
import { mergeBuckets } from "./components/useMetricSeries";

describe("format helpers", () => {
  it("formats durations", () => {
    expect(fmtMs(250)).toBe("250 ms");
    expect(fmtMs(2500)).toBe("2.5 s");
    expect(fmtMs(120_000)).toBe("2.0 min");
    expect(fmtMs(null)).toBe("-");
  });
  it("formats numbers safely", () => {
    expect(fmtNum(1234.567, 1)).toBe("1,234.6");
    expect(fmtNum(undefined)).toBe("-");
  });
  it("computes reservation success rate and handles empty", () => {
    expect(successRate([])).toBeNull();
    expect(successRate([{ status: "RESERVED", count: 3 }, { status: "REJECTED", count: 1 }])).toBe(75);
    expect(successRate([{ status: "RESERVED", count: "2" as unknown as number }])).toBe(100);
  });
  it("maps utilization to tones", () => {
    expect(toneForPct(40)).toBe("ok");
    expect(toneForPct(75)).toBe("warn");
    expect(toneForPct(92)).toBe("bad");
  });
  it("renders relative time", () => {
    const now = Date.parse("2026-01-01T00:10:00Z");
    expect(ago("2026-01-01T00:09:30Z", now)).toBe("30s ago");
    expect(ago("2026-01-01T00:05:00Z", now)).toBe("5m ago");
  });
  it("merges metric buckets and propagates the synthetic flag", () => {
    const r = mergeBuckets(["a", "b"], [
      [{ bucket: "t1", avg: 1.234, max: 2, samples: 1, synthetic: true }],
      [{ bucket: "t1", avg: 5, max: 5, samples: 1, synthetic: false }, { bucket: "t2", avg: 6, max: 6, samples: 1, synthetic: false }],
    ]);
    expect(r.synthetic).toBe(true);
    expect(r.rows).toEqual([{ bucket: "t1", a: 1.23, b: 5 }, { bucket: "t2", b: 6 }]);
  });
});
