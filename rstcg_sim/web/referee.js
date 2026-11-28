/* The online referee: the duel as the server runs it.
 *
 * The server never keeps a duel running. It keeps the setup (seed, decks, capes, names) and the list of
 * moves, and for every request it plays the duel again from the start on the synchronous engine (engine.js
 * with async/await taken out, as the hard bot's playouts use). The engine's questions are answered from the
 * move list; the first question the list can't answer is the decision now pending. Replaying a whole duel
 * takes about a millisecond.
 *
 *   const F = RSTCGReferee.make(S, D)   // S: the synchronous engine, data set; D: RSTCGDeckRules.make(...)
 *   F.entry(cards, cape)                // problems with a deck and cape brought online ([] = fine)
 *   F.state(setup, moves)               // { pending: {seat, t} | null, result: {winner, reason} | null, late }
 *   F.accept(setup, moves, seat, seq, move)  // checks a player's move -> { move, ...state after it } (or throws Refused)
 *   F.timeout(setup, moves)             // the move to record when the pending decision runs out of time
 *   F.view(setup, moves, seat, since)   // what that seat may see (see below)
 *
 * A move is { seat, t, v }: t is 'choose' (v: one of the legal actions, ['pass'] ends the turn), 'react'
 * (v: a card from the offered list, or null) or 'discard' (v: a card in hand, at the hand limit). A move
 * made for a player whose time ran out has late: true. Two moves end a duel outside the engine:
 * { seat, t: 'concede' } and { seat, t: 'forfeit' } (time ran out a third time).
 *
 * Nothing secret leaves through view(): not the seed, not the order of either deck, not the other
 * player's hand or deck list. test/referee_test.js checks this over thousands of duels. Each player gets
 * their own hand, their deck list and what's left in their deck (`left`: how many of each card, no order).
 */
(function (root) {
  'use strict';
  const LIMITS = { choose: 60, discard: 60, react: 20 };   // seconds for a decision
  const GRACE = 5;           // more seconds the server allows, for the other device's animations and lag
  const START_GRACE = 12;    // the first decision of a duel also waits out the intro
  const STRIKES = 3;         // running out of time this many times loses the duel
  const MAX_TURNS = 400;     // as runDuel: a duel this long is a draw
  const MAX_STEPS = 40;      // a view catches up move by move up to this many; past it, it starts afresh

  // What a player may see of each player. Every field of Player and Game must be in one of these lists
  // (the test fails on a new field until it is sorted into one).
  const PLAYER_PUBLIC = ['name', 'discard', 'removed', 'materials', 'weapon', 'shield', 'armor', 'ammo', 'prayer',
    'longrange', 'ate_food', 'ate_karambwan', 'reacted_round', 'free_swing_ready', 'free_swing_used', 'buff',
    'buff_new', 'buff_left', 'quest', 'completed', 'qp', 'sanctuary', 'light', 'lost_actions', 'cape', 'cape_name',
    'cape_prog', 'fresh', 'energy', 'free_steps', 'lumbridge_used', 'exchanged'];
  const PLAYER_HIDDEN = ['rng', 'deck', 'hand', 'decklist'];       // sizes for everyone; your own hand and list for you
  const GAME_PUBLIC = ['location', 'location_owner', 'turn', 'active', 'winner', 'end_reason', 'stats', 'actions_left',
    'free_plays_used', 'cape_table', 'cape_claimed'];
  const GAME_HIDDEN = ['rng', 'agents', 'players', 'rules', 'log', 'events'];   // players, rules, log and events go out separately

  class Refused extends Error {
    constructor(code, text) { super(text || code); this.code = code; }
  }
  class Stop { constructor(o) { Object.assign(this, o); } }

  const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  // A deck list in name order. The engine builds a deck in the list's order before shuffling it, and the
  // database (jsonb) doesn't keep the order it was given, so the referee always puts the names in order.
  const inOrder = (cards) => Object.fromEntries(Object.entries(cards).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
  const copy = (x) => (x === undefined ? null : JSON.parse(JSON.stringify(x)));
  const counted = (cards) => { const n = {}; for (const c of cards.slice().sort()) n[c] = (n[c] || 0) + 1; return n; };
  const BAD = {};                     // "that answer doesn't fit"

  function make(S, D) {
    const { CARDS, CAPES } = S;

    // The seat's picture of the duel.
    function seatState(g, seat) {
      const out = {};
      for (const k of GAME_PUBLIC) out[k] = copy(g[k]);
      out.players = g.players.map((p, i) => {
        const q = {};
        for (const k of PLAYER_PUBLIC) q[k] = copy(p[k]);
        q.lp = p.deck.length;
        q.handSize = p.hand.length;
        q.hand = i === seat ? p.hand.slice() : null;
        q.decklist = i === seat ? { ...p.decklist } : null;
        q.left = i === seat ? counted(p.deck) : null;      // what's left in your own deck: how many of each, never the order
        return q;
      });
      return out;
    }

    // Rune Pouch (and Necromancy Ritual) list the cards left in your deck in deck order; sort them so the list says
    // nothing about the order.
    const FROM_DECK = new Set(['pouch', 'ritual']);
    function canon(legal) {
      const out = legal.slice();
      const at = {};
      out.forEach((a, j) => { if (a[0] === 'play' && CARDS[a[1]] && FROM_DECK.has(CARDS[a[1]].kind)) (at[a[1]] = at[a[1]] || []).push(j); });
      for (const js of Object.values(at)) {
        const sorted = js.map((j) => out[j]).sort((a, b) => String(a[2]).localeCompare(String(b[2])));
        js.forEach((j, n) => { out[j] = sorted[n]; });
      }
      return out;
    }

    function answer(g, seat, t, data, v) {
      if (t === 'choose') { const hit = data.legal.find((a) => same(a, v)); return hit === undefined ? BAD : hit; }
      if (t === 'react') return v === null ? null : data.opts.includes(v) ? v : BAD;
      if (t === 'discard') return typeof v === 'string' && g.players[seat].hand.includes(v) ? v : BAD;
      return BAD;
    }

    // Play the duel again from its setup, answering from `moves`.
    //   viewer: a seat whose name becomes "You" in the log, with pictures of the duel at each decision from `since`
    //   onPoint(g, k, ask): called at each decision before it is answered (for tests)
    function replay(setup, moves, o = {}) {
      const viewer = o.viewer === 0 || o.viewer === 1 ? o.viewer : null;
      const since = o.since === undefined ? moves.length : o.since;
      const names = setup.names.slice();
      if (viewer !== null) names[viewer] = 'You';
      const points = [];
      const late = [0, 0];
      let k = 0;
      const ask = (g, t, seat, data) => {
        const point = { k, log: g.log.length, ev: g.events.length };
        if (viewer !== null && k >= since) point.state = seatState(g, viewer);
        points.push(point);
        if (o.onPoint) o.onPoint(g, k, { seat, t, data });
        if (k >= moves.length) throw new Stop({ pending: { k, seat, t, data } });
        const m = moves[k];
        if (m.t === 'concede' || m.t === 'forfeit') {
          k += 1;
          throw new Stop({ result: { winner: 1 - m.seat, reason: m.t === 'concede' ? 'concede' : 'time' } });
        }
        if (m.seat !== seat || m.t !== t) throw new Error(`move ${k} doesn't fit the duel`);
        const v = answer(g, seat, t, data, m.v);
        if (v === BAD) throw new Error(`move ${k} isn't legal`);
        if (m.late) late[seat] += 1;
        k += 1;
        return v;
      };
      const agent = {
        choose: (g, i, legal) => ask(g, 'choose', i, { legal: [...legal, ['pass']] }),
        react: (g, j, amount, style, src, opts) => ask(g, 'react', j, { amount, style, src, opts: opts.slice() }),
        discard_choice: (g, i) => ask(g, 'discard', i, {}),
      };
      const g = new S.Game([inOrder(setup.decks[0]), inOrder(setup.decks[1])], [agent, agent], setup.seed, { capes: setup.capes.slice() }, names);
      let pending = null, result = null;
      try {
        while (g.winner === null && g.turn < MAX_TURNS) g.play_turn();
        result = { winner: g.winner, reason: g.winner === null ? 'turns' : g.end_reason };
      } catch (e) {
        if (!(e instanceof Stop)) throw e;
        pending = e.pending || null;
        result = e.result || null;
      }
      if (!pending && k < moves.length) throw new Error('moves recorded after the duel ended');
      points.push({ k, log: g.log.length, ev: g.events.length, state: viewer !== null ? seatState(g, viewer) : null });
      return { g, pending, result, late, points };
    }

    const summary = (r) => ({ pending: r.pending ? { seat: r.pending.seat, t: r.pending.t } : null, result: r.result, late: r.late });

    function state(setup, moves) { return summary(replay(setup, moves)); }

    function after(setup, moves, move) {
      const next = moves.concat([move]);
      return Object.assign({ move, moves: next }, summary(replay(setup, next)));
    }

    // A player's move. `seq` is how many moves the player had seen: a move sent from an old picture is refused.
    function accept(setup, moves, seat, seq, move) {
      if (seat !== 0 && seat !== 1) throw new Refused('seat', 'You are not in this duel.');
      const r = replay(setup, moves);
      if (r.result) throw new Refused('over', 'The duel is over.');
      if (!move || typeof move !== 'object') throw new Refused('bad', 'That move makes no sense.');
      if (move.t === 'concede') return after(setup, moves, { seat, t: 'concede' });     // any time, from either player
      if (seq !== moves.length) throw new Refused('stale', 'The duel has moved on.');
      const p = r.pending;
      if (p.seat !== seat) throw new Refused('turn', "It isn't your decision.");
      if (move.t !== p.t) throw new Refused('kind', `The duel is waiting for a ${p.t}, not a ${move.t}.`);
      const v = answer(r.g, seat, p.t, p.data, move.v === undefined ? null : move.v);
      if (v === BAD) throw new Refused('illegal', "That move isn't allowed now.");
      return after(setup, moves, { seat, t: p.t, v: copy(v) });
    }

    // Time ran out on the pending decision: the turn ends (choose), no reaction (react), or the newest card
    // in hand goes (discard). The third time, the duel is lost instead.
    function timeout(setup, moves) {
      const r = replay(setup, moves);
      if (r.result || !r.pending) return null;
      const { seat, t } = r.pending;
      if (r.late[seat] + 1 >= STRIKES) return after(setup, moves, { seat, t: 'forfeit' });
      const hand = r.g.players[seat].hand;
      const v = t === 'choose' ? ['pass'] : t === 'react' ? null : hand[hand.length - 1];
      return after(setup, moves, { seat, t, v, late: true });
    }

    // How many seconds a pending decision gets, all told.
    const allowance = (pending, first) => (pending ? LIMITS[pending.t] + GRACE + (first ? START_GRACE : 0) : 0);

    // The decision now pending, as `seat` sees it: who must decide and what kind of decision, and for the
    // one deciding, the choices.
    function pendingView(p, seat) {
      if (!p) return null;
      const out = { seat: p.seat, t: p.t };
      if (p.seat === seat) {
        if (p.t === 'choose') out.legal = canon(p.data.legal);
        if (p.t === 'react') Object.assign(out, { amount: p.data.amount, style: p.data.style, src: p.data.src, opts: p.data.opts.slice() });
      }
      return out;
    }

    // What `seat` may see. With `since` (how many moves that player has already seen) it also gets each move
    // since then with the picture after it, so the screen can play them out one by one.
    function view(setup, moves, seat, since) {
      const catchUp = Number.isInteger(since) && since >= 0 && since <= moves.length && moves.length - since <= MAX_STEPS;
      const r = replay(setup, moves, { viewer: seat, since: catchUp ? since : moves.length });
      const last = r.points[r.points.length - 1];
      const out = { seq: moves.length, you: seat, state: last.state, result: r.result, strikes: r.late, pending: pendingView(r.pending, seat) };
      if (catchUp && since < moves.length) {
        out.steps = [];
        for (let m = since; m < moves.length; m++) {
          const from = r.points[m], to = r.points[m + 1];
          const mv = { seat: moves[m].seat, t: moves[m].t };
          if ('v' in moves[m]) mv.v = copy(moves[m].v);
          if (moves[m].late) mv.late = true;
          out.steps.push({ move: mv, state: to.state, notes: r.g.log.slice(from.log, to.log), events: r.g.events.slice(from.ev, to.ev) });
        }
      } else if (!catchUp) {                  // starting afresh: the whole log, and the rules the duel is played by
        out.log = r.g.log.slice();
        out.rules = copy(r.g.rules);
      }
      return out;
    }

    // A deck and cape someone brings online. (Until accounts, the server can't check they own the cards.)
    function entry(cards, cape) {
      const problems = [];
      if (!cards || typeof cards !== 'object' || Array.isArray(cards)) return ['No deck.'];
      const r = D.check(cards);
      if (!r.known) problems.push(`Not cards: ${r.unknown.join(', ')}`);
      if (!r.sixty) problems.push(`${r.total} cards, not 60`);
      if (!r.copies) problems.push(`More than 4 of ${r.over.join(', ')}`);
      if (!r.legend) problems.push(`${r.legends} Legendaries (1 allowed)`);
      if (!(typeof cape === 'string' && Object.prototype.hasOwnProperty.call(CAPES, cape))) problems.push('No such cape.');
      return problems;
    }

    // One duel with fixed, made-up choices, start to finish, and what each seat would see at the end. A copy of
    // the engine and card data that gives the same answer as the original plays the same game (the server
    // build records the answer, and the deployed function reports whether its copy still gives it).
    function selfPlay(setup) {
      const moves = [];
      let k = 0;
      const agent = {
        choose: (g, i, legal) => { const all = [...legal, ['pass']]; const a = all[(k++ * 7 + 3) % all.length]; moves.push({ seat: i, t: 'choose', v: a }); return a; },
        react: (g, j, amount, style, src, opts) => { const c = k++ % 3 ? opts[k % opts.length] : null; moves.push({ seat: j, t: 'react', v: c }); return c; },
        discard_choice: (g, i) => { const h = g.players[i].hand; const n = h[k++ % h.length]; moves.push({ seat: i, t: 'discard', v: n }); return n; },
      };
      const g = new S.Game([inOrder(setup.decks[0]), inOrder(setup.decks[1])], [agent, agent], setup.seed, { capes: setup.capes.slice() }, setup.names.slice());
      while (g.winner === null && g.turn < MAX_TURNS) g.play_turn();
      return { moves: moves.length, views: [view(setup, moves, 0), view(setup, moves, 1)] };
    }

    return { replay, state, accept, timeout, view, entry, seatState, pendingView, canon, allowance, selfPlay, Refused };
  }

  const api = { make, Refused, LIMITS, GRACE, START_GRACE, STRIKES, MAX_TURNS, MAX_STEPS,
    PLAYER_PUBLIC, PLAYER_HIDDEN, GAME_PUBLIC, GAME_HIDDEN };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.RSTCGReferee = api;
})(typeof window !== 'undefined' ? window : globalThis);
