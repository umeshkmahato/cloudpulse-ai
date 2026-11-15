import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { AuthContext, type AuthState } from "./auth";

const asUser = (roles: ("VIEWER" | "OPERATOR" | "ADMIN")[] = ["ADMIN"]): AuthState => ({
  user: { username: "tester", roles }, loading: false, login: async () => {}, logout: async () => {}, can: () => true,
});

function renderAt(path: string, auth: AuthState = asUser()) {
  return render(<AuthContext.Provider value={auth}><MemoryRouter initialEntries={[path]}><App /></MemoryRouter></AuthContext.Provider>);
}

describe("dashboard shell", () => {
  beforeEach(() => {
    // Backend down: every fetch fails, the UI must degrade gracefully instead of crashing.
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new Error("offline"))));
  });
  afterEach(() => vi.unstubAllGlobals());

  it("shows the login page (and none of the dashboard) when nobody is signed in", () => {
    render(<AuthContext.Provider value={{ ...asUser(), user: null }}><MemoryRouter><App /></MemoryRouter></AuthContext.Provider>);
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Primary" })).not.toBeInTheDocument();
  });

  it("shows the signed-in identity and role in the top bar", () => {
    renderAt("/");
    expect(screen.getByText(/tester · ADMIN/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Log out" })).toBeInTheDocument();
  });

  it("lists all ten pages in the navigation", () => {
    renderAt("/");
    const nav = screen.getByRole("navigation", { name: "Primary" });
    expect(nav.querySelectorAll("a")).toHaveLength(10);
  });

  it("shows an error banner (not a crash) when the backend is unreachable", async () => {
    renderAt("/");
    expect(await screen.findByRole("alert")).toHaveTextContent(/unreachable/i);
  });

  it.each([
    ["/kubernetes", /SAMPLE DATA · Phase 11/],
    ["/cost", /SAMPLE DATA · Phase 14/],
    ["/security", /SAMPLE DATA · Phase 13/],
  ])("labels fixture-backed page %s as sample data", async (path, label) => {
    renderAt(path);
    expect((await screen.findAllByText(label)).length).toBeGreaterThan(0);
  });

  it("predictive page explains detection vs forecast and is not a sample fixture", async () => {
    renderAt("/incidents");
    expect(await screen.findByText(/detected anomaly/i)).toBeInTheDocument();
    expect(screen.queryByText(/SAMPLE DATA/)).not.toBeInTheDocument();
  });

  it("shows measured local DR results and marks RTO/RPO as experimental targets", async () => {
    renderAt("/dr");
    expect(await screen.findByText(/Experimental POC targets, not commitments/i)).toBeInTheDocument();
    expect(screen.getByText(/measured on a local kind cluster/i)).toBeInTheDocument();
    expect(screen.getByText(/Regional failover and traffic failover/)).toBeInTheDocument();
    expect(screen.getAllByText("Design only").length).toBeGreaterThan(0);
  });

  it("assistant page promises evidence-grounded answers and offers the eight questions", async () => {
    renderAt("/assistant");
    expect(await screen.findByText(/Evidence-grounded/)).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "What happens if the inventory service fails?" })).toBeInTheDocument();
    expect(screen.queryByText(/SAMPLE DATA/)).not.toBeInTheDocument();
  });

  it("digital twin degrades gracefully when the backend is down and states what it is", async () => {
    renderAt("/twin");
    expect(await screen.findByText(/Relational digital twin/)).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent(/unreachable/i);
  });
});
