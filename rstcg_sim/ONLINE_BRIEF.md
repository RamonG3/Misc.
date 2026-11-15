# Brief: online play for the RuneScape TCG Arena

**Status (2026-11-15): planned, nothing built yet.** Ramon has made the main decisions (below). This file sets out what he wants, how it will work, and what is still open. Read `HANDOFF.md` for the wider project and `CLAUDE.md` for how the Arena's code fits together.

## What I want

Two ways to play another person, both from one **Play Online** screen:

- **"I just want to play someone": Find a match.** Press one button and get paired with whoever else is looking.
- **"I want to play my friend": Challenge a friend.** Send a link, or read out a short code. When the friend opens it, you duel.

It is one web app, so PC and phone play each other.

## Decided (Ramon, 2026-11-15)

1. **The server referees from day one.** The duel runs on the server, and each player is sent only what they may see. Nobody can peek at a hand or a deck.
2. **A name for now, accounts very soon.** The first time you go online you pick a name, with no email. Accounts follow soon after, so progress (GP, collection, decks) doesn't reset between devices.
3. **Online decks are your own decks,** under the same rules as against bots.
4. **Online duels pay GP like bot duels:** 100 for a win, 50 for a loss.

## Why the server referees

**Today:** a duel is the engine plus two agents: `human` (you) and `botAgent` (the bot).
- The engine asks whichever agent it needs: `choose`, `react` or `discard_choice`.
- Everything random, every shuffle included, comes from one seeded `RNG`. The same seed and the same answers always give the same duel; saved duels resume this way.

**The design not taken ("lockstep"): both devices run the duel and only pass moves.**
- It is quicker to build, but whoever holds the seed can work out both decks' order and the opponent's hand.
- A player with the browser's developer tools could read them.

**With a referee, the seed and the decks never leave the server.**

**Measured (200 bot-vs-bot duels on the starter decks, 2026-11-15):**
- A duel is about 36 decisions on average, 65 at most.
- The move list is about 1 KB as JSON.
- Replaying a whole duel from its move list takes about 0.5 ms in node, 8 ms at worst, and every replay matched the original.

So the server can afford to rebuild the duel from scratch on every move.

## How the referee works

### On the server

- **One Edge Function, `duel`, runs the same `engine.js` and `cards.json`.**
  - A build step makes the Deno copy, as `build_arena.py` does for the page.
  - Never keep a second engine by hand.
- **Each request rebuilds the duel:**
  1. Load the match, its secret seed and its move list.
  2. Rebuild the `Game` and answer the engine's questions from the list.
  3. The first question the list can't answer is the decision now pending: who must answer, what kind (choose, react or discard), and the legal answers.
  - It will use the synchronous engine copy (`unwait`, as the hard bot does). Its agent throws "decision needed" when the list runs out, so the rebuild simply stops there.
- **When a player sends a move, the server:**
  1. checks that the move comes from the player being asked, and that it is one of the legal answers;
  2. adds it to the move list;
  3. runs on to the next pending decision;
  4. works out what each player may see;
  5. tells both players something changed. Each then fetches its own view.
- **The clock:** each pending decision gets a deadline, kept by the server. A claim that the other player has run out of time is checked against it.

### On the device

- **The duel screen gets a second driver.**
  - Bot duels keep running the engine on the device, offline, as now.
  - Online, a new `runOnlineDuel` feeds the same screen from the server.
- **Each view from the server becomes a display-only `Game`.**
  - Everything public is real, and so is your own hand. Hidden cards are blank placeholders.
  - The aim is for `duelHTML`, `snap` and `playChanges` to keep working on it, so cards still fly and capes still tick.
  - Legal moves and prompts come from the server, not from the device's engine.
- **Making a move:** your answer goes to the server, and the screen waits briefly for the next view.
  - While the other player decides, the bot's "thinking" spot shows "Waiting for Zezima…" with their clock.

### What each player sees

| Who | What |
| --- | --- |
| **Both players** | Everything in play, both discard piles, deck and hand sizes, cape progress, the duel log. |
| **Only you** | Your hand, and the cards you draw. |
| **Nobody** | The order of either deck (your own too) and the seed. |
| **Hidden until the duel starts** | Each player's cape, as now: both capes flip face up when the duel starts. |
| **Hidden all duel** | Your opponent's deck list, as in MTG Arena (open question 3). |

### Testing the referee

- **Node tests.** The referee code also runs in node. Tests play thousands of duels through it and check that:
  - no view sent to a player ever names a card that player can't see (a "no leaks" test);
  - every duel replays identically;
  - illegal and out-of-turn moves are refused.
- **Browser test.** Two headless browsers duel each other through a real Supabase project, from start to finish, like `arena_play.js`.

## The two modes

### Challenge a friend

1. **Start it:** Play Online → pick a deck and cape → **Challenge a friend**.
2. **The server opens a duel room with a 5-character code.** It skips look-alike characters: no 0/O, no 1/I/L.
   - On a phone, the share sheet sends a link (`https://runescapetcg.netlify.app/#duel-K7MQX`) to Messages, WhatsApp and so on.
   - On PC there is a Copy button.
   - The code is shown big, so it can be read out.
3. **The friend joins:** they open the link, or type the code under **Join with a code**. They see "Ramon challenges you", pick a deck and cape, and press Accept.
4. **The duel starts:** the server flips for first player, picks the seed, and starts the duel.

- **Waiting room:** "Waiting for your friend…" with Cancel. Rooms expire after a day.
- **Afterwards:** Rematch, which both players must press. It keeps the same room.

### Find a match

1. **Start it:** Play Online → pick a deck and cape → **Find a match**.
2. **Queue:** first come, first served; ratings can come later. The server pairs you with the player who has waited longest.
3. **Match:** "Opponent found: Zezima" → the duel starts.

- **Show who's around:** how many players are online and searching, so an empty queue isn't a mystery.
- **Nobody there:** after a while, offer **Play a bot instead**.
- **With strangers:**
  - Preset emotes only (Good luck, Well played, Thanks, Oops), no free chat.
  - A short word filter on names.
  - **Block**, so you are never paired with that player again.

### Shared by both modes

- **Names:** the first time you go online, you pick a name, and names are unique.
- **Timers:** online needs a clock, or a player who walks away freezes the duel. Proposal (open question 1):
  - 60 seconds per decision, shown once 15 remain.
  - 20 seconds to answer an attack.
  - Running out three times loses the duel.
- **Leaving:** Leave keeps your seat, and your clock still runs.
- **Dropping out:** if a device loses connection or goes to sleep, reopening the app fetches the duel as it stands and carries on. After 3 minutes away, the other player may claim the win.
- **Concede** counts as a loss.
- **Same version only:** each build gets a version stamp, a hash of `engine.js` and `cards.json`.
  - The device and the server must agree.
  - An out-of-date app is told to update. The app's offline copy fetches the new version whenever it's online, so a reload is enough.
- **Results and GP:**
  - Results are stored with the duel, and the result screen names the opponent.
  - GP is 100 for a win and 50 for a loss.
  - Until accounts exist, the device adds the GP to its local save, as for bots. With accounts, the server pays it.
- **Decks:**
  - Until accounts exist, the server checks the deck rules (60 cards, at most 4 copies, at most 1 Legendary, real cards) but has to trust that you own the cards.
  - With accounts, it checks them against your collection.

## Backend: Supabase

**Ramon's account today** (checked 2026-11-15):
- Organization "RG3 Designs", on the free plan.
- It has one project: RuneScape Kingdoms TTRPG Website.
- The free plan allows 2 active projects, so the TCG gets its own free project.

**What Supabase provides:**
- Anonymous sign-in: an account with no email, which can later be linked to Google or email, keeping the same account.
- Postgres tables with row-level security.
- Realtime: the "something changed" pings, and presence for "players online".
- Edge Functions (Deno), for the referee and matchmaking.

**Free-plan limits** (pricing page, checked 2026-11-15):
- 200 simultaneous Realtime connections.
- 2 million Realtime messages a month.
- 500,000 Edge Function calls a month.
- 500 MB of database.

Even if every move costs three function calls (the move, plus each player fetching their view), a duel is about 110 calls. That leaves room for about 4,500 duels a month.

**The catch: free projects pause after a week with no activity.** Online play would then stop until someone resumes the project in the dashboard. Two fixes:
- A weekly keep-alive ping, such as a scheduled GitHub Action in the Misc. repo (free). It has to sit on the repo's default branch to run on a schedule.
- The Pro plan at $25 a month, which never pauses.

**Other facts:**
- An Edge Function gets 2 seconds of CPU per request. Rebuilding a duel takes milliseconds.
- Anonymous sign-up is rate-limited per IP address. Supabase recommends adding a CAPTCHA (such as Cloudflare Turnstile) to stop abuse.
- A Claude session can set up the project, tables, policies and functions through the Supabase connector. The app itself still goes to Netlify by drag-and-drop.

**Alternatives considered:**
- **Cloudflare Durable Objects:** one live object per duel room, on the free plan since April 2025, sleeping when idle and waking on the next request. A good fit for a referee, but it needs a Cloudflare account and its own deploy tool, while Supabase is already connected.
- **Peer to peer (WebRTC):** can't hide cards, because both devices would run the duel.

## Data (first cut)

- **`profiles`:** user id, name, created. With accounts, it also holds progress.
- **`matches`:**
  - id, invite code, kind (challenge or queue), status (waiting, ready, playing, done, abandoned);
  - the two players, version stamp, the pending decision and its deadline, result, timestamps.
  - Both players can read these.
- **`match_secrets`:** the seed, both deck lists and both capes. **No player can read this table.** Only the referee function can.
- **`moves`:** match id, sequence number, seat, the move. Moves are only ever added, and only by the referee.
- **Pairing and joining run as database functions,** so two players can't grab the same opponent.

## Where it plugs into the code

- **Menu:** "Play Online" (now "coming later") opens a new Play Online screen.
  - It is a new `onlineHTML` in `SCREENS`, reusing the deck and cape pickers from `pickHTML`.
- **Duel:** a new `runOnlineDuel(match)` sits beside `runDuel`.
  - It builds the display-only `Game` from each view.
  - It turns the server's pending decision into the same `ui.pending` prompts `decide()` shows today.
  - It sends your answer instead of resolving a local promise.
  - Names come from the match, not "Bot".
- **Network code:** a new module `online.js`, inlined by `build_arena.py` like the others.
  - The Supabase JavaScript client is bundled into the build. No CDN.
  - Offline play makes no network calls.
- **Server code:** `server/duel/` holds the Edge Function. A build step copies the current `engine.js` and `cards.json` into it.
- **Saves:** a new key, `rstcg-online-v1`, holds your online id and name and the duel you're in. Don't touch the existing keys.
- **Online play only works from the Netlify app** (runescapetcg.netlify.app, installed or in the browser).
  - The claude.ai artifact blocks connections to other sites.
  - In the artifact, the Play Online screen points to the app.

## Phases

0. **Groundwork:**
   - the Supabase project and its keep-alive;
   - tables and policies;
   - anonymous sign-in and names;
   - the referee function (rebuild, move checks, views, clock);
   - its node tests (no leaks, replays, refused moves).
1. **The duel screen online:** `runOnlineDuel`, the display-only `Game`, waiting and clocks, rejoining. Tested with two headless browsers.
2. **Challenge a friend:** links and codes, the waiting room, rematch.
3. **Accounts and cloud progress** (soon after, at Ramon's request):
   - **Signing in:** sign in with Google and/or email, linked to the name you already have.
   - **Progress on the server:** GP, collection, decks, packs and tutorial state, so PC and phone share progress.
   - **The server takes over:** online decks are checked against your collection, and the server pays online GP.
   - **Setup needed:**
     - Google sign-in needs a Google OAuth client, made once in Google Cloud.
     - Email sign-in needs a mail-sending service, because Supabase's built-in email only reaches the project's own team members.
   - **The first sign-in on a second device** asks which progress to keep.
   - This phase doesn't depend on online duels, so it could also come first.
4. **Find a match:** the queue, players online, emotes, blocking, and recent opponents you can challenge again.

**Later:**
- a friends list with who's online;
- challenge notifications;
- a ranked ladder.

## Still open for Ramon

1. **Timers:** 60 seconds a decision, 20 seconds to answer an attack, three strikes? [suggested]
2. **Supabase:** OK to create a free Supabase project for the TCG, and add a weekly keep-alive (or pay $25 a month for Pro)?
3. **Deck lists:** should your opponent's deck list stay hidden during a duel? [suggested: hidden, as in MTG Arena]
4. **When accounts come (phase 3):**
   - Google, email, or both?
   - When two devices both have progress, keep the one with more cards, or always ask?
5. **Order:** accounts before or after Challenge a friend?

## Done means (phases 0 to 2)

- **Full duels across devices:** two people on different devices (PC and phone) can duel by link or code from start to finish, with no page errors.
- **Nothing leaks:** no view either player receives contains a card they shouldn't see. The node "no leaks" test checks thousands of duels.
- **The server enforces the rules:** it refuses moves that are illegal, out of turn or from the wrong player.
- **Rejoining works:** closing the app mid-duel and reopening it carries on where the duel was.
- **Clocks work:**
  - A player who stops moving loses on time.
  - If a player disconnects for 3 minutes, the other can claim the win.
- **Tested:** two headless browsers play full duels through the real server in a test.
- **Nothing else changes:** the bot game, offline play and the artifact work as before.

## Sources

- Supabase pricing (free-plan limits, 2 active projects, pausing, Pro): https://supabase.com/pricing
- Free project pausing: https://supabase.com/docs/guides/platform/free-project-pausing
- Anonymous sign-ins (linking to Google or email later, rate limit, CAPTCHA): https://supabase.com/docs/guides/auth/auth-anonymous
- Built-in email only reaches the project's team; custom SMTP: https://supabase.com/docs/guides/auth/auth-smtp
- Edge Function limits (2 s CPU): https://supabase.com/docs/guides/functions/limits
- Cloudflare Durable Objects on the free plan: https://developers.cloudflare.com/changelog/2025-04-07-durable-objects-free-tier/
