import { ago, fmtTime } from "../format";
import type { StreamEvent } from "../types";
import { Empty, Pill } from "./ui";

export function EventStream({ events }: { events: StreamEvent[] }) {
  if (!events.length) return <Empty>No events yet. Place a test order to see the stream.</Empty>;
  return (
    <ul className="events" aria-label="Recent events">
      {events.map((e) => (
        <li key={e.event_id}>
          <span className="mono muted small" title={ago(e.occurred_at)}>{fmtTime(e.occurred_at)}</span>
          <span>
            <Pill tone={e.type === "order.created" ? "info" : e.summary.includes("REJECTED") ? "bad" : "ok"}>{e.type}</Pill>{" "}
            {e.summary}
            {e.correlation_id && <span className="muted small mono"> · {e.correlation_id.slice(0, 16)}</span>}
          </span>
        </li>
      ))}
    </ul>
  );
}
