# Brief: AI opponents for the RuneScape TCG Arena

You're picking up one job on a working game: better computer opponents. The rules engine, the
browser game (the "Arena") and a simple bot already exist and are tested. Read this first, then
`HANDOFF.md` for the wider project.

## What I want

- **Three difficulty levels** picked on the pre-duel screen, defaulting to the middle one:
  - Easy: makes real mistakes, good for learning.
  - Normal: about today's bot.
  - Hard: plays to win and should feel it.
  - Names with a RuneScape flavour are welcome (for example Goblin / Guard / Champion).
- **No cheating at any level.** A bot may not look at hidden information: the other player's hand,
  or the order of either deck. If Hard can't be hard without peeking, tell me rather than peek.
- **Optional, later:** a personality per deck, so a melee bot plays aggressively and a mage bot hoards
  runes. Not needed for the first version.

## Where things are

| Path | What |
| --- | --- |
| `web/engine.js` | The rules: a line-for-line port of `engine.py`. **Don't change the rules.** |
| `web/bot.js` | `HeuristicBot`: greedy. It scores every legal action and takes the best, with a little noise. |
| `web/cards.json` | Cards, capes and decklists, exported from `cards.py` (`export_cards.py`). Don't hand-edit it. |
| `web/arena.src.html` | The Arena game, one file. `python web/build_arena.py` builds `web/dist/arena.html`. |
| `planner.py` | `RolloutBot`, the Python "smart" bot used for balance testing. It plays close decisions out many times on copies of the game, resampling hidden information. This is the best starting idea for Hard. |
| `web/test/bot_arena.js` | Headless bot vs bot on the real engine. Start here. |

## The bot interface

An agent is any object with these methods. Each may return a Promise, and the engine awaits it.

```js
setup(g)                                   // once, at the start of a duel
choose(g, i, legal)                        // return one entry of legal (arrays like ['play', 'Bronze Bar'], ['swing'], ['pass'])
react(g, j, amount, style, src, opts)      // an attack is coming at seat j: return a card name from opts, or null to take it
discard_choice(g, i)                       // over the hand limit: return a card name in g.players[i].hand
```

- `g` is the live `Game`. Read it, never mutate it.
- Useful parts of `Game`:
  - `g.players[i]`, with `hand`, `deck`, `discard`, `materials`, `lp` (the deck size, which is the life total), `weapon_card()`, `qp`, `quest` and more.
  - `g.legal_actions(i)`, `g.compute_damage(...)`, `g.progress(p, cape)`, `g.claimable(i)`, `g.cape_table`, `g.rules`.
- A search bot needs to copy the game. Python uses deepcopy. `engine.js` has no clone yet, so add one
  outside the rules: a `cloneGame(g, agents)` in the bot file, or a helper module, is fine.
  - The clone must resample what the bot can't see: the opponent's hand and both deck orders.

## Hard constraints

1. **Deterministic for a given seed.** A saved duel is resumed by replaying only the human's decisions;
   the bot's moves are recomputed.
   - Each bot is built as `new XBot(config.botSeed)`, and the same seed and state must give the same choice.
   - Use the engine's seeded `RNG` class. Never use `Math.random()` or `Date.now()`.
   - Never cut a search off by wall-clock time. Use a fixed rollout count instead.
2. **Fast enough on a phone.** The game is played on a Pixel 7 Pro, where a phone is roughly 5–10× slower than `node` on a desktop.
   - Aim for under 300 ms per decision on the phone.
   - That means under about 40 ms on average, and under 150 ms at worst, in `bot_arena.js`.
   - If Hard needs more, keep the page responsive (yield between rollouts) and tell me the cost.
3. **Keep `HeuristicBot` as it is.** The balance numbers in `README.md` were measured with it. Add new bots
   beside it, either in `bot.js` or a new `web/bot_*.js` that `build_arena.py` inlines.
   - Use the same browser/node module pattern as `bot.js`: `module.exports` plus a `root.RSTCGBot` style global.
4. **Don't edit `engine.js`, `cards.json` or `engine.py`.** JS and Python must stay in parity
   (`web/test/parity*.py`, `parity.js`). If a bot needs an engine helper, ask first.

## Wiring it into the game (after the bots are good)

- `buildGame()` in `arena.src.html` creates the opponent:
  `agents[1 - you] = botAgent(new HeuristicBot(config.botSeed))`.
  - `botAgent` adds the "thinking" pause and the move reveal, so new bots shouldn't do any pacing themselves.
- Add the difficulty to the config built by `newConfig()`, so a saved or resumed duel keeps its level.
- Add a picker to the pre-duel screen (`pickHTML`), styled like the cape picker.
- Show the level on the result screen.
- Build with `python web/build_arena.py`, then check:
  - `node web/test/arena_play.js 3` plays full duels through the real UI by mouse.
  - It must report "no page errors".

## How to measure

```
node web/test/bot_arena.js 40 hard greedy           # starter decks, both seat orders
node web/test/bot_arena.js 40 hard greedy all       # every starter and tournament deck
```

- Register each new bot in the `BOTS` table at the top of the script.
- The script prints the win rate per matchup and overall, plus milliseconds per decision.
- Greedy vs greedy is about 50%, taking about 0.02 ms per decision.

## Done means

- **Easy loses to greedy about 25–35% of the time.** It should make human-looking mistakes, like holding the wrong card or attacking into a block, not random nonsense.
- **Normal stays close to greedy:** about 45–55%.
- **Hard beats greedy at least 65% across the starter decks**, without peeking, within the speed budget.
- **Results are deterministic.** The same seed played twice gives the same game, so check this in the harness.
- **The bots are in the game:** there's a difficulty picker, `arena_play.js` is clean, and there's a short note in `HANDOFF.md`.
