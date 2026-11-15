import { Empty, Panel, Pill, SampleBadge, SampleBanner } from "../components/ui";
import { fmtMoney } from "../format";
import { SAMPLE_COST } from "../sample/data";

export default function Cost() {
  const total = SAMPLE_COST.estimateMonthly.reduce((a, r) => a + r.value, 0);
  return (
    <>
      <SampleBanner phase={14}>
        Estimated and actual costs are kept strictly separate. Nothing is deployed, so actual spend is zero and the billing export is not connected.
        The estimate below is a partial, illustrative calculation, not a quote.
      </SampleBanner>
      <div className="grid cols-2">
        <Panel title="Estimated monthly cost (model)" right={<Pill tone="info">ESTIMATE</Pill>}>
          <table>
            <tbody>
              {SAMPLE_COST.estimateMonthly.map((r) => (<tr key={r.item}><td>{r.item}<div className="muted small">{r.note}</div></td><td className="num">{fmtMoney(r.value)}</td></tr>))}
              <tr><td><strong>Partial total</strong></td><td className="num"><strong>{fmtMoney(total)}</strong></td></tr>
            </tbody>
          </table>
        </Panel>
        <Panel title="Actual billed cost" right={<Pill tone="muted">BILLING EXPORT</Pill>}>
          <Empty>Not connected. Enable the Cloud Billing export to the <span className="mono">cloudpulse_finops</span> BigQuery dataset (Terraform creates it) to show real spend here.</Empty>
        </Panel>
      </div>
      <Panel title="Optimization recommendations" right={<SampleBadge phase={14} />}>
        <ul>{SAMPLE_COST.recommendations.map((r) => (<li key={r.title}><strong>{r.title}</strong> <span className="muted">- {r.detail}</span></li>))}</ul>
      </Panel>
    </>
  );
}
