/* Online play, the page's side of the wire: signing in, your name, the `duel` function, and hearing when a
 * duel changes. No libraries, only fetch and WebSocket, so the page stays one self-contained file.
 *
 *   const ON = RSTCGOnline.make({ load, save })   // where the sign-in is kept: load() -> auth or null, save(auth)
 *   ON.server                  { url, key }: the Supabase project. The publishable key is public by design;
 *                              the secret key never leaves the server. Tests point this elsewhere with
 *                              window.RSTCG_ONLINE_SERVER = { url, key } before the page loads.
 *   await ON.user()            your user id (signs in anonymously the first time: accounts come later)
 *   await ON.call(op, body)    the duel function's answer (server/supabase/functions/duel/index.js). Throws an
 *                              Error with .code: 'net' (can't reach the server), 'auth', or the server's own code
 *   await ON.setName(name)     saves your name. Throws .code 'taken' or 'shape' (the database's rules)
 *   const stop = ON.watch(id, (row) => {})   the duel's database row each time it changes (Supabase Realtime)
 *   ON.live(id)                is that watch connected right now? (if not, the page polls instead)
 *   ON.wake()                  reconnect now (the page came back from the background)
 */
(function (root) {
  'use strict';
  const SERVER = { url: 'https://udmaqoxyhiwpvxkrugwr.supabase.co', key: 'sb_publishable_eaN07rbCUAT_-FtdXkB2Iw_IjdGi_bJ' };
  const HEARTBEAT = 25e3;          // Realtime drops a connection that stays quiet for a minute

  const oops = (code, message, status) => Object.assign(new Error(message || code), { code, status });

  function make(o = {}) {
    const server = Object.assign({}, SERVER, root.RSTCG_ONLINE_SERVER || {}, o.server || {});
    const load = o.load || (() => null), save = o.save || (() => {});
    let auth = load();             // { access_token, refresh_token, expires_at (seconds), uid }
    let pending = null;            // a sign-in or refresh under way, shared by everyone who needs it

    async function http(path, init) {
      let r;
      try { r = await fetch(server.url + path, init); } catch (e) { throw oops('net', "Can't reach the game server."); }
      const text = await r.text().catch(() => '');
      let body = null;
      try { body = text ? JSON.parse(text) : null; } catch (e) { body = { message: text }; }
      return { ok: r.ok, status: r.status, body };
    }
    const json = (extra) => Object.assign({ apikey: server.key, 'Content-Type': 'application/json' }, extra || {});

    // ---------- signing in ----------
    function keep(s) {
      auth = { access_token: s.access_token, refresh_token: s.refresh_token, uid: s.user ? s.user.id : auth && auth.uid,
        expires_at: s.expires_at || Math.floor(Date.now() / 1000) + (s.expires_in || 3600) };
      save(auth);
      rt.token();
      return auth;
    }
    async function signUp() {
      const r = await http('/auth/v1/signup', { method: 'POST', headers: json(), body: JSON.stringify({ data: {} }) });
      if (!r.ok || !r.body || !r.body.access_token) throw oops(r.status === 429 ? 'busy' : 'signin', 'Could not sign in to the game server.', r.status);
      return keep(r.body);
    }
    async function refresh() {
      const r = await http('/auth/v1/token?grant_type=refresh_token', { method: 'POST', headers: json(), body: JSON.stringify({ refresh_token: auth.refresh_token }) });
      if (r.ok && r.body && r.body.access_token) return keep(r.body);
      if (r.status >= 400 && r.status < 500 && r.status !== 429) { auth = null; save(null); return signUp(); }   // that sign-in is gone
      throw oops('net', "Can't reach the game server.", r.status);
    }
    function session(force) {
      const stored = load();       // another tab may have refreshed already: never use a refresh token twice
      if (stored && stored.access_token && (!auth || stored.expires_at > auth.expires_at)) auth = stored;
      if (pending) return pending;
      const stale = !auth || !auth.access_token ? signUp : force || auth.expires_at - 90 < Date.now() / 1000 ? refresh : null;
      if (!stale) return Promise.resolve(auth);
      pending = stale().finally(() => { pending = null; });
      return pending;
    }
    const user = async () => (await session()).uid;

    // ---------- the duel function and the names table ----------
    async function call(op, body) {
      for (let attempt = 0; ; attempt++) {
        const a = await session(attempt > 0);
        const r = await http('/functions/v1/duel', { method: 'POST', headers: json({ Authorization: `Bearer ${a.access_token}` }), body: JSON.stringify(Object.assign({}, body || {}, { op })) });
        if (r.ok) return r.body;
        if (r.status === 401 && attempt === 0) continue;        // an old token: refresh it and try once more
        const b = r.body || {};
        throw oops(b.error || (r.status === 401 ? 'auth' : 'server'), b.message || 'Something went wrong on the server.', r.status);
      }
    }
    async function setName(name) {
      const a = await session();
      const r = await http('/rest/v1/profiles', { method: 'POST', body: JSON.stringify({ id: a.uid, name }),
        headers: json({ Authorization: `Bearer ${a.access_token}`, Prefer: 'resolution=merge-duplicates,return=minimal' }) });
      if (r.ok) return name;
      const b = r.body || {};
      if (b.code === '23505') throw oops('taken', 'That name is taken.', r.status);
      if (b.code === '23514') throw oops('shape', "That name won't do.", r.status);
      throw oops(r.status === 401 ? 'auth' : 'server', 'Could not save your name.', r.status);
    }

    // ---------- Realtime: hearing a duel's row change (Phoenix channels, the JSON protocol) ----------
    const rt = { ws: null, open: false, ref: 0, topics: new Map(), retry: null, beat: null, tries: 0 };
    const nextRef = () => String(++rt.ref);
    rt.send = (m) => { if (rt.ws && rt.open) try { rt.ws.send(JSON.stringify(m)); } catch (e) { /* closing */ } };
    rt.token = () => { if (auth) for (const t of rt.topics.values()) if (t.joined) rt.send({ topic: t.topic, event: 'access_token', payload: { access_token: auth.access_token }, ref: nextRef() }); };
    function connect() {
      clearTimeout(rt.retry);
      if (rt.ws || !rt.topics.size || typeof WebSocket === 'undefined') return;
      let ws;
      try { ws = new WebSocket(`${server.url.replace(/^http/, 'ws')}/realtime/v1/websocket?apikey=${encodeURIComponent(server.key)}&vsn=1.0.0`); } catch (e) { later(); return; }
      rt.ws = ws;
      ws.onopen = () => {
        rt.open = true; rt.tries = 0;
        for (const t of rt.topics.values()) join(t);
        rt.beat = setInterval(() => rt.send({ topic: 'phoenix', event: 'heartbeat', payload: {}, ref: nextRef() }), HEARTBEAT);
      };
      ws.onmessage = (e) => { let m = null; try { m = JSON.parse(e.data); } catch (x) { return; } if (m) heard(m); };
      ws.onclose = () => {
        clearInterval(rt.beat);
        if (rt.ws === ws) { rt.ws = null; rt.open = false; }
        for (const t of rt.topics.values()) t.joined = false;
        later();
      };
      ws.onerror = () => {};
    }
    function later() {
      clearTimeout(rt.retry);
      if (rt.topics.size) rt.retry = setTimeout(connect, Math.min(30e3, 1000 * 2 ** rt.tries++));
    }
    async function join(t) {
      const a = await session().catch(() => null);
      if (!a || !rt.open || rt.topics.get(t.topic) !== t) return;
      t.ref = nextRef();
      rt.send({ topic: t.topic, event: 'phx_join', ref: t.ref, join_ref: t.ref, payload: {
        config: { broadcast: { ack: false, self: false }, presence: { key: '', enabled: false },
          postgres_changes: [{ event: 'UPDATE', schema: 'public', table: 'matches', filter: `id=eq.${t.id}` }], private: false },
        access_token: a.access_token } });
    }
    function rejoin(t) { t.joined = false; clearTimeout(t.again); t.again = setTimeout(() => join(t), 4000); }
    function heard(m) {
      const t = rt.topics.get(m.topic);
      if (!t) return;
      const p = m.payload || {};
      if (m.event === 'phx_reply' && m.ref === t.ref) { if (p.status === 'ok') t.joined = true; else rejoin(t); return; }
      if (m.event === 'system' && p.status && p.status !== 'ok') { rejoin(t); return; }
      if (m.event === 'phx_error' || m.event === 'phx_close') { rejoin(t); return; }
      if (m.event === 'postgres_changes') {
        const row = p.data && p.data.record;
        if (row) for (const cb of t.cbs) try { cb(row); } catch (e) { console.error(e); }
      }
    }
    function watch(id, cb) {
      const topic = `realtime:match-${id}`;
      let t = rt.topics.get(topic);
      if (!t) {
        t = { topic, id, cbs: new Set(), joined: false, ref: null, again: null };
        rt.topics.set(topic, t);
        if (rt.open) join(t); else connect();
      }
      t.cbs.add(cb);
      return () => {
        t.cbs.delete(cb);
        if (t.cbs.size || rt.topics.get(topic) !== t) return;
        rt.topics.delete(topic);
        clearTimeout(t.again);
        if (t.joined) rt.send({ topic, event: 'phx_leave', payload: {}, ref: nextRef() });
        if (!rt.topics.size && rt.ws) { const ws = rt.ws; rt.ws = null; rt.open = false; clearInterval(rt.beat); try { ws.close(); } catch (e) { /* gone */ } }
      };
    }
    const live = (id) => { const t = rt.topics.get(`realtime:match-${id}`); return !!(t && t.joined && rt.open); };
    function wake() { rt.tries = 0; if (!rt.ws) connect(); }

    return { server, user, call, setName, watch, live, wake, get uid() { return auth && auth.uid; } };
  }

  const api = { make, SERVER, APP: 'https://runescapetcg.netlify.app/' };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.RSTCGOnline = api;
})(typeof window !== 'undefined' ? window : globalThis);
