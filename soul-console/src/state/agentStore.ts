import { create } from 'zustand';
import { getAgents } from '../api/rest';
import type { AgentStatus, AgentStatusPayload, DelegationPayload } from '../api/types';

export interface AgentView {
  role: string;
  model: string;
  status: AgentStatus;
  description: string | null;
  task: string | null;
  toolNote: string | null;
  /** Timestamp when the agent entered a busy state — drives the elapsed timer. */
  since: number | null;
}

export interface DelegationView extends DelegationPayload {
  at: number;
}

const MAX_DELEGATIONS = 20;
const BUSY: ReadonlySet<AgentStatus> = new Set(['thinking', 'delegating', 'working']);

interface AgentState {
  agents: Record<string, AgentView>;
  delegations: DelegationView[];
  lastDelegation: DelegationView | null;
  hydrate: () => Promise<void>;
  applyStatus: (role: string, payload: AgentStatusPayload) => void;
  applyDelegation: (payload: DelegationPayload) => void;
  applyTool: (role: string, note: string | null) => void;
  setModel: (role: string, model: string) => void;
}

export const useAgentStore = create<AgentState>((set) => ({
  agents: {},
  delegations: [],
  lastDelegation: null,

  async hydrate() {
    try {
      const infos = await getAgents();
      set((s) => {
        const agents: Record<string, AgentView> = {};
        for (const info of infos) {
          const prev = s.agents[info.role];
          agents[info.role] = {
            role: info.role,
            model: info.model,
            status: prev?.status ?? info.status,
            description: info.description ?? null,
            task: prev?.task ?? info.task ?? null,
            toolNote: prev?.toolNote ?? null,
            since: prev?.since ?? null,
          };
        }
        return { agents };
      });
    } catch {
      /* backend down — ErrorBanner reflects the WS state */
    }
  },

  applyStatus(role, payload) {
    set((s) => {
      const prev: AgentView = s.agents[role] ?? {
        role,
        model: '—',
        status: 'idle',
        description: null,
        task: null,
        toolNote: null,
        since: null,
      };
      const nowBusy = BUSY.has(payload.status);
      const wasBusy = BUSY.has(prev.status);
      return {
        agents: {
          ...s.agents,
          [role]: {
            ...prev,
            status: payload.status,
            task: payload.task !== undefined ? payload.task : nowBusy ? prev.task : null,
            toolNote: nowBusy ? prev.toolNote : null,
            since: nowBusy ? (wasBusy ? prev.since : Date.now()) : null,
          },
        },
      };
    });
  },

  applyDelegation(payload) {
    set((s) => {
      const delegation: DelegationView = { ...payload, at: Date.now() };
      return {
        delegations: [...s.delegations, delegation].slice(-MAX_DELEGATIONS),
        lastDelegation: delegation,
      };
    });
  },

  applyTool(role, note) {
    set((s) => {
      const prev = s.agents[role];
      if (!prev) return s;
      return { agents: { ...s.agents, [role]: { ...prev, toolNote: note } } };
    });
  },

  setModel(role, model) {
    set((s) => {
      const prev = s.agents[role];
      if (!prev) return s;
      return { agents: { ...s.agents, [role]: { ...prev, model } } };
    });
  },
}));
