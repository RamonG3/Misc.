# RuneScape TCG: handoff (2026-11-09)

This file describes where things stand. `CLAUDE.md` covers how the code fits together and the ground rules. `README.md` covers the balance history (Rounds 1–27).

## Links

- **The Arena (latest build, claude.ai artifact):** https://claude.ai/artifact/DTVmjFMHpVvqVYmtS29tRm (version 19, built from `web/dist/arena.html`).
- **Rules doc:** https://claude.ai/artifact/AA5kzzcL6QWhBjmwqwhntj
- **Player rulebook:** https://claude.ai/artifact/87VaH6ai4i5UXEdbRrHkfC
- **Card templates / design canvas:** https://claude.ai/artifact/NdJNNtUEMTV8FUHc7zTPUg. The card back is the "Crest", artboard `BackCrest.dc.html`.
- **Card art:** Ramon's Google Drive folder (the link is kept out of this public repo; ask Ramon).
  - Files are named by item; look in a `Detail` subfolder when there is one.
  - `art/art_map.json` maps each card to its Drive file ID; downloaded images are in `art/img/`.
  - `rstcg_missing_art.csv` (one folder up, on Ramon's PC) lists the 64 cards still without art.

## The Arena: what's done

- **Main menu** with an idle attract mode, GP, and How to Play.
- **Settings:** motion, music, sfx, tips, "show tutorial again".
- **Pick screen:** your deck, your cape (blind), the opponent's deck.
- **Duel on a tilted 3D table.**
  - Drag or click to play cards, with animated moves, attacks, milling, heals, quests and capes.
  - Cape progress has animated ticks with sound, plus a "READY TO CLAIM" stamp.
  - The bot's hand is shown as card backs at the top.
  - The log drawer is at the top right.
  - Phone-width stages get a shorter, wider table automatically.
- **Results:** GP payout (100 win / 50 loss / 0 concede). Leaving a duel saves it; concede is a separate choice.
- **Collection:** you start with the three starter decks' cards; basic materials are unlimited.
- **My Decks and the deck editor:** filters, search, paging, drag to add, live legality.
- **Deck Wizard:** pick up to 2 cards you own and it builds a whole 60-card deck around them.
  - It adapts the Starter/Tournament lists, then tests up to 4 builds with greedy-vs-greedy games and keeps the best.
  - The result shows its win rate. You can save it, tweak it in the editor, or pick again.
- **Packs:** 200 GP for 6 cards (3 Common, 2 Uncommon, 1 Rare that sometimes upgrades to a Legendary), with a pack-opening animation.
- **Guided tutorial:** a scripted 19-step first duel with stacked decks and a scripted bot.
  - It is offered on first launch and from the menu, and finishing it grants a free pack.
  - After it, one-time tips show up in real duels.
- **Audio:** synthesised music with an intense layer (latches on once either deck is ≤10), plus sfx.
- **Xbox controller:** gold focus ring; A/B/X/Y, LB/RB, Menu/View; the right stick scrolls.

## AI opponents (2026-11-10)

Three levels in `web/bot_levels.js`. `makeBot(level, seed)` builds one, and Normal is the default.

| Level | Name | What it is | Wins against greedy, starter decks | Time per decision (node) |
| --- | --- | --- | --- | --- |
| easy | Goblin | Greedy with a new player's habits. It overlooks some of its options, is slow to claim capes and forgets reactions. It never misses a kill or a cape about to win. | 29.4% of 5,400 games | 0.02 ms |
| normal | Guard | `HeuristicBot`, unchanged. | 50.1% | 0.02 ms |
| hard | Champion | Looks ahead by playing its best few moves out on copies of the game. | 66.9% of 2,880 games (±1.7) | 30 ms on average, 107 ms at worst |

- **Across all 12 decks the bot can be given:** Champion 64.8% (±2.3), Goblin 31.1%. Champion beats Goblin 81% of the time.
- **No peeking.** No bot reads the other player's hand or the order of either deck.
  - Champion's copies pool the opponent's hand and deck and deal them back at random, and shuffle its own deck.
  - The check: every decision is asked a second time with the hidden cards dealt differently, and the answer must be the same. Champion: 2,499 decisions, none changed. Goblin: 34,935, none changed.
  - The bots do know both deck lists, as greedy always has.
- **Same seed, same game.** Every random number comes from the engine's seeded `RNG`. Champion's search is bounded by how many turns it simulates, never by the clock, and it keeps nothing between decisions. Every measured run was replayed in a second process and matched.
- **How Champion thinks.**
  - It tries its six best moves on the same deals of the hidden cards and plays each to the end. A move that falls clearly behind is dropped. Its first choice keeps its place unless another move is clearly ahead.
  - In those playouts the opponent is greedy. Champion's own seat is played by `SharpBot`: greedy that knows the game is a race. Steps towards a cape are worth more to it, it won't claim second, and it sees a kill that takes the whole turn. That alone is worth 3 points; the search on top of it is worth the rest.
  - The playouts run on `engine.js` with its `async`/`await` words taken out. Same rules, word for word, about a fifth faster. Nothing is kept by hand: node makes the copy when it loads, and the build makes one for the page.
- **On the phone.** Expect 0.2 to 0.4 seconds a decision on the Pixel (worked out from the node times; not measured on the phone yet). In the Arena it thinks during the bot's "thinking" pause and lets the page draw every 12 playouts, so at normal speed there is no extra wait. At fast speed a hard bot's move can take about 0.3 seconds longer.
- **Saves.** A hard bot's answers are saved with the duel, as an extra `bot` list in `rstcg-arena-duel-v1`, so resuming doesn't think them all through again. Saves without the list still load: the bot thinks again and lands on the same position. A duel saved before there were levels is a normal one.
- **If cards or rules change:** there is nothing to retrain. Run the ladder again. The settings are the `EASY`, `SHARP` and `HARD` tables in `bot_levels.js`.
- **Tried and dropped:** deciding reactions and discards by playout (no gain), and stopping playouts early to score the position with a learned estimate (worse than playing them out, for the same time).
- **Engine speed-up (2026-11-15, Ramon approved it on condition the parity tests didn't move):** `count`, `main_elements`, `meets` and `family_count` in `engine.js` are now plain loops instead of building arrays on every call. Same logic as `engine.py`, which is unchanged.
  - Checked: 3,000 greedy games across all 12 decks play move for move the same on the old and new engine (103,219 decisions). 40,000 random boards count the same, 2,381 of them with Staff of Light-style weapons. The JS side of `parity27.py` gives byte-identical results, and the Python comparison is normal noise (87 matchups, mean |z| 0.69).
  - Gain: greedy games 31% faster. Champion 46 → 36 ms a decision on the same seeds, with identical games and results (67.1% of 432).
  - Champion's search budget was left as it was, so it is the same strength, only quicker. Spending the time on more playouts instead would make it stronger; that would be a balance call for Ramon.

```
node web/test/bot_ladder.js 160 hard greedy             # any two bots: starter pairings, both seats, same seeds, 4 cores
node web/test/bot_ladder.js 6 hard greedy all           # every starter and tournament deck
node web/test/bot_ladder.js 8 hard greedy --peek-test   # also ask every decision twice with the hidden cards dealt differently
node web/test/bot_selftest.js                           # the pieces the bots stand on; must end "all passed"
node web/test/arena_levels.js                           # the levels in the built page, in a real browser; must end "all passed"
```

## What's next, in Ramon's order

### 1. AI opponents: done, and in the Arena (2026-11-15)
- The three levels are above. The picker (Goblin / Guard / Champion tiles) is on the pre-duel screen, Guard by default, and the result screen names the level.
- The Arena source and its tests are now in this repo (`web/arena.src.html`, `build_arena.py`, `web/test/ui/`). The generated `arena_assets.js` / `arena_fonts.css` stay out: `python web/recover_generated.py <saved Arena page>` rebuilds them, or `arena_assets.py` with the art folder.
- Still to check: Champion's real speed on Ramon's Pixel. That's the first step of the phone work.

### 2. Finish the phone version (next)
Ramon has decided: landscape only, the same file as the PC version, and an installable app hosted on Netlify. The tilted table already works at phone size. What's left:

- **Touch controls.**
  - Long-press a card to zoom it. Hover zoom is mouse-only today (`mouseover` handler); `data-zoom` marks what can zoom.
  - Check that drag-to-play works by finger on the tilted table (it uses pointer events and should).
  - The deck editor removes cards by right-click. Phones need another way, such as long-press or the visible − button on deck rows.
  - Make touch targets big enough at phone scale. The stage is scaled down about 2.2× on a Pixel 7 Pro (892×412 CSS px), so a 44px button becomes about 20px.
- **Rotate-your-phone overlay** when the phone is held in portrait.
- **Phone layouts** for the menu, My Decks, the deck editor, the wizard and the packs screens. They currently scale the 1920×1080 stage down, which works but is small. Test at 892×412.
- **Installable app on Netlify.**
  - Add a web manifest (landscape orientation, icons from the Crest/skill icons) and a service worker so it installs and works offline.
  - Deploy `web/dist/arena.html` as `index.html`.
  - Ramon has a Netlify account. He already hosts runescapekingdoms.netlify.app, a different project.

### 3. Later
- **Card art** for the 64 cards in `rstcg_missing_art.csv`. Rune Arrows art exists on Drive but isn't downloaded yet.
- **Online play against other people.** Ramon wants this eventually; nothing is built yet.
- **Rebuild decks from the full tested pool,** to see what independent deckbuilding does to balance.
- **Print-and-play:** `print/build_pnp.py` + `print/render.js` → PDFs. Done for the 3 starters.

## Simulator state (Rounds 1–27)

The cards and rules are settled through Round 27 (rebuilt starters). See `README.md` and the rules doc.

Watch list, from Ramon:
- Ranged is the weakest style on average.
- Earth/Fire Blast and Wave are weak.
- The elemental staves are starter-grade.
- Anti-dragon Shield is worse than Kiteshield.

Ramon accepts that some deck will always be weakest. He prefers fixing weak cards to cutting them, but will cut for a better game.

Common simulator commands:
- `python group2.py 40` (greedy) or `python group2.py 5 --smart` (slow).
- `python bestof.py 40`.
- `python starters27.py` for the current starters.
