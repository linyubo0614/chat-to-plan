// One private desktop connector per deployment. Planner credentials are checked
// by the local backend. No task bodies or credentials are persisted in the relay.
const LIMIT = 512 * 1024;
const json = (value, status = 200) => new Response(JSON.stringify(value), {
  status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
});

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/bridge") {
      if (!env.BRIDGE_SECRET || request.headers.get("Authorization") !== `Bearer ${env.BRIDGE_SECRET}`)
        return json({ error: "Unauthorized connector" }, 401);
      if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket")
        return json({ error: "WebSocket required" }, 426);
    } else if (url.pathname !== "/healthz" && !url.pathname.startsWith("/v1/")) {
      return json({ error: "Not found" }, 404);
    }
    return env.DESKTOP.get(env.DESKTOP.idFromName("owner")).fetch(request);
  },
};

export class DesktopRelay {
  constructor(state) { this.state = state; this.pending = new Map(); }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/bridge") {
      for (const old of this.state.getWebSockets()) old.close(1000, "Connector replaced");
      this.failPending();
      const pair = new WebSocketPair();
      this.state.acceptWebSocket(pair[1]);
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    if (!["GET", "POST", "PUT", "DELETE"].includes(request.method)) return json({ error: "Method not allowed" }, 405);
    const socket = this.state.getWebSockets()[0];
    if (!socket) return json({ error: "Desktop offline" }, 503);
    if (this.pending.size >= 32) return json({ error: "Relay busy" }, 503);
    if (Number(request.headers.get("Content-Length")) > LIMIT) return json({ error: "Request too large" }, 413);
    const reader = request.body?.getReader();
    const chunks = []; let size = 0;
    if (reader) {
      while (true) {
        const { done, value } = await reader.read(); if (done) break;
        size += value.byteLength;
        if (size > LIMIT) { await reader.cancel(); return json({ error: "Request too large" }, 413); }
        chunks.push(value);
      }
    }
    const bytes = new Uint8Array(size); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    const id = crypto.randomUUID();
    const job = { id, method: request.method, path: url.pathname + url.search,
      authorization: request.headers.get("Authorization") || "",
      contentType: request.headers.get("Content-Type") || "application/json",
      body: new TextDecoder().decode(bytes) };
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(id); resolve(json({ error: "Desktop response unconfirmed" }, 504));
      }, 40000);
      this.pending.set(id, { resolve, timer, socket });
      try { socket.send(JSON.stringify(job)); }
      catch { clearTimeout(timer); this.pending.delete(id); resolve(json({ error: "Desktop offline" }, 503)); }
    });
  }

  webSocketMessage(socket, message) {
    if (typeof message !== "string" || message.length > 2 * LIMIT) return;
    let data;
    try { data = JSON.parse(message); } catch { return; }
    const pending = this.pending.get(data.id);
    if (!pending || pending.socket !== socket) return;
    this.pending.delete(data.id); clearTimeout(pending.timer);
    try {
      if (!Number.isInteger(data.status) || data.status < 200 || data.status > 599 || typeof data.body !== "string") throw Error();
      const binary = atob(data.body);
      if (binary.length > LIMIT) throw Error();
      const body = Uint8Array.from(binary, (x) => x.charCodeAt(0));
      pending.resolve(new Response([204, 205, 304].includes(data.status) ? null : body, {
        status: data.status, headers: { "Content-Type": data.contentType || "application/json", "Cache-Control": "no-store" },
      }));
    } catch { pending.resolve(json({ error: "Invalid desktop response" }, 502)); }
  }

  failPending(socket) {
    for (const [id, pending] of this.pending) {
      if (socket && pending.socket !== socket) continue;
      clearTimeout(pending.timer); this.pending.delete(id);
      pending.resolve(json({ error: "Desktop disconnected; result unconfirmed" }, 503));
    }
  }
  webSocketClose(socket) { this.failPending(socket); }
  webSocketError(socket) { this.failPending(socket); }
}
