/* Online play and accounts, the page's side of the wire: signing in, your name, the `duel` function, hearing
 * when a duel changes, and the cloud save. No libraries, only fetch and WebSocket, so the page stays one
 * self-contained file. Every call to Supabase's sign-in service here was checked against its own client
 * (auth-js 2.119) and server (supabase/auth) on 2026-11-16; ONLINE_BRIEF.md lists them.
 *
 *   const ON = RSTCGOnline.make({ load, save, loadFlow, saveFlow, changed })
 *                              where the sign-in is kept: load() -> auth or null, save(auth); and the same for a
 *                              trip to Google that is under way (it must survive the page going and coming back).
 *                              changed(why) is called when the sign-in changes under the page ('signedout').
 *   ON.server                  { url, key }: the Supabase project. The publishable key is public by design;
 *                              the secret key never leaves the server. Tests point this elsewhere with
 *                              window.RSTCG_ONLINE_SERVER = { url, key } before the page loads.
 *
 * Playing (works for a guest: the first call signs in anonymously):
 *   await ON.user()            your user id
 *   await ON.call(op, body)    the duel function's answer (server/supabase/functions/duel/index.js). Throws an
 *                              Error with .code: 'net' (can't reach the server), 'auth', or the server's own code
 *   await ON.setName(name)     saves your name. Throws .code 'taken' or 'shape' (the database's rules)
 *   await ON.myName()          your name, or null if you haven't picked one
 *   const stop = ON.watch(id, (row) => {})   the duel's database row each time it changes (Supabase Realtime)
 *   ON.live(id)                is that watch connected right now? (if not, the page polls instead)
 *   ON.wake()                  reconnect now (the page came back from the background)
 *
 * Accounts (a guest who signs in keeps the same user id, so their name and duels stay theirs):
 *   ON.account()               who this device is signed in as, without asking the server:
 *                              { uid, guest, email, provider }, or null before the first sign-in
 *   await ON.ways()            the ways to sign in that are switched on at the server: { google, email }
 *   await ON.google(back)      leave for Google's sign-in page; Google sends the player back to the address
 *                              `back`. A guest gets Google tied to their own id. { fresh: true } signs in as
 *                              whoever owns that Google account instead, leaving the guest behind.
 *   ON.landing(href)           has the page just come back from a sign-in? Something to give ON.land, or null
 *   await ON.land(landing)     finish it: { account, linked }. Or { again: true } when Google is already tied
 *                              to another player: call ON.google(back, { fresh: true }) to sign in as them.
 *   await ON.emailCode(email)  email a sign-in code: { type } to give emailVerify (or { done: true } when the
 *                              server asks for no code). A guest gets the address put on their own id, unless
 *                              it already has an account, which they are then signing in to.
 *   await ON.emailVerify(email, code, type)   the code they typed: the account, or throws .code 'otp_expired'
 *   await ON.signOut()         forget this device's sign-in (the next ON.user() is a new guest)
 *   ON.forget()                the same, without telling the server (another tab already did)
 *   Errors carry the sign-in service's own .code where it gave one ('otp_expired', 'email_exists',
 *   'over_email_send_rate_limit', 'email_address_not_authorized', 'manual_linking_disabled', ...).
 *
 * The cloud save, for signed-in players only (server/supabase/migrations/*_accounts_progress.sql):
 *   await ON.progress.load(since)       { rev, at, data }, or null when nothing newer than revision `since`
 *   await ON.progress.save(data, rev)   { ok: true, rev, at }, or { ok: false, rev, at, data } when another
 *                                       device saved first (rev: the revision `data` was made from; null: none)
 */
(function (root) {
  'use strict';
  const SERVER = { url: 'https://udmaqoxyhiwpvxkrugwr.supabase.co', key: 'sb_publishable_eaN07rbCUAT_-FtdXkB2Iw_IjdGi_bJ' };
  const HEARTBEAT = 25e3;          // Realtime drops a connection that stays quiet for a minute

  const oops = (code, message, status) => Object.assign(new Error(message || code), { code, status });

  function make(o = {}) {
    const server = Object.assign({}, SERVER, root.RSTCG_ONLINE_SERVER || {}, o.server || {});
    const load = o.load || (() => null), save = o.save || (() => {});
    let flowKept = null;           // a trip to Google under way, if the page gave us nowhere better to keep it
    const loadFlow = o.loadFlow || (() => flowKept), saveFlow = o.saveFlow || ((f) => { flowKept = f; });
    const changed = (why) => { if (o.changed) try { o.changed(why); } catch (e) { console.error(e); } };
    let auth = load();             // { access_token, refresh_token, expires_at (seconds), uid, guest, email, provider }
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
    const guestOf = (a) => !a || a.guest !== false;      // sign-ins kept from before accounts existed were all guests
    function keep(s) {
      const u = s.user || null;
      auth = { access_token: s.access_token, refresh_token: s.refresh_token,
        uid: u ? u.id : auth && auth.uid,
        guest: u ? !!u.is_anonymous : guestOf(auth),
        email: u ? u.email || null : (auth && auth.email) || null,
        provider: u ? (u.app_metadata && u.app_metadata.provider) || null : (auth && auth.provider) || null,
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
      if (r.status >= 400 && r.status < 500 && r.status !== 429) {        // that sign-in is gone
        const was = auth;
        auth = null; save(null);
        if (guestOf(was)) return signUp();                                // a guest just becomes a new guest
        changed('signedout');                                             // an account has to sign in again
        throw oops('auth', 'You have been signed out.', r.status);
      }
      throw oops('net', "Can't reach the game server.", r.status);
    }
    // another tab may have refreshed, or signed in, already: never use a refresh token twice
    function adopt() {
      const stored = load();
      if (stored && stored.access_token && (!auth || stored.expires_at > auth.expires_at)) auth = stored;
    }
    function session(force) {
      adopt();
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
    // a request as the signed-in player; a token the server calls out of date is renewed, once
    async function asMe(path, init) {                // each a value, or a function of the sign-in
      for (let attempt = 0; ; attempt++) {
        const a = await session(attempt > 0);
        const i = (typeof init === 'function' ? init(a) : init) || {};
        const r = await http(typeof path === 'function' ? path(a) : path, Object.assign({}, i, { headers: json(Object.assign({ Authorization: `Bearer ${a.access_token}` }, i.headers)) }));
        if (r.status !== 401 || attempt > 0) return r;
      }
    }
    async function setName(name) {
      const r = await asMe('/rest/v1/profiles', (a) => ({ method: 'POST', body: JSON.stringify({ id: a.uid, name }), headers: { Prefer: 'resolution=merge-duplicates,return=minimal' } }));
      if (r.ok) return name;
      const b = r.body || {};
      if (b.code === '23505') throw oops('taken', 'That name is taken.', r.status);
      if (b.code === '23514') throw oops('shape', "That name won't do.", r.status);
      throw oops(r.status === 401 ? 'auth' : 'server', 'Could not save your name.', r.status);
    }
    async function myName() {
      const r = await asMe((a) => `/rest/v1/profiles?select=name&id=eq.${a.uid}`);
      if (!r.ok) throw oops(r.status === 401 ? 'auth' : 'server', 'Could not look up your name.', r.status);
      return Array.isArray(r.body) && r.body[0] ? r.body[0].name : null;
    }

    // ---------- accounts: a guest becomes a player who can sign in anywhere ----------
    // The sign-in service's errors, with its own code: { code: 422, error_code: 'email_exists', msg } (or, on its
    // newer wire format, { code: 'email_exists', message }).
    const codeOf = (r) => { const b = r.body || {}; return b.error_code || (typeof b.code === 'string' ? b.code : '') || ''; };
    function authError(r, fallback) {
      const b = r.body || {};
      return oops(codeOf(r) || (r.status === 429 ? 'over_request_rate_limit' : 'server'), b.msg || b.message || b.error_description || fallback || 'Could not sign in.', r.status);
    }
    function account() {
      adopt();
      return auth && auth.access_token ? { uid: auth.uid, guest: guestOf(auth), email: auth.email || null, provider: auth.provider || null } : null;
    }
    let waysNow = null, waysAt = 0;
    async function ways() {
      if (waysNow && Date.now() - waysAt < 60e3) return waysNow;
      const r = await http('/auth/v1/settings', { headers: json() });
      if (!r.ok || !r.body) throw oops('server', 'Could not ask the game server how to sign in.', r.status);
      const x = r.body.external || {};
      waysAt = Date.now();
      return (waysNow = { google: !!x.google, email: !!x.email });
    }

    // Google, by the PKCE flow: we keep a secret (the verifier) and send only its hash (the challenge). Google sends
    // the player back with a one-time code, and only the page holding the secret can swap that code for a sign-in.
    const b64url = (bytes) => btoa(String.fromCharCode.apply(null, bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    async function google(back, opt) {
      if (!root.crypto || !root.crypto.subtle || !root.location) throw oops('where', 'Signing in needs the app.');
      const verifier = b64url(root.crypto.getRandomValues(new Uint8Array(48)));
      const challenge = b64url(new Uint8Array(await root.crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))));
      const q = `provider=google&redirect_to=${encodeURIComponent(back)}&code_challenge=${challenge}&code_challenge_method=s256`;
      const a = !(opt && opt.fresh) && auth && auth.access_token ? await session() : null;
      if (a && guestOf(a)) {                         // a guest: tie Google to their own id, so their name and duels stay
        const r = await http(`/auth/v1/user/identities/authorize?${q}&skip_http_redirect=true`, { headers: json({ Authorization: `Bearer ${a.access_token}` }) });
        if (!r.ok || !r.body || !r.body.url) throw authError(r, 'Could not start signing in with Google.');
        saveFlow({ verifier, link: true, at: Date.now() });
        root.location.assign(r.body.url);
        return;
      }
      saveFlow({ verifier, link: false, at: Date.now() });
      root.location.assign(`${server.url}/auth/v1/authorize?${q}`);
    }
    // Back from a sign-in? Google's trip ends in ?code=... (or ?error=...&error_code=...); a link clicked in a
    // sign-in email ends in #access_token=...&refresh_token=... (or #error=...). Our own #duel-CODE links are neither.
    function landing(href) {
      let u;
      try { u = new URL(href); } catch (e) { return null; }
      const q = u.searchParams, h = new URLSearchParams(u.hash.replace(/^#/, ''));
      const both = (k) => q.get(k) || h.get(k);
      if (both('error') && (both('error_code') || both('error_description'))) return { error: both('error_code') || both('error'), message: both('error_description') || '' };
      if (q.get('code') && loadFlow()) return { code: q.get('code') };
      if (h.get('access_token') && h.get('refresh_token'))
        return { tokens: { access_token: h.get('access_token'), refresh_token: h.get('refresh_token'), expires_in: +h.get('expires_in') || 3600, expires_at: +h.get('expires_at') || 0 } };
      return null;
    }
    async function land(l) {
      const flow = loadFlow();
      saveFlow(null);
      if (l.error) {
        // Google (or its email address) is another player's already: sign in as that player instead
        if (flow && flow.link && (l.error === 'identity_already_exists' || l.error === 'email_exists')) return { again: true };
        throw oops(l.error, l.message || 'Could not sign in.');
      }
      if (l.code) {
        if (!flow || !flow.verifier) throw oops('flow_state_not_found', 'That sign-in was started somewhere else.');
        const r = await http('/auth/v1/token?grant_type=pkce', { method: 'POST', headers: json(), body: JSON.stringify({ auth_code: l.code, code_verifier: flow.verifier }) });
        if (!r.ok || !r.body || !r.body.access_token) throw authError(r);
        keep(r.body);
        return { account: account(), linked: !!flow.link };
      }
      const who = await http('/auth/v1/user', { headers: json({ Authorization: `Bearer ${l.tokens.access_token}` }) });
      if (!who.ok || !who.body || !who.body.id) throw authError(who);
      keep(Object.assign({}, l.tokens, { user: who.body }));
      return { account: account(), linked: false };
    }

    async function emailCode(email) {
      const a = auth && auth.access_token ? await session() : null;
      if (a && guestOf(a)) {                         // a guest: put the address on their own id, so their name and duels stay
        const r = await http('/auth/v1/user', { method: 'PUT', headers: json({ Authorization: `Bearer ${a.access_token}` }), body: JSON.stringify({ email }) });
        if (r.ok) {
          const u = r.body || {};
          if (u.is_anonymous !== false || String(u.email || '').toLowerCase() !== email.toLowerCase()) return { type: 'email_change' };
          await session(true);                       // the server asked for no code ("Confirm email" is off): done
          return { done: true };
        }
        if (codeOf(r) !== 'email_exists') throw authError(r, 'Could not send the code.');
      }                                              // ...or that address has an account already: sign in to it
      const r = await http('/auth/v1/otp', { method: 'POST', headers: json(), body: JSON.stringify({ email, create_user: true, data: {}, gotrue_meta_security: {} }) });
      if (!r.ok) throw authError(r, 'Could not send the code.');
      return { type: 'email' };
    }
    async function emailVerify(email, token, type) {
      const r = await http('/auth/v1/verify', { method: 'POST', headers: json(), body: JSON.stringify({ email, token, type: type || 'email', gotrue_meta_security: {} }) });
      if (!r.ok || !r.body || !r.body.access_token) throw authError(r, "That code didn't work.");
      keep(r.body);
      return account();
    }
    function forget() { auth = null; pending = null; }
    // The device forgets its sign-in at once; telling the server (so the sign-in can't be used again) is best
    // effort: offline, the server just keeps a sign-in that nobody holds. An out-of-date token is renewed first,
    // because the server only signs out a caller it can still recognise.
    async function signOut() {
      const was = auth;
      auth = null; pending = null;
      save(null); saveFlow(null);
      if (!was || !was.access_token) return;
      const bye = (token) => http('/auth/v1/logout?scope=local', { method: 'POST', headers: json({ Authorization: `Bearer ${token}` }) });
      try {
        const r = await bye(was.access_token);
        if (r.status !== 401 && r.status !== 403) return;
        const t = await http('/auth/v1/token?grant_type=refresh_token', { method: 'POST', headers: json(), body: JSON.stringify({ refresh_token: was.refresh_token }) });
        if (t.ok && t.body && t.body.access_token) await bye(t.body.access_token);
      } catch (e) { /* offline */ }
    }

    // ---------- the cloud save ----------
    // Only for signed-in players, and never by signing anyone in: a guest (or nobody) gets .code 'auth'.
    async function mine(path, init) {
      for (let attempt = 0; ; attempt++) {
        adopt();
        if (!auth || !auth.access_token || guestOf(auth)) throw oops('auth', 'Sign in to save your progress.');
        const a = await session(attempt > 0);
        if (guestOf(a)) throw oops('auth', 'Sign in to save your progress.');
        const r = await http(path, Object.assign({}, init, { headers: json({ Authorization: `Bearer ${a.access_token}` }) }));
        if (r.status === 401 && attempt === 0) continue;         // an old token: refresh it and try once more
        if (r.ok) return { r, uid: a.uid };
        const b = r.body || {};
        throw oops(r.status === 401 || r.status === 403 ? 'auth' : 'server', b.message || 'Could not reach your saved progress.', r.status);
      }
    }
    const progress = {
      async load(since) {
        adopt();
        const uid = auth && auth.uid;
        const { r } = await mine(`/rest/v1/progress?select=data,rev,updated_at&user_id=eq.${uid}&rev=gt.${Math.max(0, Math.floor(+since || 0))}`, {});
        const row = Array.isArray(r.body) ? r.body[0] : null;
        return row ? { rev: +row.rev, at: row.updated_at, data: row.data } : null;
      },
      async save(data, rev, opt) {
        const body = JSON.stringify({ p_data: data, p_rev: rev == null ? null : rev });
        // keepalive lets a last save finish after the page is hidden or closed; browsers only allow it for small ones
        const { r } = await mine('/rest/v1/rpc/progress_save', { method: 'POST', body, keepalive: !!(opt && opt.keepalive) && body.length < 60000 });
        const b = r.body || {};
        return b.ok ? { ok: true, rev: +b.rev, at: b.at } : { ok: false, rev: b.rev == null ? null : +b.rev, at: b.at, data: b.data };
      },
    };

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

    return { server, user, call, setName, myName, watch, live, wake,
      account, ways, google, landing, land, emailCode, emailVerify, signOut, forget, progress,
      get uid() { return auth && auth.uid; } };
  }

  const api = { make, SERVER, APP: 'https://runescapetcg.netlify.app/' };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.RSTCGOnline = api;
})(typeof window !== 'undefined' ? window : globalThis);
