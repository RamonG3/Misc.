/* Difficulty levels for the Arena's computer opponent.
 *
 *   makeBot(level, seed)   an agent for engine.js: 'easy' | 'normal' | 'hard'
 *   LEVELS                 the three levels, in order, with the names the page shows
 *
 * Normal is the greedy HeuristicBot from bot.js, untouched: the balance numbers were measured with it.
 * Easy is the same bot with a new player's habits: it overlooks options, forgets capes and reactions.
 * Hard looks ahead. For each decision it copies the game, deals out again everything it can't see, and
 * plays its best few choices to the end on the same deals, dropping the ones that fall clearly behind.
 *
 * No peeking. A bot here never reads the other player's hand or the order of either deck. Hard's copies
 * pool the opponent's hand and deck and deal them back at random, and shuffle its own deck. (Like greedy,
 * the bots do know both deck lists, the way players do when lists are open.)
 * Same seed, same game. Every random number comes from the engine's seeded RNG. A search is bounded by how
 * many turns it simulates, never by the clock, and Hard keeps nothing between decisions: its dice for a
 * decision come from the seed and the position. The live game is only read, never changed.
 *
 * Measure and check with web/test/bot_ladder.js and web/test/bot_selftest.js.
 */
(function (root) {
  'use strict';
  const node = typeof module !== 'undefined' && module.exports;
  const R = node ? require('./engine.js') : root.RSTCG;
  const { HeuristicBot } = node ? require('./bot.js') : root.RSTCGBot;
  const { CARDS, RNG } = R;

  const LEVELS = [
    { id: 'easy', name: 'Goblin', blurb: 'Still learning. Makes mistakes.' },
    { id: 'normal', name: 'Guard', blurb: 'A fair fight.' },
    { id: 'hard', name: 'Champion', blurb: 'Thinks ahead. Plays to win.' },
  ];

  const key = (a) => JSON.stringify(a);
  const NOOP = () => {};

  // ---------- the engine without its waiting ----------
  // engine.js waits on every agent so a person can take their time. Playouts ask only bots, so they run on a
  // copy of the same source with the async/await words taken out: the same rules, word for word, about a
  // fifth faster. Node makes the copy here; the page is given one by build_arena.py (window.RSTCGSync).
  // Without it the playouts use engine.js itself: slower, same answers.
  function unwait(source) {
    return source.replace(/\basync\s+/g, '').replace(/\bawait\s+/g, '');
  }
  let FAST = null;
  function fastEngine() {
    if (FAST) return FAST;
    let api = root.RSTCGSync || null;
    if (!api && node) {
      try {
        const src = require('fs').readFileSync(require('path').join(__dirname, 'engine.js'), 'utf8');
        const m = { exports: {} };
        new Function('module', 'window', unwait(src))(m, {});
        api = m.exports;
      } catch (e) { api = null; }
    }
    if (api && api.Game) api.setData({ cards: R.CARDS, capes: R.CAPES, decks: R.DECKS });
    FAST = api && api.Game ? { Game: api.Game.prototype, Player: api.Player.prototype, sync: true }
      : { Game: R.Game.prototype, Player: R.Player.prototype, sync: false };
    return FAST;
  }

  // Let the page draw and take taps in the middle of a long think. (A message, not a zero timeout: browsers
  // make a run of zero timeouts wait 4 ms each.)
  let port = null;
  const waiting = [];
  function breathe() {
    if (typeof MessageChannel === 'undefined') return new Promise((res) => setTimeout(res, 0));
    if (!port) {
      const ch = new MessageChannel();
      ch.port1.onmessage = () => { const res = waiting.shift(); if (res) res(); };
      if (ch.port1.unref) ch.port1.unref();
      port = ch.port2;
    }
    return new Promise((res) => { waiting.push(res); port.postMessage(0); });
  }

  // One number from several, well mixed (so nearby inputs give unrelated seeds).
  function mix(...xs) {
    let h = 0x811c9dc5;
    for (const x of xs) {
      h ^= x | 0;
      h = Math.imul(h, 0x01000193);
      h ^= h >>> 15;
      h = Math.imul(h, 0x2c1b3c6d);
      h ^= h >>> 12;
    }
    return (h >>> 0) || 1;
  }

  // ---------- a copy of the game to try moves on ----------
  function copyPlayer(p, rng, proto) {
    const q = Object.create(proto || Object.getPrototypeOf(p));
    for (const k in p) {
      const v = p[k];
      q[k] = v === null || typeof v !== 'object' || k === 'decklist' || k === 'rng' ? v : Array.isArray(v) ? v.slice() : { ...v };
    }
    q.rng = rng;
    return q;
  }

  // What seat `view` can't see, in a fixed order, so a copy depends on which cards are hidden and never on
  // where they happen to sit: their own deck, and the opponent's hand and deck as one pile.
  function hiddenFrom(g, view) {
    const me = g.players[view];
    const op = g.players[1 - view];
    return { deck: me.deck.slice().sort(), pool: op.hand.concat(op.deck).sort(), hand: op.hand.length };
  }

  // The copy has its own RNG and agents, and none of the page's wrappers. With `view`, everything that seat
  // can't see is dealt again at random; without it the copy is exact (for tests). `protos` makes the copy a
  // game of another build of the same engine (the one without the waiting).
  function cloneGame(g, view, rng, agents, hidden, protos) {
    const s = Object.create(protos ? protos.Game : Object.getPrototypeOf(g));
    for (const k in g) if (typeof g[k] !== 'function') s[k] = g[k];      // the page's wrappers (functions) stay behind
    s.rng = rng;
    s.players = [copyPlayer(g.players[0], rng, protos && protos.Player), copyPlayer(g.players[1], rng, protos && protos.Player)];
    s.agents = agents;
    s.stats = [{}, {}];
    s.log = [];
    s.events = [];
    s.cape_claimed = { ...g.cape_claimed };
    if (view !== null && view !== undefined) {
      const h = hidden || hiddenFrom(g, view);
      const me = s.players[view];
      const op = s.players[1 - view];
      me.deck = h.deck.slice();
      rng.shuffle(me.deck);
      const pool = h.pool.slice();
      rng.shuffle(pool);
      op.hand = pool.slice(0, h.hand);
      op.deck = pool.slice(h.hand);
    }
    return s;
  }

  // ---------- the greedy bot without its dice: what the playouts are played with ----------
  class SteadyBot extends HeuristicBot {
    constructor() { super(1, 0); }
    choose(g, i, legal) {
      let best = null;
      let bestV = -Infinity;
      for (const a of legal) {
        const v = this.score(g, i, a);
        if (v > bestV) { best = a; bestV = v; }
      }
      return best;
    }
    discard_choice(g, i) {
      let best = null;
      let bestV = Infinity;
      for (const n of g.players[i].hand) {
        const v = this.keep_value(g, i, n);
        if (v < bestV) { best = n; bestV = v; }
      }
      return best;
    }
  }

  // ---------- greedy that knows the game is a race ----------
  // The first player to claim a cape wins three turns later unless they are killed first. Greedy plays as
  // if it didn't know: it claims when it is already too late, keeps building while the clock runs, and only
  // sees a kill that takes one action. This bot fixes those, and nothing else about greedy's judgement.
  const SHARP = {
    tick: 2.0,                         // how much more a step towards a cape is worth than greedy thinks
    race: { 2: [1.6, 3.0, 2.5], 3: [1.3, 1.0, 1.0] },   // they wear a cape, N of my turns left: damage x a + b, the rest - c
    guard: [1.5, 0.6, 2.0],            // I wear a cape: drawing costs this, damage is worth x this, defence + this
  };
  const ATTACK = new Set(['spell', 'ability', 'rapid', 'longrange', 'destroy']);
  const DEFENCE = new Set(['shield', 'armor', 'food']);

  class SharpBot extends SteadyBot {
    constructor(tune) {
      super();
      this.k = Object.assign({}, SHARP, tune || {});
    }

    attacks(g, i, a) {
      const p = g.players[i];
      if (a[0] === 'punch' || a[0] === 'swing' || a[0] === 'free_swing') return true;
      if (a[0] === 'spec') return !R.FREE_SPECS.has(p.weapon_card().spec);
      if (a[0] !== 'play') return false;
      const c = CARDS[a[1]];
      return ATTACK.has(c.kind) && (c.kind !== 'spell' && c.kind !== 'destroy' || c.damage > 0);
    }

    defends(g, i, a) {
      const p = g.players[i];
      if (a[0] === 'varrock') return true;
      if (a[0] === 'spec') return R.FREE_SPECS.has(p.weapon_card().spec);
      if (a[0] !== 'play') return false;
      const c = CARDS[a[1]];
      return DEFENCE.has(c.kind) || c.kind === 'longrange' || c.effect === 'redemption' || a[1] === 'Lumbridge';
    }

    score(g, i, a) {
      const p = g.players[i];
      const o = g.opp(i);
      const theirs = o.cape === null ? 0 : g.rules.cape_turns - o.cape;    // my turns left before their cape wins
      if (a[0] === 'claim') return theirs ? -10.0 : super.score(g, i, a);  // the second to claim is too late
      if (theirs === 1) {                                                   // last turn: greedy's all-out rule, for every attack
        const v = this.base_score(g, i, a);
        return this.attacks(g, i, a) ? v * 2.0 + 5.0 : v - 4.0;
      }
      if (theirs) {
        const [mul, add, sub] = this.k.race[theirs] || this.k.race[3];
        const v = this.base_score(g, i, a);
        return this.attacks(g, i, a) ? v * mul + add : v - sub;
      }
      if (p.cape !== null) {                                                // I'm on the clock: stay alive
        const [draw, hit, def] = this.k.guard;
        const v = this.base_score(g, i, a);
        if (v >= 900) return v;
        if (a[0] === 'draw') return v - draw;
        if (this.defends(g, i, a)) return v + def;
        return this.attacks(g, i, a) ? v * hit : v;
      }
      const v = this.base_score(g, i, a);
      if (v >= 900) return v;
      return g.cape_table.length ? v + this.k.tick * this.cape_bonus(g, i, a) : v;
    }

    // What a move does to the opponent right now, before their shield, and how often it can be made this turn.
    strike(g, i, a, left) {
      const p = g.players[i];
      const w = p.weapon_card();
      const hit = (amount, style, src, ia, ip) => { const [to, so] = g.compute_damage(i, amount, style, !!ia, !!ip, src); return to + so; };
      if (a[0] === 'punch') return [hit(g.rules.punch_damage, 'melee', 'punch', true, true), left];
      if (a[0] === 'swing' || a[0] === 'free_swing') {
        const once = a[0] === 'free_swing' ? 1 : left;
        if (w.kind === 'bow') {
          return a[1] === 'ranged' ? [hit(w.hit + p.ammo.bonus, 'ranged', 'bow'), Math.min(once, p.ammo.shots)]
            : [hit(w.bash_hit, 'melee', 'weapon'), Math.min(once, p.weapon.dur)];
        }
        const src = w.kind === 'thrown' ? 'thrown' : w.kind === 'staff' ? 'autocast' : 'weapon';
        return [hit(w.hit, a[1], src) * w.hits, Math.min(once, p.weapon.dur)];
      }
      if (a[0] === 'spec') {
        switch (w.spec) {
          case 'war_god': return [hit(w.hit, 'melee', 'spec'), 1];
          case 'sunder': return [hit(w.hit + w.spec_val, 'melee', 'spec'), 1];
          case 'snapshot': return [hit(w.hit + p.ammo.bonus, 'ranged', 'spec') * Math.min(2, p.ammo.shots), 1];
          case 'soulshot': return [hit(w.hit + p.ammo.bonus + w.spec_val, 'ranged', 'spec', true), 1];
          case 'iban': return [hit(w.spec_val, 'magic', 'spec'), 1];
          case 'lightning': return [hit(w.hit, 'melee', 'spec') + hit(w.spec_val, 'magic', 'spec'), 1];
          default: return [0, 0];
        }
      }
      if (a[0] !== 'play') return [0, 0];
      const c = CARDS[a[1]];
      let copies = 0;
      for (const x of p.hand) if (x === a[1]) copies++;
      if (c.kind === 'spell' && c.damage) return [hit(c.damage, 'magic', 'spell'), copies];
      if (c.kind === 'ability') return [hit(w.hit + c.damage, 'melee', 'ability', c.ignores_armor), Math.min(copies, p.weapon.dur)];
      if (c.kind === 'destroy' && c.damage) return [hit(c.damage, ['melee', 'ranged', 'magic'].includes(c.style) ? c.style : 'none', 'spell'), copies];
      if (c.kind === 'rapid' || c.kind === 'longrange') {
        const thrown = w.kind === 'thrown';
        const shots = Math.min(c.kind === 'rapid' ? 2 : 1, thrown ? p.weapon.dur : p.ammo.shots);
        return [hit(w.hit + (thrown ? 0 : p.ammo.bonus) + (c.kind === 'longrange' ? -1 : 0), 'ranged', 'ability') * shots, copies];
      }
      return [0, 0];
    }

    // The first move of a kill this turn, when the moves I have left add up to one.
    kill(g, i, legal) {
      const o = g.opp(i);
      const left = g.actions_left;
      if (o.lp > 36 || (g.location === 'Lumbridge' && !o.lumbridge_used)) return null;      // more than a turn can do
      const hits = [];
      for (const a of legal) {
        const [amount, times] = this.strike(g, i, a, left);
        if (amount > 0 && times > 0) hits.push({ a, amount, times, free: a[0] === 'free_swing' });
      }
      hits.sort((x, y) => y.amount - x.amount);
      let total = 0;
      let used = 0;
      let first = null;
      for (const h of hits) {
        const n = h.free ? 1 : Math.min(h.times, left - used);
        if (n <= 0) continue;
        total += n * h.amount;
        if (!h.free) used += n;
        first = first || h.a;
      }
      return first && total - (o.shield ? o.shield.soak : 0) >= o.lp ? first : null;
    }

    choose(g, i, legal) {
      let best = null;
      let bestV = -Infinity;
      for (const a of legal) {
        const v = this.score(g, i, a);
        if (v > bestV) { best = a; bestV = v; }
      }
      return bestV >= 900 ? best : this.kill(g, i, legal) || best;
    }
  }

  // ---------- Hard ----------
  const HARD = {
    turns: 3500,             // the most turns of play one decision may simulate, over all its playouts
    opening: [1500, 2500],   // ... and less on each player's first and second turn, when a playout is longest and tells least
    width: 6,                // how many of its top choices it tries
    batch: 4,                // deals given to every move still in the running before the standings are checked
    wait: 8,                 // deals played before any move is dropped
    z: 2.0,                  // a move this many standard errors behind the leader is dropped
    stay: 3.0,               // ... but its first choice has to be this far behind
    edge: 1.0,               // and when the budget runs out, another move replaces the first choice only if it is this far ahead
    same: 12,                // a move that has matched a better-ranked one on this many deals is taken to be the same move
    horizon: 80,             // turns a playout may run before it counts as a draw
    pause: 0,                // let the page breathe after this many playouts (0: never; the answer is the same either way)
  };

  // In a playout the opponent is played by greedy and Hard's own seat by SharpBot, which is also what picks
  // and orders the moves it tries. Reactions and discards follow greedy's rules: searching them gained nothing.
  class HardBot extends HeuristicBot {
    constructor(seed, opts) {
      super(seed);
      this.seed = seed >>> 0;
      this.opt = Object.assign({}, HARD, opts || {});
      this.searches = 0;       // for the tests: decisions searched, playouts played, turns simulated
      this.played = 0;
      this.spent = 0;
    }

    setup(g) {
      super.setup(g);
      this.greedy = new SteadyBot();
      this.greedy.setup(g);
      this.sharp = new SharpBot();
      this.sharp.setup(g);
      this.forced = null;
    }

    // My seat in a playout: the one move being tried, then the usual play.
    seat() {
      const sharp = this.sharp;
      return {
        choose: (g, i, legal) => { const f = this.forced; if (f) { this.forced = null; return f; } return sharp.choose(g, i, legal); },
        react: (g, j, amount, style, src, opts) => sharp.react(g, j, amount, style, src, opts),
        discard_choice: (g, i) => sharp.discard_choice(g, i),
      };
    }

    // Every decision gets its own dice, from the seed and what anyone at the table can see.
    dice(g, i) {
      const p = g.players[i];
      const o = g.players[1 - i];
      return new RNG(mix(this.seed, g.turn, g.actions_left, g.free_plays_used, p.hand.length, p.lp, o.lp, 1));
    }

    // Race the moves: deal the same hidden cards to every move still in the running, play each one out, and
    // after every few deals drop the moves that are clearly behind. Returns the index of the move left standing.
    async search(g, i, moves) {
      const o = this.opt;
      const rng = this.dice(g, i);
      const fast = fastEngine();
      const hidden = hiddenFrom(g, i);
      const mine = this.seat();
      const agents = i === 0 ? [mine, this.greedy] : [this.greedy, mine];
      const cap = g.turn < 2 ? o.opening[0] : g.turn < 4 ? o.opening[1] : o.turns;
      const won = moves.map(() => []);            // each move's results, deal by deal
      const total = (k) => { let t = 0; for (const v of won[k]) t += v; return t; };
      const behind = (k, lead) => {               // how far move k trails the leader, in standard errors
        let sum = 0;
        let sq = 0;
        const n = won[k].length;
        for (let r = 0; r < n; r++) { const d = won[lead][r] - won[k][r]; sum += d; sq += d * d; }
        const mean = sum / n;
        const se = Math.sqrt(Math.max(0, sq / n - mean * mean) / n);
        return mean <= 0 ? 0 : se === 0 ? Infinity : mean / se;
      };
      let alive = moves.map((_, k) => k);
      let spent = 0;
      let deals = 0;
      let since = 0;
      while (alive.length > 1 && spent < cap) {
        for (let b = 0; b < o.batch; b++) {
          const seed = rng.randrange(0x7fffffff) + 1;
          for (const k of alive) {
            const s = cloneGame(g, i, new RNG(seed), agents, hidden, fast);
            s.note = NOOP;                          // nothing in the rules reads the log, the events or the stats
            s.emit = NOOP;
            s.stat = NOOP;
            this.forced = moves[k];
            await s.resume_turn(g.actions_left, g.free_plays_used);
            const end = s.turn + o.horizon;
            while (s.winner === null && s.turn < end) await s.play_turn();
            this.forced = null;
            won[k].push(s.winner === i ? 1 : s.winner === null ? 0.5 : 0);
            spent += s.turn - g.turn + 1;
            this.played++;
            if (o.pause && ++since >= o.pause) { since = 0; await breathe(); }
          }
          deals++;
        }
        let lead = alive[0];
        for (const k of alive) if (total(k) > total(lead)) lead = k;      // ties go to the better-ranked move
        const kept = [];
        for (const k of alive) {
          if (k !== lead) {
            if (deals >= o.wait && behind(k, lead) > (k === 0 ? o.stay : o.z)) continue;                   // clearly behind
            if (deals >= o.same && kept.some((q) => won[q].every((v, r) => v === won[k][r]))) continue;   // a copy
          }
          kept.push(k);
        }
        alive = kept;
      }
      let best = alive[0];
      for (const k of alive) if (total(k) > total(best)) best = k;
      if (best !== 0 && alive[0] === 0 && behind(0, best) < o.edge) best = 0;    // not clearly better than the first choice
      this.searches++;
      this.spent += spent;
      return best;
    }

    // The moves in the order SharpBot likes them, ties in a fixed order (so the order the engine lists moves
    // in doesn't matter), and a kill that takes the whole turn at the front.
    ranked(g, i, legal) {
      const order = legal.map((a) => ({ a, k: key(a), s: this.sharp.score(g, i, a) }))
        .sort((x, y) => y.s - x.s || (x.k < y.k ? -1 : x.k > y.k ? 1 : 0));
      if (order[0].s < 900) {
        const first = this.sharp.kill(g, i, legal);
        const at = first ? order.findIndex((x) => x.a === first) : -1;
        if (at > 0) order.unshift(order.splice(at, 1)[0]);
      }
      return order;
    }

    async choose(g, i, legal) {
      if (legal.length === 1) return legal[0];
      const order = this.ranked(g, i, legal);
      if (order[0].s >= 900) return order[0].a;            // a kill is a kill
      const moves = order.slice(0, this.opt.width).map((x) => x.a);
      return moves[await this.search(g, i, moves)];
    }

    discard_choice(g, i) {                                 // greedy's choice, without its dice
      const names = [...new Set(g.players[i].hand)];
      return names.sort((x, y) => this.keep_value(g, i, x) - this.keep_value(g, i, y) || (x < y ? -1 : 1))[0];
    }
  }

  // ---------- Easy ----------
  const EASY = {
    overlook: 0.5,        // chance it doesn't notice any one of its options this action
    overlook_claim: 0.8,  // the same for a cape that is ready to claim: new players forget capes
    noise: 1.0,           // how far it misjudges the options it does notice
    shield_blind: 0.5,    // it counts damage a shield would soak as if it landed (greedy counts about half)
    miss_react: 0.4,      // chance it forgets to react to a hit that wouldn't finish it
  };

  // Easy plays greedy's game with a new player's habits: it doesn't see every option it has (so it takes
  // the best of the ones it noticed), it is slow to claim capes, and it forgets its reactions. It never
  // misses a winning blow or a cape about to win, and it never picks a move greedy thinks is harmful.
  class EasyBot extends HeuristicBot {
    constructor(seed, opts) {
      super(seed);
      this.opt = Object.assign({}, EASY, opts || {});
    }

    // How much of this attack the opponent's shield would take.
    soaked(g, i, a) {
      const p = g.players[i];
      if (a[0] === 'swing' || a[0] === 'free_swing') {
        const w = p.weapon_card();
        const amount = w.kind !== 'bow' ? w.hit : a[1] === 'ranged' ? w.hit + p.ammo.bonus : w.bash_hit;
        return g.compute_damage(i, amount, a[1], false, false, 'weapon')[1] * w.hits;
      }
      if (a[0] === 'play' && CARDS[a[1]].kind === 'spell' && CARDS[a[1]].damage) {
        return g.compute_damage(i, CARDS[a[1]].damage, 'magic', false, false, 'weapon')[1];
      }
      return 0;
    }

    choose(g, i, legal) {
      const o = g.opp(i);
      const doomed = o.cape !== null && o.cape >= g.rules.cape_turns - 1;
      const order = legal.map((a) => ({ a, k: key(a), s: this.score(g, i, a) }))
        .sort((x, y) => y.s - x.s || (x.k < y.k ? -1 : x.k > y.k ? 1 : 0));
      if (order[0].s >= 900 || doomed) return order[0].a;  // it doesn't miss a kill, or a cape about to win
      const swing = Math.max(-Infinity, ...order.filter((x) => x.a[0] === 'swing' || x.a[0] === 'free_swing').map((x) => x.s));
      let best = null;
      let bestV = -Infinity;
      for (const x of order) {
        const blink = this.rng.random();
        const roll = this.rng.random();
        if (blink < (x.a[0] === 'claim' ? this.opt.overlook_claim : this.opt.overlook)) continue;
        if (x.s <= 0 && order[0].s > 0) continue;          // not a move that only does harm
        if (x.a[0] === 'punch' && swing > x.s) continue;   // nor a punch with a better weapon in its hand
        const v = x.s + this.opt.shield_blind * this.soaked(g, i, x.a) + roll * this.opt.noise;   // a hit on a shield still feels like a hit
        if (v > bestV) { best = x.a; bestV = v; }
      }
      return best || order[0].a;
    }

    react(g, j, amount, style, src, opts) {
      const me = g.players[j];
      const [incoming] = g.compute_damage(1 - j, amount, style);
      const forget = this.rng.random() < this.opt.miss_react;
      if (forget && incoming < me.lp) return null;
      return super.react(g, j, amount, style, src, opts);
    }

    discard_choice(g, i) {
      const names = [...new Set(g.players[i].hand)].sort();
      let best = null;
      let bestV = Infinity;
      for (const n of names) {
        const v = this.keep_value(g, i, n) + this.rng.random() * this.opt.noise;
        if (v < bestV) { best = n; bestV = v; }
      }
      return best;
    }
  }

  function makeBot(level, seed, opts) {
    if (level === 'easy') return new EasyBot(seed, opts);
    if (level === 'hard') return new HardBot(seed, opts);
    return new HeuristicBot(seed);
  }

  const api = { LEVELS, makeBot, EasyBot, HardBot, SteadyBot, SharpBot, cloneGame, hiddenFrom, mix, unwait, fastEngine };
  if (node) module.exports = api;
  root.RSTCGLevels = api;
})(typeof window !== 'undefined' ? window : globalThis);
