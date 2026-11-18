# The online referee (Supabase)

The server side of online play. `../ONLINE_BRIEF.md` explains the design, what's built and what's next.

- `supabase/functions/duel/index.js`: the `duel` Edge Function (Deno). Its `gen/` folder is made by `node build.js` from `../web/engine.js`, `../web/cards.json`, `../web/referee.js` and `../web/deckrules.js`; never edit it.
- `supabase/migrations/`: the database, as applied to the project "RuneScape TCG" (`udmaqoxyhiwpvxkrugwr`).
- `supabase/config.toml`: for the Supabase CLI (`npx supabase functions deploy duel --project-ref udmaqoxyhiwpvxkrugwr --use-api --yes`).
- `rarity.json`: the non-Common rarities, for the one-Legendary rule (refreshed by `build.js` from `../web/arena_assets.js` when that's present).
- `set1.json`: every card in Set 1 (refreshed the same way), for the bank: with `../web/packs.js` it says what a pack can hold and where a collection starts.
- `sql_test.mjs`: every migration applied to a real Postgres in node (PGlite, a dev dependency), then the bank's database functions tried. Migrations are applied to the live project through the Supabase connector, never by the Action: apply one before pushing the function that needs it.
- `test_local.mjs`: the function in node against a pretend database (`fake_db.mjs`, the tables and database calls in memory, mirroring the migrations: keep them in step). `../web/test/referee_test.js`: the referee itself.
- `smoke.sql`: a live test through the database (`pg_net`), for when nothing else can reach supabase.co.

Deploys: `.github/workflows/rstcg-referee.yml` runs the tests and redeploys `duel` on every push that changes the rules, the cards, the referee or anything here. The repo secret `SUPABASE_ACCESS_TOKEN` was added on 2026-11-15.
