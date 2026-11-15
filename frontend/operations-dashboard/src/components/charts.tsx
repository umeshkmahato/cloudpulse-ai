import { Area, AreaChart, Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { fmtTime } from "../format";
import { Empty } from "./ui";

const AXIS = { stroke: "#8aa0c0", fontSize: 11 };
const GRID = "#1f2b45";
const TOOLTIP = { contentStyle: { background: "#0b1220", border: "1px solid #1f2b45", borderRadius: 8 }, labelStyle: { color: "#8aa0c0" } };

export interface Series { key: string; label: string; color: string }

/** Time-series line chart. Rows need a `bucket` ISO timestamp plus the numeric keys named in `series`. */
export function TimeSeries({ data, series, height = 220, unit = "", area = false }: {
  data: Record<string, unknown>[]; series: Series[]; height?: number; unit?: string; area?: boolean;
}) {
  if (!data.length) return <Empty>No data in this window yet.</Empty>;
  const Chart = area ? AreaChart : LineChart;
  return (
    <div role="img" aria-label={`Time series of ${series.map((s) => s.label).join(", ")}`}>
      <ResponsiveContainer width="100%" height={height}>
        <Chart data={data} margin={{ top: 6, right: 12, bottom: 0, left: 0 }}>
          <CartesianGrid stroke={GRID} strokeDasharray="3 3" />
          <XAxis dataKey="bucket" tickFormatter={(v) => fmtTime(String(v))} {...AXIS} minTickGap={32} />
          <YAxis {...AXIS} width={48} unit={unit} />
          <Tooltip {...TOOLTIP} labelFormatter={(v) => fmtTime(String(v))} />
          {series.length > 1 && <Legend wrapperStyle={{ fontSize: 12 }} />}
          {series.map((s) =>
            area ? (
              <Area key={s.key} type="monotone" dataKey={s.key} name={s.label} stroke={s.color} fill={s.color} fillOpacity={0.18} strokeWidth={2} dot={false} isAnimationActive={false} />
            ) : (
              <Line key={s.key} type="monotone" dataKey={s.key} name={s.label} stroke={s.color} strokeWidth={2} dot={false} isAnimationActive={false} />
            ),
          )}
        </Chart>
      </ResponsiveContainer>
    </div>
  );
}

export function Bars({ data, xKey, series, height = 220 }: { data: Record<string, unknown>[]; xKey: string; series: Series[]; height?: number }) {
  if (!data.length) return <Empty>No data in this window yet.</Empty>;
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} margin={{ top: 6, right: 12, bottom: 0, left: 0 }}>
        <CartesianGrid stroke={GRID} strokeDasharray="3 3" />
        <XAxis dataKey={xKey} {...AXIS} />
        <YAxis {...AXIS} width={40} allowDecimals={false} />
        <Tooltip {...TOOLTIP} cursor={{ fill: "rgba(56,189,248,.08)" }} />
        {series.length > 1 && <Legend wrapperStyle={{ fontSize: 12 }} />}
        {series.map((s) => <Bar key={s.key} dataKey={s.key} name={s.label} fill={s.color} radius={[4, 4, 0, 0]} isAnimationActive={false} />)}
      </BarChart>
    </ResponsiveContainer>
  );
}
