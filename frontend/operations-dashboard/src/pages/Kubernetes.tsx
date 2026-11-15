import { Panel, SampleBanner } from "../components/ui";
import { SAMPLE_DEPLOYMENTS } from "../sample/data";

export default function Kubernetes() {
  return (
    <>
      <SampleBanner phase={11}>
        No cluster exists (GKE is a gated, billable resource and nothing is deployed). Rows are illustrative targets for the manifests
        planned in Phase 11: HPA, probes, requests/limits, PodDisruptionBudgets, NetworkPolicies and RBAC.
      </SampleBanner>
      <Panel title="Deployments (sample)">
        <div style={{ overflowX: "auto" }}>
          <table>
            <thead><tr><th>Deployment</th><th>Ready</th><th>HPA</th><th>CPU req/limit</th><th>Memory req/limit</th><th className="num">Restarts</th><th className="num">Zones</th></tr></thead>
            <tbody>{SAMPLE_DEPLOYMENTS.map((d) => (
              <tr key={d.name}><td>{d.name}</td><td>{d.ready}</td><td>{d.hpa}</td><td>{d.cpu}</td><td>{d.mem}</td><td className="num">{d.restarts}</td><td className="num">{d.zones}</td></tr>
            ))}</tbody>
          </table>
        </div>
      </Panel>
    </>
  );
}
