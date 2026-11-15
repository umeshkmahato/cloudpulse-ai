import { Suspense, lazy } from "react";
import { NavLink, Navigate, Route, Routes, useLocation } from "react-router-dom";
import { RefreshProvider, useRefresh } from "./refresh";
import { useAuth } from "./auth";
import { LoginPage } from "./components/LoginPage";
import { Pill } from "./components/ui";
const Executive = lazy(() => import("./pages/Executive"));
const Infrastructure = lazy(() => import("./pages/Infrastructure"));
const Performance = lazy(() => import("./pages/Performance"));
const Predictive = lazy(() => import("./pages/Predictive"));
const Assistant = lazy(() => import("./pages/Assistant"));
const Kubernetes = lazy(() => import("./pages/Kubernetes"));
const Cost = lazy(() => import("./pages/Cost"));
const Security = lazy(() => import("./pages/Security"));
const DisasterRecovery = lazy(() => import("./pages/DisasterRecovery"));
const DigitalTwin = lazy(() => import("./pages/DigitalTwin"));

export const PAGES = [
  { path: "/", title: "Executive Overview", tag: "LIVE", element: <Executive /> },
  { path: "/infrastructure", title: "Infrastructure Monitoring", tag: "LIVE", element: <Infrastructure /> },
  { path: "/performance", title: "Application Performance", tag: "LIVE", element: <Performance /> },
  { path: "/incidents", title: "Predictive Incidents", tag: "LIVE", element: <Predictive /> },
  { path: "/assistant", title: "AI Incident Assistant", tag: "LIVE", element: <Assistant /> },
  { path: "/kubernetes", title: "Kubernetes Health", tag: "P11", element: <Kubernetes /> },
  { path: "/cost", title: "Cloud Cost Intelligence", tag: "P2/14", element: <Cost /> },
  { path: "/security", title: "Security Events", tag: "P13", element: <Security /> },
  { path: "/dr", title: "Disaster Recovery", tag: "P14", element: <DisasterRecovery /> },
  { path: "/twin", title: "Architecture Digital Twin", tag: "LIVE", element: <DigitalTwin /> },
] as const;

function UserChip() {
  const { user, logout } = useAuth();
  if (!user) return null;
  return (
    <span className="row">
      <Pill tone="info" title="Roles are enforced by the gateway and the services; the UI only reflects them">{user.username} · {user.roles.join(", ")}</Pill>
      <button onClick={() => void logout()} aria-label="Log out">Log out</button>
    </span>
  );
}

function TopBar() {
  const { pathname } = useLocation();
  const { intervalMs, setIntervalMs } = useRefresh();
  const page = PAGES.find((p) => p.path === pathname);
  return (
    <header className="topbar">
      <div>
        <h1>{page?.title ?? "CloudPulse"}</h1>
        <div className="muted small">Environment: local (Docker Compose + Pub/Sub emulator) · not connected to GCP</div>
      </div>
      <div className="right">
        <Pill tone="info">LOCAL</Pill>
        <UserChip />
        <label className="row small muted">
          <span className="live-dot dot" style={{ color: intervalMs ? "var(--ok)" : "var(--muted)" }} aria-hidden />
          Auto-refresh
          <select value={intervalMs} onChange={(e) => setIntervalMs(Number(e.target.value))} aria-label="Auto-refresh interval">
            <option value={2000}>2 s</option>
            <option value={5000}>5 s</option>
            <option value={15000}>15 s</option>
            <option value={0}>Off</option>
          </select>
        </label>
      </div>
    </header>
  );
}

export default function App() {
  const { user, loading } = useAuth();
  if (loading) return <div className="empty" role="status">Loading…</div>;
  if (!user) return <LoginPage />;
  return (
    <RefreshProvider>
      <div className="shell">
        <aside className="sidebar">
          <div className="brand">
            <svg width="28" height="28" viewBox="0 0 32 32" aria-hidden>
              <circle cx="16" cy="16" r="14" fill="#0b1220" stroke="#38bdf8" strokeWidth="2" />
              <path d="M4 17h6l3-8 5 14 3-6h7" fill="none" stroke="#38bdf8" strokeWidth="2.5" strokeLinecap="round" />
            </svg>
            <div>CloudPulse AI<small>Operations Command Center</small></div>
          </div>
          <nav className="nav" aria-label="Primary">
            {PAGES.map((p) => (
              <NavLink key={p.path} to={p.path} end={p.path === "/"} className={({ isActive }) => (isActive ? "active" : "")}>
                <span>{p.title}</span>
                <span className="tag">{p.tag}</span>
              </NavLink>
            ))}
          </nav>
        </aside>
        <div className="main">
          <TopBar />
          <main className="content">
            <Suspense fallback={<div className="empty" role="status">Loading…</div>}>
              <Routes>
                {PAGES.map((p) => <Route key={p.path} path={p.path} element={p.element} />)}
                <Route path="*" element={<Navigate to="/" replace />} />
              </Routes>
            </Suspense>
          </main>
        </div>
      </div>
    </RefreshProvider>
  );
}
