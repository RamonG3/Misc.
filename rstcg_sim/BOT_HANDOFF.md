# RuneScape TCG: bot handoff for Claude Code

This covers what exists for the AI opponents, what was measured, and what to watch for.
The game UI was rebuilt in Claude Design. Its own handoff covers the artifact.
This document covers the rules engine and the bots, which come from the balance simulator in this zip.

## 1. What's in the zip

`rstcg_sim/` holds the Python balance simulator and the earlier browser game. Art and print output are left out to keep the zip small.

| File | What it is |
|---|---|
| `engine.py` | **The rules reference** (1,220 lines). Every rule ruling lives here, and every balance number came from it. |
| `cards.py`, `starter_lists.py` | Card definitions (158 cards, 10 capes) and the three starter decks with their suggested capes. |
| `bot.py` | `HeuristicBot`, the "greedy" bot. It scores every legal action and takes the best. It was used for about a million test games. |
| `planner.py` | `RolloutBot`, the "smart" bot. On close calls it plays each candidate move out to the end, many times, and picks the one that wins most. |
| `web/engine.js`, `web/bot.js` | Line-for-line JS ports of `engine.py` and `bot.py`. They are tested against Python for matching win rates. |
| `web/cards.json` | Card data exported for JS. Rebuild it with `python export_cards.py`. |
| `web/game.src.html`, `web/dist/game.html` | The earlier browser game (pre-Claude Design). It shows how the UI drives the JS engine and bot. |
| `web/test/parity*.py`, `parity.js`, `spec.js` | Python vs JS parity checks (win rates match within noise). |
| `starters27.py`, `sweep.py`, `smart.py`, `replay.py` | Test harnesses. `replay.py` narrates a single game, which is good for watching a bot think. |
| `README.md` | Results of every test round (1 to 27), with what was tried, adopted or cut. |
| `HANDOFF.md` | General project handoff. |

## 2. The agent interface

The engine knows the rules and nothing about strategy. An agent (bot or human UI) gets these calls:

```
setup(game)                                  once, before the game
choose(game, i, legal) -> action             each decision on your turn; legal = game.legal_actions(i)
discard_choice(game, i) -> card name         when over the 7-card hand limit
react(game, j, amount, style, src, opts)     on the OPPONENT's turn: block or reverse an incoming attack?
     -> card name from opts, or None         opts = the Protect prayers / reversal cards you could use
keep_value(game, i, card) -> number          optional; used to pick which card a reaction costs
```

Actions are tuples (arrays in JS), listed in the docstring at the top of `engine.py`:
- `("draw",)`, `("punch",)`, `("swing", style)`, `("free_swing", style)`, `("spec",)`
- `("play", card, target)`, `("quest", target)`, `("claim", cape)`, `("legend", card)`
- `("varrock", card)` (location: discard a card to heal 3)
- `("exchange", card)` (Grand Exchange: free, once a turn, discard a card and draw)

**The engine always produces the legal moves, and the bot only ranks them.** Keep it that way. A bot that builds its own moves will eventually make an illegal one.

The JS engine is `async`: it `await`s the agent, so a human UI can wait for clicks. For bot playouts you'll want a sync path or a sync engine copy, because async per call adds up.

## 3. Speed (measured today)

- **JS:** a full greedy bot vs greedy bot game takes about **1 ms** in Node (starter decks, about 17 turns).
- **Python:** about 1.5 ms per game.

So search is cheap in the browser. The Python RolloutBot runs 4 candidates × 12 playouts, about 48 games per close decision, which is about 50 ms in JS. A Hard bot could afford hundreds of playouts per decision and still answer in well under a second.

**The JS port has no RolloutBot and no `clone()` yet.** Port them from `planner.py` and `engine.py` (`Game.clone`, `Player.clone`, `spend`, `resume_turn`).

## 4. How strong the existing bots are

- **Greedy** plays reasonably, not well, on purpose. Balance tests shouldn't depend on a genius pilot.
- **Smart (RolloutBot)** beats greedy **58–62%** with identical decks, at about 400× the compute.
- **Starter decks, greedy, with suggested capes, head to head:** Melee 48.7%, Ranged 52.8%, Air Mage 48.5%. Balanced.
- **Starter decks vs the 20-deck test field:** about 40–44%. Starters are meant to be a bit weaker than tuned decks.

### Known greedy blind spots (what the smart bot fixes and a good bot must handle)

- **Delayed payoffs.** Quests (multi-step), the Quest Point Cape, The Restless Ghost, and Varrock + its teleport tablet were rated "bot-limited": greedy can't play them and smart can.
- **Capes are win conditions.** A cape gains a counter at the start of its wearer's turn, and the wearer wins at 3. The bot must:
  - race for its own cape,
  - recognise when the opponent is one turn from winning by cape ("doomed": only lethal damage saves you), and
  - consider claiming the opponent's cape. Either player may claim either cape. You can wear only one and can't take it off.

  `HeuristicBot.score` has crude hand-tuned versions of all three.
- **Reactions** (Protect prayers, reversals) fire rarely, about 0.3 a game. The greedy `react` uses a "worth at least 7 damage, or the hit is lethal" threshold. It probably wastes them and holds them too long in about equal measure.
- **Ranged is the weakest style on average** in the simulations. Some of that may be the bot (Rapid and Longrange timing) and not the cards.

## 5. Hidden information: don't let the bot cheat

`Game.clone(view=i)` resamples everything player i can't see before each playout:
- i's own deck order,
- the opponent's hand and deck, pooled and redealt.

So the search never peeks. Any new bot must do the same, or at least never read `opponent.hand` or anyone's deck order.

The engine stores everything in one object, so cheating is one careless line away. A good test: a bot's win rate shouldn't change if you shuffle the opponent's hidden cards before each of its decisions.

## 6. Suggested plan for bot difficulty levels

1. **Easy:** greedy with high noise (`noise` is 0.25 by default; try 2–4), plus maybe a chance to skip reactions or claims.
2. **Normal:** the existing greedy bot, unchanged. It's the one every balance number was measured with.
3. **Hard:** RolloutBot ported to JS. Search costs nothing in the browser, so widen it (more candidates, more playouts, search every decision and not just close ones).
4. **Stretch:** Information Set MCTS, or a light learned evaluation. Only bother once Hard stops being enough. Rollouts with determinization already capture most of the value in a game like this.

Measure each level in a bot-vs-bot ladder (Easy < Normal < Hard, across all three starters and both seats) before anyone plays it. A human-facing Hard bot that loses to Normal does happen.

## 7. Matching the Claude Design game

**This is the most important part.** Claude Design's version may have its own engine or a modified copy of `web/engine.js`. Before writing bots:

1. Find its rules code and diff it against `web/engine.js` and `engine.py`.
2. If it reimplemented rules, either swap in `engine.js` as the single source of truth or run a parity test: same decks and seeds, bot vs bot, win rates and average turns within noise (see `web/test/parity.js`).
3. Every balance number above assumes **this** engine. A rules drift means the numbers are for a different game.

## 8. Rules gotchas that trip up bots (all handled in `engine.py`)

- **Your deck is your life.** Damage mills cards off the top of your deck.
  - You lose when you'd draw or take damage from an empty deck.
  - Healing can't push your deck past 60.
  - There is no Respawn.
- **Turn structure:**
  - 2 actions a turn, and you may end your turn early.
  - Opening hand 6, hand limit 7.
  - No damage on the very first turn of the game.
- **Free plays:** at most 3 a turn. Quest steps (1 free a turn) and the Grand Exchange swap don't count toward that cap. Buff prayers, Wooden Shield and Grand Exchange are free to play.
- **Materials stay in play and are checked, never spent.** Exceptions:
  - cards with `expend`, which spend them,
  - destroy cards, which remove the opponent's.
- **Legendaries:**
  - One Legendary per deck, and the Wise Old Man counts as one.
  - A Legendary weapon can't attack on the turn it's played.
- **Protect prayers** block one attack of their style completely (Round 24). Duel Arena shuts off all prayers.
- **Elite prayers** last 3 of your turns, counting the one you play them on. Special attack energy belongs to the player (max 4, +1 a turn).
- **Staff of Light** counts as 2 of whichever elemental rune you have the most of in play. Iban's Staff needs any elemental rune.
- **Lumbridge** saves you once per player. **Grand Exchange** is free, once a turn.
- **Capes:** 10 of them, each with its own count to hit. The counts are in `cards.py` and the rules doc. Examples: Attack = 6 Melee hits (Punch counts), Magic = 3 Magic hits, Quest Point = 6 QP.

Full player-facing rules are in the rulebook doc: https://claude.ai/artifact/87VaH6ai4i5UXEdbRrHkfC.
The designer rules and test results are in the rules doc: https://claude.ai/artifact/AA5kzzcL6QWhBjmwqwhntj.

## 9. Useful commands

```
python starters27.py 200                 # starters head to head (with/without capes) and vs the field, greedy
python starters27.py 20 --smart          # same with the smart bot (slow; run in background)
python replay.py "Melee" "Ranged" 3      # narrate games turn by turn (add --smart)
python export_cards.py                   # cards.py -> web/cards.json
node web/test/parity.js field 100        # JS engine + bot vs the Python numbers
```

Use fixed seeds for everything. Bot A vs bot B comparisons should run on the same seeds in both seats, and with enough games: ±1 point needs thousands, ±5 needs about 100.

## 10. Design intent for the bots

- **Ramon expects to play against the bot more than against friends.** The bot *is* the game for most sessions.
- It should feel like a player, not a calculator. Some deliberate imperfection on Easy and Normal is fine; obviously dumb moves aren't. The worst offenders: never using a quest, sitting on a reversal until death, ignoring an enemy cape at 2 counters.
- Tell the player what the bot did (the UI log), so its plays read as intentional.
- No peeking (section 5). Players notice a cheating bot fast in a hidden-hand card game.
