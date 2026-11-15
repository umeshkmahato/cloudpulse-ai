// Shapes returned by operations-service / order-service / inventory-service (see Swagger UIs).
export interface Overview {
  windowMinutes: number;
  orders: { orders: number; order_value: number };
  inventoryOutcomes: { status: string; count: number }[];
  metrics: { samples: number; includes_synthetic: boolean };
  dataSource: string;
}
export interface OrderBucket { bucket: string; orders: number; order_value: number }
export interface Outcome { status: string; reason: string; count: number }
export interface MetricBucket { bucket: string; avg: number; max: number; samples: number; synthetic: boolean }
export interface LatencyRow { type: string; events: number; p50_ms: number; p95_ms: number }
export interface StreamEvent { type: string; event_id: string; occurred_at: string; correlation_id: string | null; summary: string }
export interface ServiceHealth {
  service: string;
  status: "UP" | "DEGRADED" | "STALE";
  reasons: string[];
  lastSampleAt: string;
  containsSyntheticData: boolean;
}
export interface LatestSample { service: string; metric: string; value: number; unit: string | null; synthetic: boolean; observedAt: string }
export interface StockItem { sku: string; description: string; available: number; updatedAt: string }
export interface OrderView { orderId: string; status: string; statusReason: string | null; totalAmount: number; currency: string }

export interface MetricFinding {
  metric: string; current: number; baselineMean: number; baselineStd: number; baselineSamples: number;
  zScore: number; relativeDeviation: number; ewma: number; ewmaUpperControlLimit: number;
  anomalous: boolean; riskContribution: number; explanation: string;
}
export interface Forecast { metric: string; current: number; slopePerMinute: number; r2: number; samples: number; targetPct: number; etaMinutes: number; confidence: string; explanation: string }
export interface Recommendation { actionId: string; title: string; rationale: string; requiresApproval: boolean }
export interface Assessment {
  service: string; status: "OK" | "WATCH" | "ANOMALY" | "FORECAST" | "INSUFFICIENT_DATA" | "STALE";
  riskScore: number; severity: string; evaluatedAt: string; findings: MetricFinding[]; forecasts: Forecast[]; notes: string[]; containsSyntheticData: boolean;
}
export interface CurrentState { assessment: Assessment; recommendations: Recommendation[]; affectedServices: string[] }
export interface Incident {
  id: string; service: string; kind: "DETECTED" | "FORECAST"; kindExplanation: string; severity: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  riskScore: number; peakRisk: number; status: "OPEN" | "RESOLVED"; openedAt: string; updatedAt: string; resolvedAt: string | null;
  summary: string; affectedServices: string[]; evidence: { findings: MetricFinding[]; forecasts: Forecast[]; notes: string[]; method: string };
  recommendations: Recommendation[]; synthetic: boolean;
}

export interface Evidence { id: string; type: string; source: string; retrievedAt: string; summary: string; data: unknown; synthetic: boolean }
export interface AssistantAnswer {
  answerId: string; question: string; intent: string; service: string | null; answer: string;
  claims: { text: string; evidenceIds: string[] }[]; evidence: Evidence[]; confidence: "HIGH" | "MEDIUM" | "LOW" | "NONE";
  limitations: string[]; provider: string; containsSyntheticData: boolean; generatedAt: string;
}
export interface TwinComponent { id: string; name: string; kind: string; description: string; deploymentStatus: string; criticality: string; parentId: string | null; uiX: number; uiY: number }
export interface TwinGraph {
  nodes: { component: TwinComponent; liveStatus: string; riskScore: number | null }[];
  edges: { fromId: string; toId: string; relation: string; mode: "SYNC" | "ASYNC"; description: string }[];
  note: string;
}
export interface Ref { id: string; name: string; kind: string }
export interface Step { kind: string; stepNo: number; text: string }
export interface TwinImpact {
  failed: Ref; scope: string; hardImpacted: Ref[]; delayedImpacted: Ref[];
  transactions: { id: string; name: string; status: "BLOCKED" | "DELAYED"; businessImpact: string; revenueImpacting: boolean }[];
  messageRecovery: Step[]; recovery: Step[]; mitigation: Step[]; businessImpact: string; notes: string[];
}

export interface RemediationRow {
  id: string; incidentId: string; service: string; action: string; status: string; deployment: string; fromReplicas: number; toReplicas: number;
  riskScore: number; incidentKind: string; rationale: string; decidedBy: string | null; decisionComment: string | null; policy: string | null;
  executedAt: string | null; observedReplicas: number | null; timeToRecoverSeconds: number | null; outcome: string | null; outcomeDetail: string | null;
  synthetic: boolean; createdAt: string; expiresAt: string;
}
export interface RemediationView { remediation: RemediationRow; guardrails: string[]; nextStep: string }
export interface ControllerStatus {
  mode: string; killSwitch: boolean; approvalsEnabled: boolean; allowList: Record<string, { minReplicas: number; maxReplicas: number; maxStep: number }>;
  automatedPolicy: { autoApprove: boolean; name: string }; cooldownMinutes: number; maxActionsPerHour: number; monitorWindowMinutes: number; executableActions: string[];
}
export interface AuditRow { id: number; at: string; actor: string; event: string; details: string }
