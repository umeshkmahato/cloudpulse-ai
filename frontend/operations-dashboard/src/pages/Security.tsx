import { Panel, SampleBanner, SeverityBadge } from "../components/ui";
import { SAMPLE_SECURITY } from "../sample/data";

export default function Security() {
  return (
    <>
      <SampleBanner phase={13}>
        Security telemetry is not collected yet. These rows illustrate the planned feed (audit logs, API auth failures, image scanning, network-policy denials) and are not real events.
      </SampleBanner>
      <Panel title="Security events (sample)">
        <div style={{ overflowX: "auto" }}>
          <table>
            <thead><tr><th>Time</th><th>Severity</th><th>Type</th><th>Detail</th><th>Source</th></tr></thead>
            <tbody>{SAMPLE_SECURITY.map((e) => (
              <tr key={e.type}><td className="mono">{e.time}</td><td><SeverityBadge level={e.severity} /></td><td className="mono">{e.type}</td><td>{e.detail}</td><td className="muted">{e.source}</td></tr>
            ))}</tbody>
          </table>
        </div>
      </Panel>
    </>
  );
}
