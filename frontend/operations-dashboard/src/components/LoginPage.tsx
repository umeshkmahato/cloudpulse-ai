import { useState, type FormEvent } from "react";
import { useAuth } from "../auth";

export function LoginPage() {
  const { login } = useAuth();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError(null);
    try { await login(username.trim(), password); }
    catch (err) { setError(err instanceof Error ? err.message : "Login failed."); setPassword(""); }
    finally { setBusy(false); }
  }

  return (
    <main style={{ minHeight: "100%", display: "grid", placeItems: "center", padding: 16 }}>
      <form className="panel" onSubmit={submit} style={{ width: "min(380px, 100%)", display: "grid", gap: 12 }} aria-labelledby="login-title">
        <div className="row" style={{ gap: 10 }}>
          <svg width="28" height="28" viewBox="0 0 32 32" aria-hidden><circle cx="16" cy="16" r="14" fill="#0b1220" stroke="#38bdf8" strokeWidth="2" /><path d="M4 17h6l3-8 5 14 3-6h7" fill="none" stroke="#38bdf8" strokeWidth="2.5" strokeLinecap="round" /></svg>
          <div><h1 id="login-title">CloudPulse AI</h1><div className="muted small">Operations Command Center · sign in</div></div>
        </div>
        <label className="small muted" htmlFor="u">Username</label>
        <input id="u" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required maxLength={80} autoFocus />
        <label className="small muted" htmlFor="p">Password</label>
        <input id="p" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required maxLength={200} />
        {error && <div className="banner error" role="alert">{error}</div>}
        <button className="primary" disabled={busy || !username || !password}>{busy ? "Signing in…" : "Sign in"}</button>
        <div className="muted small">Roles: viewer (read), operator (place orders, approve remediations), admin (kill switch, telemetry injection). Sessions last 30 minutes.</div>
      </form>
    </main>
  );
}
