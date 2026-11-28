/* Cloud save: keeping a player's progress the same on the server and on every device they sign in on.
 *
 * A signed-in player's progress (the profile: GP, collection, decks, packs, tutorial and tips, records) is kept
 * on the server as one document with a revision number. Each device also keeps its "mark": the revision and the
 * copy it last knew the server had. What differs from the mark is what changed on this device since.
 *
 *   RSTCGCloud.same(a, b)                   the same progress? Key order doesn't count (the database reorders
 *                                           keys), and a missing value equals a null one.
 *   RSTCGCloud.merge(base, local, remote)   what this device changed since `base`, applied on top of `remote`:
 *       gp, wins, losses, packs, online.*   theirs, plus what was gained (or spent) here
 *       coll                                each card: their count, plus the copies gained here
 *       decks                               by id. Changed here: ours. Otherwise theirs. Made on either side:
 *                                           kept. Deleted on one side: gone, unless the other changed it since.
 *       tips                                seen on either device, minus any cleared here
 *       onlinePaid                          both lists (so an online duel pays once per account)
 *       gifts                               both lists (a starter deck added later is given once per player)
 *       anything else                       ours if it changed here, otherwise theirs
 *
 *   await RSTCGCloud.sync(dev)              one visit to the server for a signed-in device:
 *       nothing new on either side          -> { did: 'same' }
 *       only this device changed            saves it                          -> { did: 'saved' }
 *       only the server's copy changed      takes it                          -> { did: 'loaded' }
 *       both changed                        merges, and saves the result      -> { did: 'merged' }
 *       they have never met (no mark for this account):
 *           the account has nothing         this device's progress starts it  -> { did: 'saved' }
 *           one of them is a brand-new game the other one is kept             -> { did: 'loaded' | 'saved' }
 *           both have progress              the player must choose            -> { ask: { rev, at, data } }
 *   await RSTCGCloud.choose(dev, 'device' | 'account', asked)   their answer. It may ask again, if the
 *                                           account's copy changed while they were choosing.
 *
 *   `dev` is the device's side of it:
 *       dev.uid                 the account's id
 *       dev.get()               -> { data, mark }: the progress on this device now (a copy), and its mark
 *                               ({ uid, rev, at, base }, or null)
 *       dev.put(data, mark)     replace both in one go, so they can never disagree (data null: only the mark)
 *       dev.load(since)         -> { rev, at, data }, or null when the server has nothing newer than `since`
 *       dev.save(data, rev)     -> { ok: true, rev, at }, or { ok: false, rev, at, data } when another device
 *                               saved first (rev null: there is nothing saved to update)
 *       dev.untouched(data)     is this a brand-new game, with nothing to lose?
 *
 * The player can keep playing while a visit waits on the network: everything read before a wait is read
 * again after it. Nothing here reads the clock or the page, so node can test it (web/test/cloud_test.js).
 * The Arena's side (when to visit, what to show) is the "Cloud save" section of arena.src.html.
 */
(function (root) {
  'use strict';
  const NUMBERS = ['gp', 'wins', 'losses', 'packs'];
  const PAID_KEPT = 40;                           // as the Arena keeps (profile.onlinePaid)

  function same(a, b) {
    if (a === b) return true;
    if (a == null || b == null) return a == null && b == null;
    if (typeof a !== 'object' || typeof b !== 'object') return false;
    const arr = Array.isArray(a);
    if (arr !== Array.isArray(b)) return false;
    if (arr) return a.length === b.length && a.every((x, i) => same(x, b[i]));
    const ka = Object.keys(a).filter((k) => a[k] != null), kb = Object.keys(b).filter((k) => b[k] != null);
    return ka.length === kb.length && ka.every((k) => same(a[k], b[k]));
  }

  const num = (x) => (typeof x === 'number' && isFinite(x) ? x : 0);
  const plus = (b, l, r) => Math.max(0, num(r) + num(l) - num(b));       // theirs, plus what changed here
  const obj = (x) => (x && typeof x === 'object' && !Array.isArray(x) ? x : {});
  const list = (x) => (Array.isArray(x) ? x : []);

  function counts(B, L, R) {
    const out = {};
    for (const k of new Set([...Object.keys(R), ...Object.keys(L)])) {
      const v = plus(B[k], L[k], R[k]);
      if (v > 0) out[k] = v;
    }
    return out;
  }

  function decks(B, L, R) {
    const byId = (ds) => new Map(ds.filter((d) => d && d.id).map((d) => [d.id, d]));
    const b = byId(B), l = byId(L), r = byId(R);
    const out = [];
    for (const d of l.values()) {
      const was = b.get(d.id), theirs = r.get(d.id);
      if (!was) out.push(d);                                   // made here
      else if (!theirs) { if (!same(d, was)) out.push(d); }    // deleted there: gone, unless changed here since
      else out.push(same(d, was) ? theirs : d);                // changed here: ours. Otherwise theirs
    }
    for (const d of r.values()) {
      if (l.has(d.id)) continue;
      const was = b.get(d.id);
      if (!was || !same(d, was)) out.push(d);                  // made there, or deleted here but changed there since
    }
    return out.length ? out : L.length ? L : R;                // never leave a player with no deck at all
  }

  function flags(B, L, R) {
    const out = {};
    for (const k of Object.keys(R)) if (R[k] && !(B[k] && !L[k])) out[k] = R[k];      // theirs, minus those cleared here
    for (const k of Object.keys(L)) if (L[k] && !B[k]) out[k] = L[k];                 // plus those new here
    return out;
  }

  // Story Mode: rewards given and roads finished only ever add up (from either device); where you are in the
  // story (main) and a replay under way go by the usual rule: this device's if it moved, else the account's.
  function story(B, L, R) {
    const out = {};
    const keys = new Set([...Object.keys(R), ...Object.keys(L)]);
    for (const k of keys) {
      if (k === 'claimed') out.claimed = { ...obj(R.claimed), ...obj(L.claimed) };
      else if (k === 'roads') {
        const r = obj(R.roads), l = obj(L.roads), roads = {};
        for (const ch of new Set([...Object.keys(r), ...Object.keys(l)])) roads[ch] = [...new Set([...list(r[ch]), ...list(l[ch])])].sort();
        out.roads = roads;
      } else if (!same(L[k], B[k])) { if (L[k] !== undefined) out[k] = L[k]; }
      else if (R[k] !== undefined) out[k] = R[k];
    }
    return out;
  }

  function merge(base, local, remote) {
    const B = obj(base), L = obj(local), R = obj(remote);
    const out = {};
    for (const k of new Set([...Object.keys(R), ...Object.keys(L)])) {
      if (NUMBERS.includes(k)) out[k] = plus(B[k], L[k], R[k]);
      else if (k === 'online') {
        const b = obj(B.online), l = obj(L.online), r = obj(R.online);
        out.online = { wins: plus(b.wins, l.wins, r.wins), losses: plus(b.losses, l.losses, r.losses) };
      }
      else if (k === 'coll') out.coll = counts(obj(B.coll), obj(L.coll), obj(R.coll));
      else if (k === 'decks') out.decks = decks(list(B.decks), list(L.decks), list(R.decks));
      else if (k === 'tips') out.tips = flags(obj(B.tips), obj(L.tips), obj(R.tips));
      else if (k === 'story') out.story = story(obj(B.story), obj(L.story), obj(R.story));
      else if (k === 'onlinePaid') out.onlinePaid = [...new Set([...list(R.onlinePaid), ...list(L.onlinePaid)])].slice(-PAID_KEPT);
      else if (k === 'gifts') out.gifts = [...new Set([...list(R.gifts), ...list(L.gifts)])];
      else if (!same(L[k], B[k])) { if (L[k] !== undefined) out[k] = L[k]; }           // changed here: ours
      else if (R[k] !== undefined) out[k] = R[k];                                      // otherwise theirs
    }
    return JSON.parse(JSON.stringify(out));       // a copy of its own: no deck shared with the three it came from
  }

  // ---------- visiting the server ----------
  const ROUNDS = 6;                               // someone else saving first, this many times running, is a fault

  async function sync(dev) {
    const markOf = (r) => ({ uid: dev.uid, rev: r.rev, at: r.at, base: r.data });
    let did = 'same';
    for (let round = 0; round < ROUNDS; round++) {
      const { data: local, mark } = dev.get();
      if (!mark || mark.uid !== dev.uid) {                       // this device and this account have never met
        const r = await dev.load(0);
        const now = dev.get().data;
        if (!r) {                                                // nothing saved: this device's progress starts it
          const s = await dev.save(now, null);
          if (s.ok) { dev.put(null, { uid: dev.uid, rev: s.rev, at: s.at, base: now }); return { did: 'saved' }; }
          continue;                                              // another device got there first: look again
        }
        if (same(r.data, now)) { dev.put(null, markOf(r)); return { did: 'same' }; }
        if (dev.untouched(now)) { dev.put(r.data, markOf(r)); return { did: 'loaded' }; }
        if (!dev.untouched(r.data)) return { ask: r };
        const s = await dev.save(now, r.rev);
        if (s.ok) { dev.put(null, { uid: dev.uid, rev: s.rev, at: s.at, base: now }); return { did: 'saved' }; }
        continue;
      }
      if (same(local, mark.base)) {                              // nothing new here: is there something new there?
        const r = await dev.load(mark.rev);
        if (!r) return { did };
        const now = dev.get().data;                              // the player may have done something meanwhile
        if (same(now, mark.base)) { dev.put(r.data, markOf(r)); return { did: did === 'merged' ? did : 'loaded' }; }
        dev.put(merge(mark.base, now, r.data), markOf(r));
        did = 'merged';
        continue;                                                // and save the join
      }
      const s = await dev.save(local, mark.rev);
      if (s.ok) { dev.put(null, { uid: dev.uid, rev: s.rev, at: s.at, base: local }); return { did: did === 'merged' ? did : 'saved' }; }
      if (s.rev == null) { dev.put(null, null); continue; }      // the account's save is gone: meet again
      dev.put(merge(mark.base, dev.get().data, s.data), markOf(s));   // someone saved first: join theirs with ours
      did = 'merged';
    }
    throw new Error('cloud save: the server kept changing');
  }

  async function choose(dev, which, asked) {
    if (which === 'account') {
      dev.put(asked.data, { uid: dev.uid, rev: asked.rev, at: asked.at, base: asked.data });
      return { did: 'loaded' };
    }
    const now = dev.get().data;                                  // this device's progress replaces the account's
    let s = await dev.save(now, asked.rev);
    if (!s.ok && s.rev == null) s = await dev.save(now, null);
    if (s.ok) { dev.put(null, { uid: dev.uid, rev: s.rev, at: s.at, base: now }); return { did: 'saved' }; }
    if (s.rev == null) throw new Error('cloud save: could not start the save');
    return { ask: { rev: s.rev, at: s.at, data: s.data } };      // it changed while they chose: show them again
  }

  const api = { same, merge, sync, choose };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.RSTCGCloud = api;
})(typeof window !== 'undefined' ? window : globalThis);
