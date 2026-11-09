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

## What's next, in Ramon's order

### 1. AI opponents
- Brief: `BOTS_BRIEF.md`. It covers three difficulty levels, no peeking at hidden information, deterministic play, and the phone speed budget.
- Harness: `web/test/bot_arena.js`.

### 2. Finish the phone version
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
