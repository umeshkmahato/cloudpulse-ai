import { act, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthProvider, useAuth } from "./auth";

function Probe() {
  const { user, loading, can } = useAuth();
  if (loading) return <div>loading</div>;
  return <div>{user ? `${user.username}:${can("OPERATOR") ? "op" : "ro"}` : "anonymous"}</div>;
}

describe("AuthProvider", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("starts anonymous when /auth/me says 401", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({ ok: false, status: 401, json: () => Promise.resolve({}) })));
    render(<AuthProvider><Probe /></AuthProvider>);
    expect(await screen.findByText("anonymous")).toBeInTheDocument();
  });

  it("restores the session from the cookie-backed /auth/me and derives role checks", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ username: "vera", roles: ["VIEWER"] }) })));
    render(<AuthProvider><Probe /></AuthProvider>);
    expect(await screen.findByText("vera:ro")).toBeInTheDocument();
  });

  it("drops back to the login state when any API reports the session expired", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ username: "oscar", roles: ["OPERATOR"] }) })));
    render(<AuthProvider><Probe /></AuthProvider>);
    expect(await screen.findByText("oscar:op")).toBeInTheDocument();
    act(() => { window.dispatchEvent(new Event("cp:unauthorized")); });
    await waitFor(() => expect(screen.getByText("anonymous")).toBeInTheDocument());
  });
});
