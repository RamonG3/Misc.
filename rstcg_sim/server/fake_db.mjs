// A pretend database for trying the `duel` function away from Supabase: the tables in memory and the
// referee's database calls (rpc), mirroring server/supabase/migrations/*.sql. Keep the two in step.
//
//   const { db, RPC } = makeDb({ now, uuid, onChange })
//   db.profiles / matches / secrets / moves      Maps, as the tables
//   RPC.duel_create({ p_host, ... }) ...         the functions, called with the arguments PostgREST passes
//   onChange(row)                                after a matches row changes (the browser tests' Realtime)
//
// Used by server/test_local.mjs and server/fake_supabase.mjs. A database error is thrown as { status, text }.
export function makeDb({ now = () => Date.now(), uuid = () => crypto.randomUUID(), onChange = () => {} } = {}) {
  const db = { profiles: new Map(), matches: new Map(), secrets: new Map(), moves: new Map() };
  const J = JSON.stringify;
  const iso = () => new Date(now()).toISOString();
  const touch = (m) => { m.updated_at = iso(); onChange(m); };
  const DAY = 24 * 3600e3;
  // close rooms; the rematch offers that pointed at them go too
  function close(rooms) {
    const gone = new Set();
    for (const m of rooms) { m.status = 'cancelled'; touch(m); gone.add(m.id); }
    for (const x of db.matches.values()) if (x.next && gone.has(x.next)) { x.next = null; touch(x); }
  }
  const openRooms = (host) => [...db.matches.values()].filter((m) => m.host === host && m.status === 'waiting');

  const RPC = {
    duel_create({ p_host, p_code, p_version, p_entry }) {
      if (!db.profiles.has(p_host)) throw { status: 409, text: 'insert or update on table "matches" violates foreign key constraint' };
      close(openRooms(p_host));
      for (const m of db.matches.values()) if (m.code === p_code && m.status === 'waiting') throw { status: 409, text: '23505 duplicate key' };
      const id = uuid();
      const m = { id, kind: 'challenge', code: p_code, status: 'waiting', host: p_host, guest: null, seats: null, names: null, version: p_version,
        seq: 0, pending: null, deadline: null, strikes: [0, 0], result: null, next: null, created_at: iso(), started_at: null, ended_at: null, updated_at: iso() };
      db.matches.set(id, m);
      db.secrets.set(id, { match_id: id, host_entry: p_entry, guest_entry: null, setup: null });
      db.moves.set(id, []);
      onChange(m);
      return id;
    },
    duel_find({ p_code }) {
      for (const m of db.matches.values()) if (m.code === String(p_code).toUpperCase() && m.status === 'waiting')
        return { id: m.id, host: m.host, host_name: db.profiles.get(m.host).name, version: m.version, created_at: m.created_at };
      return null;
    },
    duel_room({ p_match }) {
      const m = db.matches.get(p_match);
      return m ? { id: m.id, host: m.host, guest: m.guest, status: m.status, host_name: db.profiles.get(m.host).name, version: m.version, created_at: m.created_at } : null;
    },
    duel_load({ p_match }) {
      const m = db.matches.get(p_match);
      return m ? JSON.parse(J({ match: m, secret: db.secrets.get(p_match), moves: db.moves.get(p_match).map((x) => x.move) })) : null;
    },
    duel_mine({ p_user }) {
      return [...db.matches.values()].filter((m) => (m.host === p_user || m.guest === p_user) && ['waiting', 'playing'].includes(m.status))
        .sort((a, b) => (a.created_at < b.created_at ? 1 : -1)).slice(0, 5)
        .map((m) => ({ id: m.id, status: m.status, code: m.code, host: m.host === p_user }));
    },
    duel_start({ p_match, p_guest, p_entry, p_setup, p_seats, p_names, p_pending, p_deadline }) {
      const m = db.matches.get(p_match);
      if (!m || m.status !== 'waiting' || m.guest || m.host === p_guest || now() - Date.parse(m.created_at) > DAY) return false;
      Object.assign(m, { guest: p_guest, seats: p_seats, names: p_names, status: 'playing', pending: p_pending, deadline: p_deadline, started_at: iso() });
      Object.assign(db.secrets.get(p_match), { guest_entry: p_entry, setup: p_setup });
      touch(m);
      close(openRooms(p_guest));
      return true;
    },
    duel_append({ p_match, p_seq, p_move, p_pending, p_deadline, p_strikes, p_result }) {
      const m = db.matches.get(p_match);
      if (!m || m.seq !== p_seq || m.status !== 'playing') return false;
      Object.assign(m, { seq: p_seq + 1, pending: p_pending, deadline: p_deadline, strikes: p_strikes, result: p_result,
        status: p_result ? 'done' : 'playing', ended_at: p_result ? iso() : null });
      db.moves.get(p_match).push({ seq: p_seq, move: JSON.parse(J(p_move)) });
      touch(m);
      return true;
    },
    duel_cancel({ p_match, p_host }) {
      const m = db.matches.get(p_match);
      if (!m || m.host !== p_host || m.status !== 'waiting') return false;
      close([m]);
      return true;
    },
    duel_next({ p_match, p_user, p_next, p_version }) {
      const m = db.matches.get(p_match);
      if (!m || m.status !== 'done' || (m.host !== p_user && m.guest !== p_user)) return false;
      const r = m.next ? db.matches.get(m.next) : null;
      const free = !m.next || (r && (r.status === 'cancelled' || (r.status === 'waiting' && (r.version !== p_version || now() - Date.parse(r.created_at) > DAY))));
      if (!free) return false;
      m.next = p_next;
      touch(m);
      return true;
    },
  };
  return { db, RPC };
}
