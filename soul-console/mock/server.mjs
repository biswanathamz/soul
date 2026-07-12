/**
 * Mock soul-orchestrator — implements the SPEC §5 REST + WS contracts so the UI
 * can be built, demoed, and e2e-tested before the Spring Boot backend exists (TDD §9, §11).
 *
 *   node mock/server.mjs        # listens on :7788 (same port the real backend will use)
 */
import http from 'node:http';
import crypto from 'node:crypto';
import { WebSocketServer } from 'ws';

const PORT = Number(process.env.PORT ?? 7788);

const agents = {
  super: { model: 'llama3.1', description: 'Manager — plans, delegates, synthesizes' },
  coder: { model: 'qwen2.5-coder', description: 'Writes and reviews code' },
  researcher: { model: 'llama3.1', description: 'Finds and summarizes information' },
  writer: { model: 'gemma2', description: 'Long-form writing and drafts' },
  analyst: { model: 'deepseek-r1', description: 'Reasoning, math, planning' },
  sysops: { model: 'llama3.1', description: 'Local system tasks' },
};
const models = ['llama3.1', 'qwen2.5-coder', 'gemma2', 'deepseek-r1', 'mistral', 'phi3'];
const conversations = new Map(); // id -> { id, messages: [] }
const sockets = new Set();

const uuid = () => crypto.randomUUID();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function broadcast(evt) {
  const frame = JSON.stringify(evt);
  for (const ws of sockets) if (ws.readyState === 1) ws.send(frame);
}

/* ---------------- canned scenarios ---------------- */

const CODER_REPLY = `The coder agent has your function ready. Memoized so repeated calls are effectively free:

\`\`\`python
from functools import lru_cache

@lru_cache(maxsize=None)
def fibonacci(n: int) -> int:
    """Return the n-th Fibonacci number (0-indexed)."""
    if n < 0:
        raise ValueError("n must be non-negative")
    if n < 2:
        return n
    return fibonacci(n - 1) + fibonacci(n - 2)


if __name__ == "__main__":
    print([fibonacci(i) for i in range(10)])
\`\`\`

Key points:

- \`lru_cache\` turns the naive exponential recursion into **O(n)**.
- Raises \`ValueError\` for negative input instead of recursing forever.
- For very large \`n\`, switch to the iterative form to avoid recursion limits.`;

const RESEARCH_REPLY = `Here's the researcher agent's summary on the current state of local LLMs:

- **Small models got good.** 7–14B models now handle everyday assistant tasks that needed cloud models a year ago.
- **Quantization is the unlock** — 4-bit GGUF builds run 8B models comfortably on 8–16 GB of RAM.
- **Tooling matured**: Ollama-style runtimes make serving a local model a one-line affair.

| Model | Sweet spot | Typical RAM |
| --- | --- | --- |
| llama3.1 8B | general chat, orchestration | ~8 GB |
| qwen2.5-coder | code generation | ~9 GB |
| deepseek-r1 | multi-step reasoning | ~10 GB |

Everything above runs fully offline — which is exactly the bet SOUL is making.`;

const WRITER_REPLY = `The writer agent drafted this for you:

> **Subject:** Project SOUL — kicking off
>
> Team,
>
> We're starting work on SOUL, a locally hosted assistant with a manager agent that
> delegates to specialist sub-agents — all running on local models, so nothing leaves
> our machines. The UI is live first; the orchestrator follows.
>
> I'll share a demo at the end of the week. Questions and ideas welcome.
>
> — B

Want it more formal, shorter, or with a timeline section?`;

const GENERAL_REPLY = `All systems nominal. I'm **SOUL** — your local mission control.

Ask me to *write code*, *research a topic*, or *draft a document*, and I'll route the work to the right specialist agent. Everything runs on your machine; nothing leaves it.`;

function scenarioFor(text) {
  const t = text.toLowerCase();
  if (/(code|function|bug|script|python|regex|fibonacci)/.test(t)) {
    return {
      delegate: 'coder',
      instruction: 'Write the requested function with tests in mind',
      tool: { name: 'file_ops.write', args: 'fibonacci.py' },
      reply: CODER_REPLY,
    };
  }
  if (/(research|search|find|news|state of|what is|compare)/.test(t)) {
    return {
      delegate: 'researcher',
      instruction: 'Research the topic and summarize findings',
      tool: { name: 'web_fetch', args: 'top sources' },
      reply: RESEARCH_REPLY,
    };
  }
  if (/(write|draft|email|doc|letter|blog)/.test(t)) {
    return {
      delegate: 'writer',
      instruction: 'Draft the requested text in a friendly tone',
      tool: null,
      reply: WRITER_REPLY,
    };
  }
  return { delegate: null, instruction: null, tool: null, reply: GENERAL_REPLY };
}

async function runScenario(conversationId, text) {
  const sc = scenarioFor(text);
  const assistantMessageId = uuid();
  const say = (type, agent, payload) => broadcast({ type, conversationId, agent, payload });

  say('agent.status', 'super', { status: 'thinking', task: 'Understanding request' });
  await sleep(600);

  if (sc.delegate) {
    say('agent.status', 'super', { status: 'delegating', task: `Routing to ${sc.delegate}` });
    say('delegation', 'super', {
      id: uuid(),
      from: 'super',
      to: sc.delegate,
      instruction: sc.instruction,
    });
    await sleep(400);
    say('agent.status', sc.delegate, { status: 'working', task: sc.instruction });
    if (sc.tool) {
      await sleep(700);
      say('tool.call', sc.delegate, { tool: sc.tool.name, args: sc.tool.args });
      await sleep(900);
      say('tool.result', sc.delegate, { tool: sc.tool.name, summary: 'ok' });
    }
    await sleep(1100);
    say('agent.status', sc.delegate, { status: 'done', task: null });
    say('agent.status', 'super', { status: 'thinking', task: 'Synthesizing answer' });
    await sleep(400);
  }

  // Stream the reply word-by-word, then commit with task.done.
  const chunks = sc.reply.split(/(?<=\s)/);
  for (const chunk of chunks) {
    say('token', 'super', { messageId: assistantMessageId, token: chunk });
    await sleep(18);
  }
  say('task.done', 'super', { messageId: assistantMessageId, text: sc.reply });
  say('agent.status', 'super', { status: 'idle' });
  if (sc.delegate) {
    await sleep(600);
    say('agent.status', sc.delegate, { status: 'idle' });
  }

  const conversation = conversations.get(conversationId);
  conversation?.messages.push({
    id: assistantMessageId,
    role: 'assistant',
    text: sc.reply,
    createdAt: new Date().toISOString(),
  });
}

/* ---------------- HTTP ---------------- */

function json(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': 'http://localhost:7787',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
  });
  res.end(data);
}

async function readBody(req) {
  const parts = [];
  for await (const part of req) parts.push(part);
  try {
    return JSON.parse(Buffer.concat(parts).toString('utf8') || '{}');
  } catch {
    return {};
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const path = url.pathname;

  if (req.method === 'OPTIONS') return json(res, 204, {});

  if (req.method === 'GET' && path === '/actuator/health') {
    return json(res, 200, { status: 'UP' });
  }

  if (req.method === 'GET' && path === '/api/v1/agents') {
    return json(
      res,
      200,
      Object.entries(agents).map(([role, a]) => ({
        role,
        model: a.model,
        status: 'idle',
        description: a.description,
        task: null,
      })),
    );
  }

  if (req.method === 'GET' && path === '/api/v1/models') {
    return json(res, 200, models.map((name) => ({ name })));
  }

  if (req.method === 'POST' && path === '/api/v1/chat') {
    const body = await readBody(req);
    if (!body.text || typeof body.text !== 'string') {
      return json(res, 400, { message: 'text is required' });
    }
    const conversationId = body.conversationId ?? uuid();
    if (!conversations.has(conversationId)) {
      conversations.set(conversationId, { id: conversationId, messages: [] });
    }
    const messageId = uuid();
    conversations.get(conversationId).messages.push({
      id: messageId,
      role: 'user',
      text: body.text,
      createdAt: new Date().toISOString(),
    });
    void runScenario(conversationId, body.text);
    return json(res, 200, { conversationId, messageId });
  }

  const conversationMatch = path.match(/^\/api\/v1\/conversations\/([^/]+)$/);
  if (req.method === 'GET' && conversationMatch) {
    const conversation = conversations.get(decodeURIComponent(conversationMatch[1]));
    if (!conversation) return json(res, 404, { message: 'conversation not found' });
    return json(res, 200, conversation);
  }

  const rebindMatch = path.match(/^\/api\/v1\/agents\/([^/]+)\/model$/);
  if (req.method === 'PUT' && rebindMatch) {
    const role = decodeURIComponent(rebindMatch[1]);
    if (!agents[role]) return json(res, 404, { message: `unknown agent: ${role}` });
    const body = await readBody(req);
    if (!body.model) return json(res, 400, { message: 'model is required' });
    agents[role].model = body.model;
    return json(res, 200, { role, model: body.model, status: 'idle', description: agents[role].description });
  }

  return json(res, 404, { message: `no route: ${req.method} ${path}` });
});

/* ---------------- WebSocket ---------------- */

const wss = new WebSocketServer({ server, path: '/ws/stream' });
wss.on('connection', (ws) => {
  sockets.add(ws);
  ws.on('close', () => sockets.delete(ws));
});

// Keepalive pings so proxies don't drop idle connections.
setInterval(() => {
  for (const ws of sockets) if (ws.readyState === 1) ws.ping();
}, 15000);

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[mock soul-orchestrator] http://localhost:${PORT}  (ws: /ws/stream)`);
});
