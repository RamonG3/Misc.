-- A smoke test of the live `duel` function, run from inside the database (pg_net).
--
-- Why from SQL: Claude's cloud container and the desktop shell can't reach *.supabase.co (their network
-- allowlist refuses it), but the Supabase connector can run SQL, and pg_net can make HTTP calls from the
-- database. A request is only sent when its transaction commits, so `smoke.req` commits and then waits
-- for the answer; that only works when each DO block below is sent as a query of its own.
-- Last run 2026-11-15 against version d5389b9ea6: everything below behaved as expected (average 126 ms a
-- request in the function). Delete the test users and the smoke schema afterwards (the last block).
--
-- Until anonymous sign-ins are switched on (Authentication -> Sign In / Providers), the test makes two
-- password users directly in auth.users; once they are on, POST auth/v1/signup with {} gives a token too.

-- 1. pg_net (already installed by migration pg_net_for_tests) and two throwaway players
create extension if not exists pg_net with schema extensions;
with u as (
  insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
    raw_app_meta_data, raw_user_meta_data, created_at, updated_at, confirmation_token, email_change, email_change_token_new, recovery_token)
  select '00000000-0000-0000-0000-000000000000', gen_random_uuid(), 'authenticated', 'authenticated', e, extensions.crypt('smoke-test-pw-71', extensions.gen_salt('bf')), now(),
    '{"provider":"email","providers":["email"]}'::jsonb, '{}'::jsonb, now(), now(), '', '', '', ''
  from unnest(array['smoke1@rstcg.test', 'smoke2@rstcg.test']) as e
  returning id, email
)
insert into auth.identities (provider_id, user_id, identity_data, provider, last_sign_in_at, created_at, updated_at)
select id::text, id, jsonb_build_object('sub', id::text, 'email', email, 'email_verified', true), 'email', now(), now(), now() from u;

-- 2. sign them in (send these four statements together; the commit sends the requests)
select net.http_post(
  url := 'https://udmaqoxyhiwpvxkrugwr.supabase.co/auth/v1/token?grant_type=password',
  headers := jsonb_build_object('Content-Type', 'application/json', 'apikey', 'sb_publishable_eaN07rbCUAT_-FtdXkB2Iw_IjdGi_bJ'),
  body := jsonb_build_object('email', e, 'password', 'smoke-test-pw-71')
) from unnest(array['smoke1@rstcg.test', 'smoke2@rstcg.test']) as e;
commit;
select pg_sleep(3);
select id, status_code from net._http_response order by id desc limit 2;

-- 3. the helpers: tokens by player, and one request waited for
create schema if not exists smoke;
create table if not exists smoke.log (step serial primary key, label text, status int, body jsonb, at timestamptz default clock_timestamp());
create table if not exists smoke.kv (k text primary key, v text);
insert into smoke.kv  -- A = smoke1, B = smoke2 (the two newest sign-ins)
select case when content::jsonb->'user'->>'email' = 'smoke1@rstcg.test' then 'tokA' else 'tokB' end, content::jsonb->>'access_token'
from net._http_response where content like '%access_token%' order by id desc limit 2;
insert into smoke.kv
select case when content::jsonb->'user'->>'email' = 'smoke1@rstcg.test' then 'idA' else 'idB' end, content::jsonb->'user'->>'id'
from net._http_response where content like '%access_token%' order by id desc limit 2;
create or replace procedure smoke.req(who text, path text, body jsonb, label text, inout res jsonb default null, method text default 'POST', extra jsonb default '{}')
language plpgsql as $$
declare rid bigint; r record; tries int := 0; tok text; hdr jsonb;
begin
  select v into tok from smoke.kv where k = 'tok' || who;
  hdr := jsonb_build_object('Content-Type', 'application/json', 'apikey', 'sb_publishable_eaN07rbCUAT_-FtdXkB2Iw_IjdGi_bJ', 'Authorization', 'Bearer ' || tok) || extra;
  if method = 'GET' then
    rid := net.http_get(url := 'https://udmaqoxyhiwpvxkrugwr.supabase.co/' || path, headers := hdr, timeout_milliseconds := 20000);
  else
    rid := net.http_post(url := 'https://udmaqoxyhiwpvxkrugwr.supabase.co/' || path, headers := hdr, body := body, timeout_milliseconds := 20000);
  end if;
  commit;
  loop
    perform pg_sleep(0.1);
    select status_code, content, error_msg into r from net._http_response where id = rid;
    exit when found;
    tries := tries + 1;
    if tries > 250 then raise exception 'no answer to %', label; end if;
  end loop;
  begin res := jsonb_build_object('status', r.status_code, 'body', r.content::jsonb);
  exception when others then res := jsonb_build_object('status', r.status_code, 'text', coalesce(r.content, r.error_msg)); end;
  insert into smoke.log(label, status, body) values (label, r.status_code, res->'body');
  commit;
end $$;

-- 4. names, then a challenge and a join (each DO block on its own)
do $$
declare res jsonb; a text; b text;
begin
  select kv.v into a from smoke.kv kv where k = 'idA'; select kv.v into b from smoke.kv kv where k = 'idB';
  call smoke.req('A', 'functions/v1/duel', '{"op":"hello"}', 'hello: intact must be true', res);
  call smoke.req('A', 'rest/v1/profiles', jsonb_build_object('id', a, 'name', 'You'), 'name "You" (refused)', res);
  call smoke.req('A', 'rest/v1/profiles', jsonb_build_object('id', b, 'name', 'Imposter'), 'a name for someone else (refused)', res);
  call smoke.req('A', 'rest/v1/profiles', jsonb_build_object('id', a, 'name', 'Smoke Ann'), 'name Smoke Ann', res);
  call smoke.req('B', 'rest/v1/profiles', jsonb_build_object('id', b, 'name', 'smoke ann'), 'same name, other case (refused)', res);
  call smoke.req('B', 'rest/v1/profiles', jsonb_build_object('id', b, 'name', 'Smoke Bo'), 'name Smoke Bo', res);
end $$;
-- Swap VERSION below for the one hello reports.
do $$
declare res jsonb; code text; m text;
  deckA jsonb := '{"Bronze Bar":9,"Mithril Bar":13,"Bronze Dagger":2,"Bronze Scimitar":3,"Bronze Mace":1,"Mithril Sword":2,"Mithril Scimitar":3,"Excalibur":1,"Slash":3,"Stab":2,"Crush":1,"Bronze Platebody":2,"Wooden Shield":2,"Kiteshield":2,"Lobster":1,"Swordfish":1,"Burst of Strength":2,"Piety":1,"Ricochet":2,"Protect from Magic":1,"Doric":1,"Vanaka":1,"Wilderness":1,"Doric''s Quest":1,"Gunnar''s Crusade":1,"Smith''s Hammer":1}';
  deckB jsonb := '{"Normal Logs":9,"Willow Logs":12,"Shortbow":2,"Willow Shortbow":3,"Magic Shortbow":1,"Bronze Arrows":3,"Mithril Arrows":3,"Rapid":4,"Longrange":2,"Leather Body":2,"Green D''hide Body":1,"Wooden Shield":2,"Kiteshield":3,"Shrimp":1,"Lobster":2,"Sharp Eye":2,"Rigour":1,"Mirror Shield":1,"Protect from Melee":1,"Lowe":1,"Horvik":1,"Grand Exchange":1,"Sheep Shearer":1,"Tinderbox":1}';
  ver text := 'VERSION';
begin
  call smoke.req('A', 'functions/v1/duel', jsonb_build_object('op','challenge','deck',deckA - 'Doric','cape','Attack Cape','version',ver), 'challenge, 59 cards (refused)', res);
  call smoke.req('A', 'functions/v1/duel', jsonb_build_object('op','challenge','deck',deckA,'cape','Attack Cape','version',ver), 'challenge', res);
  code := res->'body'->>'code'; m := res->'body'->>'match';
  insert into smoke.kv values ('code', code), ('match', m) on conflict (k) do update set v = excluded.v;
  call smoke.req('B', 'functions/v1/duel', jsonb_build_object('op','view','match',m), 'B views a room that isn''t B''s (refused)', res);
  call smoke.req('B', 'functions/v1/duel', jsonb_build_object('op','peek','code',lower(code)), 'peek', res);
  call smoke.req('A', 'functions/v1/duel', jsonb_build_object('op','join','code',code,'deck',deckA,'cape','Attack Cape','version',ver), 'A joins own (refused)', res);
  call smoke.req('B', 'functions/v1/duel', jsonb_build_object('op','join','code',code,'deck',deckB,'cape','Defence Cape','version',ver), 'join', res);
  call smoke.req('A', 'functions/v1/duel', jsonb_build_object('op','view','match',m), 'A first view', res);
  call smoke.req('B', 'functions/v1/duel', jsonb_build_object('op','view','match',m), 'B first view', res);
end $$;

-- 24 moves at random, whoever must decide; every 6th, the other player tries to move out of turn
do $$
declare res jsonb; m text; x jsonb; seat int; who text; other text; p jsonb; mv jsonb; legal jsonb; n int; vs jsonb := '{}';
begin
  select kv.v into m from smoke.kv kv where kv.k = 'match';
  select body into x from smoke.log where label = 'A first view'; vs := jsonb_set(vs, '{A}', x);
  select body into x from smoke.log where label = 'B first view'; vs := jsonb_set(vs, '{B}', x);
  for i in 1..24 loop
    x := vs->'A';
    exit when x->'result' is not null and x->'result' <> 'null'::jsonb;
    p := x->'pending';
    seat := (p->>'seat')::int;
    who := case when (x->>'you')::int = seat then 'A' else 'B' end;
    other := case when who = 'A' then 'B' else 'A' end;
    call smoke.req(who, 'functions/v1/duel', jsonb_build_object('op','view','match',m,'since',(vs->who->>'seq')::int), 'view ' || who, res);
    vs := jsonb_set(vs, array[who], res->'body');
    mv := res->'body';
    p := mv->'pending';
    if i % 6 = 0 then
      call smoke.req(other, 'functions/v1/duel', jsonb_build_object('op','move','match',m,'seq',(mv->>'seq')::int,'move',jsonb_build_object('t',p->>'t','v',null)), 'out of turn (refused)', res);
    end if;
    if p->>'t' = 'choose' then
      legal := p->'legal'; n := jsonb_array_length(legal);
      if random() < 0.1 then x := '["pass"]'; else x := legal->(floor(random() * (n - 1)))::int; end if;
    elsif p->>'t' = 'react' then
      x := case when random() < 0.5 then p->'opts'->0 else 'null'::jsonb end;
    else
      legal := mv->'state'->'players'->((mv->>'you')::int)->'hand';
      x := legal->(floor(random() * jsonb_array_length(legal)))::int;
    end if;
    call smoke.req(who, 'functions/v1/duel', jsonb_build_object('op','move','match',m,'seq',(mv->>'seq')::int,'move',jsonb_build_object('t',p->>'t','v',x)), 'move ' || who || ' ' || (p->>'t') || ' ' || x::text, res);
    if res->>'status' = '200' then vs := jsonb_set(vs, array[who], res->'body'); end if;
    call smoke.req(other, 'functions/v1/duel', jsonb_build_object('op','view','match',m,'since',(vs->other->>'seq')::int), 'view ' || other, res);
    vs := jsonb_set(vs, array[other], res->'body');
  end loop;
  insert into smoke.kv values ('vs', vs::text) on conflict (k) do update set v = excluded.v;
end $$;

-- The player to move walks away; past the deadline the other looks again (expect a late move and a
-- strike). Then the things a player must never reach (expect 403s), and a concede.
do $$
declare res jsonb; m text; vs jsonb; x jsonb; who text; other text;
begin
  select kv.v into m from smoke.kv kv where kv.k = 'match';
  select kv.v::jsonb into vs from smoke.kv kv where kv.k = 'vs';
  x := vs->'A';
  who := case when (x->>'you')::int = (x->'pending'->>'seat')::int then 'A' else 'B' end;
  other := case when who = 'A' then 'B' else 'A' end;
  perform pg_sleep(67);
  call smoke.req(other, 'functions/v1/duel', jsonb_build_object('op','view','match',m,'since',(vs->other->>'seq')::int), 'view after the deadline', res);
  call smoke.req(other, 'rest/v1/match_secrets?select=*', null, 'read match_secrets (refused)', res, 'GET');
  call smoke.req(other, 'rest/v1/moves?select=*', null, 'read moves (refused)', res, 'GET');
  call smoke.req(other, 'rest/v1/rpc/duel_load', jsonb_build_object('p_match', m), 'call duel_load (refused)', res);
  call smoke.req(other, 'rest/v1/matches?select=id,status,seq,strikes', null, 'read own matches', res, 'GET');
  call smoke.req(who, 'functions/v1/duel', jsonb_build_object('op','move','match',m,'move',jsonb_build_object('t','concede')), 'concede', res);
  call smoke.req(other, 'functions/v1/duel', jsonb_build_object('op','move','match',m,'seq',(res->'body'->>'seq')::int,'move',jsonb_build_object('t','choose','v','["pass"]'::jsonb)), 'move after the end (refused)', res);
end $$;

-- What happened
select step, label, status, left(body::text, 200) from smoke.log order by step;

-- 5. clean up
delete from auth.users where email in ('smoke1@rstcg.test', 'smoke2@rstcg.test');   -- cascades to profiles, matches, moves
drop schema smoke cascade;
delete from net._http_response;                                                       -- it held the test tokens
