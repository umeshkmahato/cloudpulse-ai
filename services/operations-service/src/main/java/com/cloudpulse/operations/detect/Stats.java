package com.cloudpulse.operations.detect;

/** Plain, transparent statistics. No models, no training: everything here can be checked by hand. */
public final class Stats {
    public record Line(double slope, double intercept, double r2) {}

    public static double mean(double[] v) {
        double s = 0;
        for (double x : v) s += x;
        return v.length == 0 ? 0 : s / v.length;
    }

    /** Sample standard deviation (n-1); 0 when fewer than two points. */
    public static double stddev(double[] v) {
        if (v.length < 2) return 0;
        double m = mean(v), s = 0;
        for (double x : v) s += (x - m) * (x - m);
        return Math.sqrt(s / (v.length - 1));
    }

    /** Exponentially weighted moving average: e_t = a*x_t + (1-a)*e_{t-1}, starting from {@code init}. */
    public static double ewma(double[] series, double alpha, double init) {
        double e = init;
        for (double x : series) e = alpha * x + (1 - alpha) * e;
        return e;
    }

    /** Ordinary least squares y = intercept + slope*x with coefficient of determination. */
    public static Line regress(double[] x, double[] y) {
        int n = x.length;
        if (n < 2) return new Line(0, n == 1 ? y[0] : 0, 0);
        double mx = mean(x), my = mean(y), sxx = 0, sxy = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            sxx += (x[i] - mx) * (x[i] - mx);
            sxy += (x[i] - mx) * (y[i] - my);
            syy += (y[i] - my) * (y[i] - my);
        }
        if (sxx == 0) return new Line(0, my, 0);
        double slope = sxy / sxx;
        double r2 = syy == 0 ? 0 : (sxy * sxy) / (sxx * syy);
        return new Line(slope, my - slope * mx, r2);
    }

    public static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private Stats() {}
}
