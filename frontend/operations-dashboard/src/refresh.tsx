import { createContext, useContext, useState, type ReactNode } from "react";

const Ctx = createContext<{ intervalMs: number; setIntervalMs: (n: number) => void }>({ intervalMs: 5000, setIntervalMs: () => {} });

export function RefreshProvider({ children }: { children: ReactNode }) {
  const [intervalMs, setIntervalMs] = useState(5000);
  return <Ctx.Provider value={{ intervalMs, setIntervalMs }}>{children}</Ctx.Provider>;
}

export const useRefresh = () => useContext(Ctx);
