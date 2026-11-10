/* Difficulty levels for the Arena's computer opponent.
 *
 *   makeBot(level, seed)   an agent for engine.js: 'easy' | 'normal' | 'hard'
 *   LEVELS                 the three levels, in order, with the names the page shows
 *
 * Normal is the greedy HeuristicBot from bot.js, untouched: the balance numbers were measured with it.
 * Easy is the same bot making the mistakes a new player makes.
 * Hard looks ahead. For each choice it copies the game, deals out again everything it can't see, and
 * plays the choice to the end many times with the greedy bot on both sides. It keeps what won most.
 *
 * No peeking: a bot here never reads the other player's hand or the order of either deck. Hard's copies
 * pool the opponent's hand and deck and deal them back at random, and shuffle its own deck.
 * Same seed, same game: every random number comes from the engine's seeded RNG, searches run a fixed
 * number of playouts (never a time limit), and the live game is only read, never changed.
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
      let v = this.base_score(g, i, a);
      if (v >= 900) return v;
      if (this.k.adj) {
        const kind = a[0] === 'play' ? CARDS[a[1]].kind : a[0];
        v += this.k.adj[kind] || 0;
      }
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
      if (o.lp > 45 || (g.location === 'Lumbridge' && !o.lumbridge_used)) return null;
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
    turns: 4000,       // the most turns of play a decision may simulate, over all its playouts
    width: 6,          // how many of greedy's top choices are tried
    batch: 4,          // deals given to every move still in the running before the standings are checked
    z: 2.0,            // a move this many standard errors behind the leader is dropped
    stay: 3.0,         // ... but greedy's first choice has to be this far behind
    edge: 1.0,         // and at the end another move replaces it only if it is this far ahead
    wait: 8,           // deals played before anything is dropped
    same: 12,          // a move that has matched a better-ranked one on this many deals is taken to be the same
    horizon: 80,       // turns a playout may run before it counts as a draw
    mine: 'greedy',    // who plays my seat in the playouts: 'greedy' or 'sharp'
    reacts: false,     // decide reactions by playout too (greedy's rule otherwise)
    discards: false,   // decide discards by playout too (greedy's rule otherwise)
    pause: 0,          // let the page breathe after this many playouts (0: never; the answer is the same)
  };

  class HardBot extends HeuristicBot {
    constructor(seed, opts) {
      super(seed);
      this.seed = seed >>> 0;
      this.opt = Object.assign({}, HARD, opts || {});
      this.searches = 0;       // for the tests: decisions searched, playouts and simulated turns spent
      this.played = 0;
      this.spent = 0;
    }

    setup(g) {
      super.setup(g);
      this.sim = [0, 1].map(() => { const b = new SteadyBot(); b.setup(g); return b; });
      this.me = [0, 1].map(() => { const b = this.opt.mine === 'sharp' ? new SharpBot() : new SteadyBot(); b.setup(g); return b; });
      this.forced = null;
    }

    // My seat in a playout: the one answer being tried, then the usual play.
    seat(k) {
      const sim = this.me[k];
      const take = (t) => {
        const f = this.forced;
        if (f && f.t === t) { this.forced = null; return f; }
        return null;
      };
      return {
        choose: (g, i, legal) => { const f = take('choose'); return f ? f.v : sim.choose(g, i, legal); },
        react: (g, j, amount, style, src, opts) => { const f = take('react'); return f ? f.v : sim.react(g, j, amount, style, src, opts); },
        discard_choice: (g, i) => { const f = take('discard'); return f ? f.v : sim.discard_choice(g, i); },
      };
    }

    // Every decision gets its own dice, from the seed and what anyone at the table can see.
    dice(g, i, tag) {
      const p = g.players[i];
      const o = g.players[1 - i];
      return new RNG(mix(this.seed, g.turn, g.actions_left, g.free_plays_used, p.hand.length, p.lp, o.lp, tag));
    }

    // Race the candidates: deal the same hidden cards to every move still in the running, play each out,
    // and after every few deals drop the moves that are clearly behind. `begin(s, cand)` makes the
    // candidate's move on the copy `s`. Returns the index of the move left standing.
    async search(g, i, cands, begin, rng) {
      const o = this.opt;
      const fast = fastEngine();
      const hidden = hiddenFrom(g, i);
      const agents = i === 0 ? [this.seat(0), this.sim[1]] : [this.sim[0], this.seat(1)];
      const won = cands.map(() => []);            // each move's results, deal by deal
      let alive = cands.map((_, k) => k);
      let spent = 0;
      let deals = 0;
      let since = 0;
      while (alive.length > 1 && spent < o.turns) {
        for (let b = 0; b < o.batch; b++) {
          const seed = rng.randrange(0x7fffffff) + 1;
          for (const k of alive) {
            const s = cloneGame(g, i, new RNG(seed), agents, hidden, fast);
            s.note = NOOP;                          // nothing in the rules reads the log, the events or the stats
            s.emit = NOOP;
            s.stat = NOOP;
            await begin(s, cands[k]);
            const end = s.turn + o.horizon;
            while (s.winner === null && s.turn < end) await s.play_turn();
            this.forced = null;
            won[k].push(s.winner === i ? 1 : s.winner === null ? 0.5 : 0);
            spent += s.turn - g.turn + 1;
            this.played++;
            if (o.pause && ++since >= o.pause) { since = 0; await new Promise((res) => setTimeout(res, 0)); }
          }
          deals++;
        }
        let lead = alive[0];
        const total = (k) => { let t = 0; for (const v of won[k]) t += v; return t; };
        for (const k of alive) if (total(k) > total(lead)) lead = k;      // ties go to greedy's order
        const kept = [];
        for (const k of alive) {
          if (k === lead) { kept.push(k); continue; }
          let sum = 0;
          let sq = 0;
          for (let r = 0; r < deals; r++) { const d = won[lead][r] - won[k][r]; sum += d; sq += d * d; }
          const mean = sum / deals;
          const sd = Math.sqrt(Math.max(0, sq / deals - mean * mean));
          if (deals >= o.wait && mean > 0 && mean > (k === 0 ? o.stay : o.z) * sd / Math.sqrt(deals)) continue;   // clearly behind
          if (deals >= o.same && kept.some((q) => won[q].every((v, r) => v === won[k][r]))) continue;   // a copy
          kept.push(k);
        }
        alive = kept;
      }
      let best = alive[0];
      if (alive.length > 1) {
        const total = (k) => { let t = 0; for (const v of won[k]) t += v; return t; };
        for (const k of alive) if (total(k) > total(best)) best = k;
        if (best !== 0 && alive[0] === 0) {       // the budget ran out with greedy's choice still in: is the leader clearly ahead of it?
          let sum = 0;
          let sq = 0;
          for (let r = 0; r < deals; r++) { const d = won[best][r] - won[0][r]; sum += d; sq += d * d; }
          const mean = sum / deals;
          const sd = Math.sqrt(Math.max(0, sq / deals - mean * mean));
          if (mean < o.edge * sd / Math.sqrt(deals)) best = 0;
        }
      }
      this.searches++;
      this.spent += spent;
      return best;
    }

    // greedy's ranking, with ties in a fixed order (so the order the engine lists moves in doesn't matter)
    ranked(g, i, legal) {
      return legal.map((a) => ({ a, k: key(a), s: this.score(g, i, a) }))
        .sort((x, y) => y.s - x.s || (x.k < y.k ? -1 : x.k > y.k ? 1 : 0));
    }

    async choose(g, i, legal) {
      if (legal.length === 1) return legal[0];
      const order = this.ranked(g, i, legal);
      if (order[0].s >= 900) return order[0].a;            // a kill is a kill
      const cands = order.slice(0, this.opt.width).map((x) => x.a);
      const best = await this.search(g, i, cands, (s, a) => {
        this.forced = { t: 'choose', v: a };
        return s.resume_turn(g.actions_left, g.free_plays_used);
      }, this.dice(g, i, 1));
      return cands[best];
    }

    async discard_choice(g, i) {
      const hand = g.players[i].hand;
      const names = [...new Set(hand)].sort((x, y) => this.keep_value(g, i, x) - this.keep_value(g, i, y) || (x < y ? -1 : 1));
      if (!this.opt.discards || names.length === 1) return names[0];
      const cands = names.slice(0, this.opt.width);
      const best = await this.search(g, i, cands, (s, name) => {
        this.forced = { t: 'discard', v: name };
        return s.resume_turn(0, g.free_plays_used);
      }, this.dice(g, i, 2));
      return cands[best];
    }

    // An attack is coming at me. With reacts on, the copy can't pick up the attacker's turn mid-swing, so it
    // settles this one hit (blocked, sent back, or taken), gives the attacker the rest of their actions, and plays on.
    async react(g, j, amount, style, src, opts) {
      const greedy = super.react(g, j, amount, style, src, opts);
      if (!this.opt.reacts) return greedy;
      const att = 1 - j;
      const w = g.players[att].weapon_card();
      const how = {
        no_bonus: true,                                    // the amount already has the attacker's bonus in it
        ignore_armor: src === 'punch',
        ignore_prayer: src === 'punch',
        shatter: !!w && ((src === 'weapon' && w.legendary) || (src === 'spec' && w.spec === 'sunder')),
      };
      const rest = Math.max(0, g.actions_left - 1);      // whoever's turn it is has (about) this many actions left
      const cands = [greedy, ...[null, ...opts.slice().sort()].filter((x) => x !== greedy)];
      const best = await this.search(g, j, cands, async (s, choice) => {
        this.forced = { t: 'react', v: choice };
        await s.deal(att, amount, style, src, how);
        this.forced = null;
        if (s.winner === null) await s.resume_turn(rest, g.free_plays_used);
      }, this.dice(g, j, 3 + amount));
      return cands[best];
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
