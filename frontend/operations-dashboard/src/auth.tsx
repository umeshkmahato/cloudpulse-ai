import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

export type Role = "VIEWER" | "OPERATOR" | "ADMIN";
const RANK: Record<Role, number> = { VIEWER: 0, OPERATOR: 1, ADMIN: 2 };
export interface User { username: string; roles: Role[] }

export interface AuthState {
  user: User | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  /** True if the user holds at least this role. UI only: the gateway and services enforce it for real. */
  can: (role: Role) => boolean;
}

export const AuthContext = createContext<AuthState>({ user: null, loading: false, login: async () => {}, logout: async () => {}, can: () => false });
export const useAuth = () => useContext(AuthContext);

/** The session is an HttpOnly cookie set by identity-service: JavaScript never sees or stores the token (no XSS token theft). */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/auth/me", { credentials: "same-origin" })
      .then((r) => (r.ok ? r.json() : null))
      .then((u) => setUser(u ? { username: u.username, roles: u.roles } : null))
      .catch(() => setUser(null))
      .finally(() => setLoading(false));
    const onExpired = () => setUser(null);
    window.addEventListener("cp:unauthorized", onExpired);
    return () => window.removeEventListener("cp:unauthorized", onExpired);
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const res = await fetch("/auth/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ username, password }), credentials: "same-origin" });
    if (res.status === 429) throw new Error("Too many attempts. Try again in a few minutes.");
    if (!res.ok) throw new Error("Invalid username or password.");
    const u = await res.json();
    setUser({ username: u.username, roles: u.roles });
  }, []);

  const logout = useCallback(async () => {
    await fetch("/auth/logout", { method: "POST", headers: { "X-CloudPulse-CSRF": "1" }, credentials: "same-origin" }).catch(() => undefined);
    setUser(null);
  }, []);

  const value = useMemo<AuthState>(() => ({
    user, loading, login, logout,
    can: (role) => !!user && user.roles.some((r) => RANK[r] >= RANK[role]),
  }), [user, loading, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
