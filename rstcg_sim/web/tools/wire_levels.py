r"""Wire the difficulty levels (web/bot_levels.js) into the Arena. Run once, then delete this file.

    python web/tools/wire_levels.py web/arena.src.html ["<script>/*__BOT__*/</script>"]

The second argument is the line that inlines bot.js, if it is spelled differently in your arena.src.html.

Why a script: the Arena's source was not in this repo when the bots were written, so the wiring was built and
tested against a copy taken back out of the published v19 page (the build of that copy is byte-for-byte v19).
Every edit below is an exact, single-match replacement. On a source that matches v19 it applies cleanly; on one
that has moved on it stops at the first edit that doesn't fit and changes nothing, and that edit is done by hand.

What it does to arena.src.html:
  - two more inlined modules after bot.js: /*__ENGINESYNC__*/ and /*__BOTLEVELS__*/
  - `level` in the setup, in newConfig() and in a rematch (easy | normal | hard; a duel saved without one is normal)
  - buildGame() makes the bot with makeBot(level, botSeed)
  - botAgent() lets the bot think during its "thinking" pause, and saves a hard bot's answers with the duel
    (an extra `bot` list in rstcg-arena-duel-v1; saves without it still load) so resuming doesn't think again
  - three tiles on the pick screen, styled like the cape tiles, and the level under the result

build_arena.py then needs the two modules (the first is engine.js with its async/await words taken out, which
the hard bot plays its playouts on; bot_levels.js makes the same copy for itself under node):

    def engine_without_waiting(src):
        src = re.sub(r"\bawait\s+", "", re.sub(r"\basync\s+", "", src))
        return ("(function () {\n  var module = { exports: {} };\n  (function (window) {\n" + src +
                "\n  })({});\n  window.RSTCGSync = module.exports;\n})();")

        "/*__ENGINESYNC__*/": engine_without_waiting(read("engine.js")),
        "/*__BOTLEVELS__*/": read("bot_levels.js"),

Then: python web/build_arena.py && node web/test/arena_levels.js   (and the usual web/test/ui/run_all.js)
"""
import sys
path = sys.argv[1]
BOT_PLACEHOLDER = sys.argv[2] if len(sys.argv) > 2 else "<script>/*__BOT__*/</script>"
s = open(path, encoding="utf-8").read()
n = 0
def rep(old, new, count=1):
    global s, n
    assert s.count(old) == count, f"expected {count} match(es), found {s.count(old)}: {old[:90]!r}"
    s = s.replace(old, new); n += 1

# --- modules -------------------------------------------------------------------------------------------
rep(BOT_PLACEHOLDER + "\n", BOT_PLACEHOLDER + "\n<script>/*__ENGINESYNC__*/</script>\n<script>/*__BOTLEVELS__*/</script>\n")
rep("  const { HeuristicBot } = window.RSTCGBot;\n", "  const { HeuristicBot } = window.RSTCGBot;\n  const LV = window.RSTCGLevels;\n")

# --- css: the level tiles are cape tiles, a size smaller ---------------------------------------------------
rep(".ct-sug { position: absolute;", """.ct.lv { width: 146px; height: 152px; padding: 7px; gap: 4px; }
.ct.lv .ct-art { height: 74px; }
.ct.lv .ct-art img { height: 64px; }
.ct.lv .ct-cond { padding: 2px 12px; }
.ct-sug { position: absolute;""")

# --- state ---------------------------------------------------------------------------------------------
rep("    replaying: false, queue: [], decisions: [], config: null,\n    setup: Object.assign({ youDeck: 'Melee', youCape: 'Attack Cape', botDeck: 'Air Mage' }, store.get('rstcg-arena-setup') || {}),",
    "    replaying: false, queue: [], decisions: [], botMoves: [], botQueue: [], config: null,\n    setup: Object.assign({ youDeck: 'Melee', youCape: 'Attack Cape', botDeck: 'Air Mage', level: 'normal' }, store.get('rstcg-arena-setup') || {}),")
rep("  function newConfig() {\n", """  // The bot's level: easy, normal or hard. A duel saved before there were levels is a normal one.
  const levelOf = (id) => (LV.LEVELS.some((l) => l.id === id) ? id : 'normal');
  const levelName = (id) => LV.LEVELS.find((l) => l.id === levelOf(id)).name;
  const LEVEL_WORD = { easy: 'Easy', normal: 'Normal', hard: 'Hard' };
  const LEVEL_ART = { easy: 'Bronze Dagger', normal: 'Mithril Sword', hard: 'Rune Scimitar' };      // bronze, mithril, rune

  function newConfig() {
""")
rep("botCape: fits[Math.floor(Math.random() * fits.length)], youFirst: Math.random() < 0.5 };",
    "botCape: fits[Math.floor(Math.random() * fits.length)], youFirst: Math.random() < 0.5, level: levelOf(s.level) };")

# --- the bot's seat ------------------------------------------------------------------------------------
old_agent = """  function botAgent(bot) {
    return {
      setup: (g) => bot.setup(g),
      async choose(g, i, legal) {
        if (g !== ui.g) return NEVER();
        if (!ui.replaying) { await settle(g); if (g !== ui.g) return NEVER(); render(); await sleep(thinkTime(g, i)); }
        if (g !== ui.g) return NEVER();
        return bot.choose(g, i, legal);
      },
      async react(g, j, amount, style, src, opts) {
        if (!ui.replaying) await sleep(fast() ? 120 : rand(600, 1100));      // a beat to "decide" whether to answer
        if (g !== ui.g) return NEVER();
        const r = bot.react(g, j, amount, style, src, opts);
        if (r && !ui.replaying) { await showMove(g, j, null, r); }
        return r;
      },
      discard_choice(g, i) { const n = bot.discard_choice(g, i); g.log.push({ text: `Bot discards ${n} (hand limit)`, who: i }); return n; },
    };
  }
"""
new_agent = """  // `keep` is for a bot that thinks hard: its answers are saved with the duel, and a resumed duel reads them
  // back instead of thinking them all through again. Each one is checked against the game as it is read.
  function botAgent(bot, keep) {
    const same = (a) => JSON.stringify(a);
    const recall = (t, ok) => {
      if (!keep || !ui.botQueue.length) return null;
      const d = ui.botQueue.shift();
      if (d.t === t && ok(d.v)) { ui.botMoves.push(d); return d; }
      ui.botQueue = [];                                       // the save and the game disagree: think from here on
      return null;
    };
    const kept = (t, v) => { if (keep) { ui.botMoves.push({ t, v }); if (!ui.replaying) saveGame(); } };
    return {
      setup: (g) => bot.setup(g),
      async choose(g, i, legal) {
        if (g !== ui.g) return NEVER();
        const old = recall('c', (v) => legal.some((a) => same(a) === same(v)));
        const think = () => (old ? legal.find((a) => same(a) === same(old.v)) : bot.choose(g, i, legal));
        let a;
        if (ui.replaying) a = await think();
        else {
          await settle(g); if (g !== ui.g) return NEVER(); render();
          [a] = await Promise.all([think(), sleep(thinkTime(g, i))]);        // it does its thinking during the pause
        }
        if (g !== ui.g) return NEVER();
        if (!old) kept('c', a);
        return a;
      },
      async react(g, j, amount, style, src, opts) {
        if (!ui.replaying) await sleep(fast() ? 120 : rand(600, 1100));      // a beat to "decide" whether to answer
        if (g !== ui.g) return NEVER();
        const old = recall('r', (v) => v === null || opts.includes(v));
        const r = old ? old.v : await bot.react(g, j, amount, style, src, opts);
        if (g !== ui.g) return NEVER();
        if (!old) kept('r', r);
        if (r && !ui.replaying) { await showMove(g, j, null, r); }
        return r;
      },
      async discard_choice(g, i) {
        const old = recall('d', (v) => g.players[i].hand.includes(v));
        const n = old ? old.v : await bot.discard_choice(g, i);
        if (g !== ui.g) return NEVER();
        if (!old) kept('d', n);
        g.log.push({ text: `Bot discards ${n} (hand limit)`, who: i });
        return n;
      },
    };
  }
"""
rep(old_agent, new_agent)
rep("    agents[you] = human; agents[1 - you] = botAgent(config.tutorial ? tutBot() : new HeuristicBot(config.botSeed));\n",
    """    const level = levelOf(config.level);
    agents[you] = human;
    agents[1 - you] = config.tutorial ? botAgent(tutBot())
      : botAgent(LV.makeBot(level, config.botSeed, level === 'hard' ? { pause: 12 } : undefined), level === 'hard');
""")

# --- saving and resuming -------------------------------------------------------------------------------
rep("  async function runDuel(config, decisions) {\n", "  async function runDuel(config, decisions, botMoves) {\n")
rep("    ui.config = config; ui.decisions = [];\n", "    ui.config = config; ui.decisions = [];\n    ui.botMoves = []; ui.botQueue = Array.isArray(botMoves) ? [...botMoves] : [];\n")
rep("  function saveGame() { if (ui.config && !ui.config.tutorial && ui.g && ui.g.winner === null) store.set(STORE, { config: ui.config, decisions: ui.decisions }); }\n",
    """  // The duel in progress: the setup and your decisions, plus a hard bot's answers so far (see botAgent).
  const duelSave = () => Object.assign({ config: ui.config, decisions: ui.decisions }, ui.botMoves.length ? { bot: ui.botMoves } : {});
  function saveGame() { if (ui.config && !ui.config.tutorial && ui.g && ui.g.winner === null) store.set(STORE, duelSave()); }
""")
rep("      case 'resume': { const saved = store.get(STORE); if (saved) runDuel(saved.config, saved.decisions); return; }",
    "      case 'resume': { const saved = store.get(STORE); if (saved) runDuel(saved.config, saved.decisions, saved.bot); return; }")
rep("youCape: c.youCape, botDeck: c.botDeck, botCape:", "youCape: c.youCape, level: levelOf(c.level), botDeck: c.botDeck, botCape:")
rep("  function snapshot() { return ui.config && ui.g && ui.g.winner === null ? { config: ui.config, decisions: ui.decisions } : {}; }",
    "  function snapshot() { return ui.config && ui.g && ui.g.winner === null ? duelSave() : {}; }")
rep("    if (data && data.config && Array.isArray(data.decisions)) { runDuel(data.config, data.decisions); return; }",
    "    if (data && data.config && Array.isArray(data.decisions)) { runDuel(data.config, data.decisions, data.bot); return; }")

# --- the picker ----------------------------------------------------------------------------------------
rep("      case 'pickbot': ui.setup.botDeck = v; ui.oppOpen = false; store.set('rstcg-arena-setup', ui.setup); break;\n",
    "      case 'pickbot': ui.setup.botDeck = v; ui.oppOpen = false; store.set('rstcg-arena-setup', ui.setup); break;\n"
    "      case 'picklevel': ui.setup.level = levelOf(v); store.set('rstcg-arena-setup', ui.setup); break;\n")
rep("""    const vs = `<div class="panel" style="position:absolute;left:60px;top:810px;width:1800px;height:230px;padding:26px 34px;display:flex;align-items:center;gap:40px">""",
    """    // how good the bot is
    const lv = levelOf(s.level);
    const levels = `<div style="margin-left:auto;display:flex;flex-direction:column;gap:8px"><span class="lbl">Bot difficulty</span>
      <div style="display:flex;gap:10px" role="group" aria-label="Bot difficulty">${LV.LEVELS.map((l) => { const pic = ART[LEVEL_ART[l.id]]; return `<button type="button" class="ct lv" data-do="picklevel" data-v="${l.id}" aria-pressed="${lv === l.id}" title="${esc(l.blurb)}">
        <div class="ct-art">${pic ? `<img src="${pic.src}" alt="">` : ''}</div><span class="ct-nm">${esc(l.name)}</span><span class="ct-cond"><small>${LEVEL_WORD[l.id]}</small></span></button>`; }).join('')}</div></div>`;
    const vs = `<div class="panel" style="position:absolute;left:60px;top:810px;width:1800px;height:230px;padding:26px 34px;display:flex;align-items:center;gap:36px">""")
rep("""      <div style="margin-left:auto;display:flex;flex-direction:column;align-items:flex-end;gap:12px">
        <button type="button" class="btn-g" data-do="start" style="width:380px;height:110px;font-size:38px">START DUEL</button>""",
    """      ${levels}
      <div style="margin-left:auto;display:flex;flex-direction:column;align-items:flex-end;gap:12px">
        <button type="button" class="btn-g" data-do="start" style="width:350px;height:110px;font-size:36px">START DUEL</button>""")

# --- the result ----------------------------------------------------------------------------------------
rep("""        <div style="display:flex;align-items:center;gap:12px;margin-top:6px">${line}</div></div>""",
    """        <div style="display:flex;align-items:center;gap:12px;margin-top:6px">${line}</div>
        ${r.config ? `<span class="lbl" style="font-size:13px">against ${esc(levelName(r.config.level))} · ${LEVEL_WORD[levelOf(r.config.level)]}</span>` : ''}</div>""")

open(path, "w", encoding="utf-8", newline="\n").write(s)
print(f"{n} edits applied to {path}. Now add the two modules to build_arena.py (see the top of this file).")
