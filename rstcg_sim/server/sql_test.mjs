// The migrations, applied in order to a real Postgres running in node (PGlite, npm i -D @electric-sql/pglite), with
// stand-ins for what Supabase provides (the roles, auth.uid(), auth.jwt(), the Realtime publication); then the bank's
// database functions are tried: opening, paying once, packs, the free pack, the daily cap, reset, online duels paid by
// duel_append, and what a player may and may not touch. node server/sql_test.mjs   ->  "all passed"
import { PGlite } from '@electric-sql/pglite';
import fs from 'fs';
const dir = new URL('./supabase/migrations', import.meta.url).pathname;
const db = new PGlite();
await db.exec(`
  create role anon; create role authenticated; create role service_role bypassrls;
  create schema auth;
  create table auth.users (id uuid primary key, is_anonymous boolean default false);
  create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('t.uid', true), '')::uuid $$;
  create function auth.jwt() returns jsonb language sql stable as $$ select coalesce(nullif(current_setting('t.jwt', true), ''), '{}')::jsonb $$;
  grant usage on schema auth to anon, authenticated, service_role;
  grant usage on schema public to anon, authenticated, service_role;
  create publication supabase_realtime;
  alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
  alter default privileges in schema public grant all on functions to anon, authenticated, service_role;
  alter default privileges in schema public grant all on sequences to anon, authenticated, service_role;
`);
for (const f of fs.readdirSync(dir).sort()) {
  try { await db.exec(fs.readFileSync(`${dir}/${f}`, 'utf8')); console.log('applied', f); }
  catch (e) { console.log('FAILED', f, e.message); process.exit(1); }
}
let fails = 0; const ok = (c, m) => { if (!c) { fails++; console.log('FAIL', m); } };
const q = async (sql, args = []) => (await db.query(sql, args)).rows;
const one = async (sql, args) => Object.values((await q(sql, args))[0])[0];
const A = '11111111-1111-1111-1111-111111111111', B = '22222222-2222-2222-2222-222222222222';
await q(`insert into auth.users (id) values ($1), ($2)`, [A, B]);
await db.exec(`set role service_role`);
ok((await one(`select public.bank_get($1)`, [A])) === null, 'no bank yet');
let r = await one(`select public.bank_open($1, 300, '{"Excalibur":1}', 2, false, 'device')`, [A]);
ok(r.gp === 300 && r.coll.Excalibur === 1 && r.packs === 2, 'opened ' + JSON.stringify(r));
r = await one(`select public.bank_open($1, 9999, '{}', 0, false, 'device')`, [A]);
ok(r.gp === 300, 'opening twice keeps the first');
r = await one(`select public.bank_apply($1, 'bot:7', 'bot', 100, null, 0, false)`, [A]);
ok(r.ok && r.bank.gp === 400, 'bot win paid ' + JSON.stringify(r));
r = await one(`select public.bank_apply($1, 'bot:7', 'bot', 100, null, 0, false)`, [A]);
ok(r.ok && r.again && r.bank.gp === 400, 'same duel twice pays once');
r = await one(`select public.bank_apply($1, 'pack:x', 'pack', -200, '{"Excalibur":1,"Lobster":2}', 1, false)`, [A]);
ok(r.ok && r.bank.gp === 200 && r.bank.coll.Excalibur === 2 && r.bank.coll.Lobster === 2 && r.bank.packs === 3, 'pack ' + JSON.stringify(r));
r = await one(`select public.bank_apply($1, 'pack:x', 'pack', -200, '{"Other":1}', 1, false)`, [A]);
ok(r.ok && r.again && r.cards.Lobster === 2 && r.bank.gp === 200, 'pack retried: same cards, paid once');
r = await one(`select public.bank_apply($1, 'pack:y', 'pack', -300, '{}', 1, false)`, [A]);
ok(!r.ok && r.why === 'short', 'not enough GP');
r = await one(`select public.bank_apply($1, 'free', 'free', 0, '{"Shrimp":1}', 1, true)`, [A]);
ok(r.ok && r.bank.free === true, 'free pack');
r = await one(`select public.bank_apply($1, 'free2', 'free', 0, '{"Shrimp":1}', 1, true)`, [A]);
ok(!r.ok && r.why === 'free', 'free pack only once');
ok((await one(`select public.bank_apply($1, 'bot:1', 'bot', 100, null, 0, false)`, [B])).why === 'nobank', 'no bank: nothing paid');
for (let i = 0; i < 205; i++) await q(`select public.bank_apply($1, $2, 'bot', 50, null, 0, false)`, [A, `bot:c${i}`]);
r = await one(`select public.bank_get($1)`, [A]);
ok(r.gp === 200 + 50 * 199, 'daily cap of 200 bot duels (one was paid before): ' + r.gp);
r = await one(`select public.bank_set($1, 'reset:1', 'reset', 0, '{"Lobster":4}', 0, false)`, [A]);
ok(r.gp === 0 && r.coll.Lobster === 4 && !r.coll.Excalibur && r.free === false, 'reset');
// online payout through duel_append
await one(`select public.bank_open($1, 0, '{}', 0, false, 'starter')`, [B]);
await db.exec(`reset role`);
await q(`insert into public.profiles (id, name) values ($1, 'Annie'), ($2, 'Bobby')`, [A, B]);
await db.exec(`set role service_role`);
async function duel(result) {
  const id = await one(`select public.duel_create($1, $2, 'v', '{}')`, [A, Math.random().toString(36).slice(2, 7).toUpperCase()]);
  await q(`select public.duel_start($1, $2, '{}', '{}', $3, '{"Annie","Bobby"}', null, null)`, [id, B, `{${A},${B}}`]);
  ok(await one(`select public.duel_append($1, 0, '{}', null, null, '{0,0}', $2)`, [id, JSON.stringify(result)]), 'append');
  ok(!(await one(`select public.duel_append($1, 0, '{}', null, null, '{0,0}', $2)`, [id, JSON.stringify(result)])), 'stale append refused');
  return id;
}
const gp = async (u) => (await one(`select public.bank_get($1)`, [u])).gp;
await duel({ winner: 0, reason: 'damage' });
ok(await gp(A) === 100 && await gp(B) === 50, 'win 100 / loss 50: ' + await gp(A) + ' ' + await gp(B));
await duel({ winner: 1, reason: 'concede' });
ok(await gp(A) === 100 && await gp(B) === 150, 'concede: loser 0');
await duel({ winner: null, reason: 'turns' });
ok(await gp(A) === 150 && await gp(B) === 200, 'draw 50 each');
// players: read own bank only, write nothing, call nothing
await db.exec(`reset role; set role authenticated; set t.uid = '${A}'`);
const rows = await q(`select user_id, gp from public.bank`);
ok(rows.length === 1 && rows[0].user_id === A, 'a player sees only their own bank');
for (const sql of [`update public.bank set gp = 99999`, `select public.bank_apply('${A}', 'x', 'bot', 100, null, 0, false)`, `select * from public.bank_log`, `insert into public.bank (user_id) values ('${B}')`]) {
  let refused = false; try { await q(sql); } catch { refused = true; }
  ok(refused, 'refused: ' + sql);
}
console.log(fails ? `${fails} failed` : 'all passed');
