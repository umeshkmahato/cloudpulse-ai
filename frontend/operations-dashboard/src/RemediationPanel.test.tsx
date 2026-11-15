import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthState, type Role } from "./auth";
import { RemediationPanel } from "./components/RemediationPanel";

const row = {
  remediation: { id: "r-1", incidentId: "i-1", service: "order-service", action: "SCALE_DEPLOYMENT", status: "PROPOSED", deployment: "order-service", fromReplicas: 2, toReplicas: 4,
    riskScore: 95, incidentKind: "DETECTED", rationale: "CPU saturation", decidedBy: null, decisionComment: null, policy: null, executedAt: null, observedReplicas: null,
    timeToRecoverSeconds: null, outcome: null, outcomeDetail: null, synthetic: true, createdAt: "2026-01-01T00:00:00Z", expiresAt: "2099-01-01T00:00:00Z" },
  guardrails: ["allow-list: ok", "bounds ok: 2 -> 4 replicas"], nextStep: "Awaiting approval",
};
const status = { mode: "enforce", killSwitch: false, approvalsEnabled: true, allowList: { "order-service": { minReplicas: 2, maxReplicas: 6, maxStep: 2 } },
  automatedPolicy: { autoApprove: false, name: "p" }, cooldownMinutes: 10, maxActionsPerHour: 3, monitorWindowMinutes: 5, executableActions: ["SCALE_DEPLOYMENT"] };

const RANK = { VIEWER: 0, OPERATOR: 1, ADMIN: 2 };
const auth = (role: Role): AuthState => ({ user: { username: "alice", roles: [role] }, loading: false, login: async () => {}, logout: async () => {}, can: (r) => RANK[role] >= RANK[r] });

describe("RemediationPanel", () => {
  const calls: { url: string; init?: RequestInit }[] = [];
  beforeEach(() => {
    calls.length = 0;
    vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
      calls.push({ url, init });
      if (init?.method === "POST") return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) });
      if (url.includes("/remediation/status")) return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(status) });
      if (url.includes("/audit")) return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([]) });
      return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve([row]) });
    }));
  });
  afterEach(() => vi.unstubAllGlobals());

  const renderAs = (role: Role) => render(<AuthContext.Provider value={auth(role)}><RemediationPanel /></AuthContext.Provider>);

  it("shows the restricted action scope", async () => {
    renderAs("OPERATOR");
    expect(await screen.findByText(/Executable action: only/)).toHaveTextContent("order-service (2-6, step ≤ 2)");
    expect(screen.getByText("human approval required")).toBeInTheDocument();
    expect(screen.getByText("SYNTHETIC")).toBeInTheDocument();
  });

  it("a viewer cannot approve: buttons are disabled and the role requirement is explained", async () => {
    renderAs("VIEWER");
    expect(await screen.findByRole("button", { name: "Approve" })).toBeDisabled();
    expect(screen.getByText(/approving needs the operator role/)).toBeInTheDocument();
  });

  it("an operator approves as themselves: no token field, session cookie + CSRF header, identity not taken from the form", async () => {
    renderAs("OPERATOR");
    expect(screen.queryByLabelText("Approver token")).not.toBeInTheDocument();
    const approve = await screen.findByRole("button", { name: "Approve" });
    await waitFor(() => expect(approve).toBeEnabled());
    await userEvent.click(approve);
    await waitFor(() => expect(calls.some((c) => c.init?.method === "POST")).toBe(true));
    const post = calls.find((c) => c.init?.method === "POST")!;
    expect(post.url).toContain("/remediations/r-1/approve");
    const headers = post.init!.headers as Record<string, string>;
    expect(headers["X-CloudPulse-CSRF"]).toBe("1");
    expect(headers.Authorization).toBeUndefined();            // the browser never handles the token
    expect(post.init!.credentials).toBe("same-origin");
    expect(Object.keys(JSON.parse(post.init!.body as string))).toEqual(["comment"]);   // no operator field to spoof
  });
});
