-- A check of the cloud save's rules (server/supabase/migrations/*_accounts_progress.sql), run in the database
-- itself: as an account, as a second account, as a guest and as nobody. Run it through the Supabase connector's
-- execute_sql (or the dashboard's SQL editor). It always ends in an error, on purpose: the error's message is
-- the report, and failing is what makes the database throw the test players and their saves away again.
--   PROGRESS CHECK all passed (17 checks)          or          PROGRESS CHECK FAILED: ...
do $check$
declare
  a constant uuid := '00000000-0000-4000-8000-0000000000a1';   -- an account
  b constant uuid := '00000000-0000-4000-8000-0000000000b2';   -- another account
  g constant uuid := '00000000-0000-4000-8000-0000000000c3';   -- a guest
  r jsonb;
  n int;
  bad text[] := '{}';
  checks int := 0;
begin
  insert into auth.users (id, aud, role, is_anonymous) values (a, 'authenticated', 'authenticated', false),
    (b, 'authenticated', 'authenticated', false), (g, 'authenticated', 'authenticated', true);

  -- ---------- an account ----------
  perform set_config('role', 'authenticated', true);
  perform set_config('request.jwt.claims', jsonb_build_object('sub', a, 'role', 'authenticated', 'is_anonymous', false)::text, true);

  r := public.progress_save('{"gp": 100}'::jsonb, null);
  checks := checks + 1;
  if not (r @> '{"ok": true, "rev": 1}') then bad := bad || ('first save: ' || r::text); end if;

  r := public.progress_save('{"gp": 5}'::jsonb, null);                     -- "nothing saved yet", but there is
  checks := checks + 1;
  if not (r @> '{"ok": false, "rev": 1, "data": {"gp": 100}}') then bad := bad || ('second first-save: ' || r::text); end if;

  r := public.progress_save('{"gp": 200}'::jsonb, 1);
  checks := checks + 1;
  if not (r @> '{"ok": true, "rev": 2}') then bad := bad || ('save from rev 1: ' || r::text); end if;

  r := public.progress_save('{"gp": 999}'::jsonb, 1);                      -- a device that missed revision 2
  checks := checks + 1;
  if not (r @> '{"ok": false, "rev": 2, "data": {"gp": 200}}') then bad := bad || ('stale save: ' || r::text); end if;

  select count(*) into n from public.progress;
  checks := checks + 1;
  if n <> 1 then bad := bad || ('an account sees ' || n || ' saves'); end if;

  checks := checks + 1;
  begin
    update public.progress set rev = 99 where user_id = a;                -- the revision is the server's
    bad := bad || 'a player could set the revision';
  exception when insufficient_privilege then null;
  end;

  checks := checks + 1;
  begin
    r := public.progress_save('[1, 2]'::jsonb, 2);
    bad := bad || 'a list was saved as progress';
  exception when check_violation then null;
  end;

  checks := checks + 1;
  begin
    r := public.progress_save(jsonb_build_object('junk', repeat('x', 400000)), 2);
    bad := bad || 'a 400 KB save was accepted';
  exception when check_violation then null;
  end;

  checks := checks + 1;
  begin
    delete from public.progress where user_id = a;
    bad := bad || 'a player could delete a save';
  exception when insufficient_privilege then null;
  end;

  -- ---------- another account ----------
  perform set_config('request.jwt.claims', jsonb_build_object('sub', b, 'role', 'authenticated', 'is_anonymous', false)::text, true);
  select count(*) into n from public.progress;
  checks := checks + 1;
  if n <> 0 then bad := bad || ('another account sees ' || n || ' saves'); end if;

  update public.progress set data = '{"gp": 1}'::jsonb where user_id = a;
  get diagnostics n = row_count;
  checks := checks + 1;
  if n <> 0 then bad := bad || 'another account changed a save'; end if;

  -- ---------- a guest, and a token that doesn't say ----------
  perform set_config('request.jwt.claims', jsonb_build_object('sub', g, 'role', 'authenticated', 'is_anonymous', true)::text, true);
  checks := checks + 1;
  begin
    r := public.progress_save('{"gp": 1}'::jsonb, null);
    bad := bad || ('a guest saved: ' || r::text);
  exception when insufficient_privilege then null;
  end;

  -- an account's own save, asked for with a token that still says "guest"
  perform set_config('request.jwt.claims', jsonb_build_object('sub', a, 'role', 'authenticated', 'is_anonymous', true)::text, true);
  select count(*) into n from public.progress;
  checks := checks + 1;
  if n <> 0 then bad := bad || 'a guest token read a save'; end if;
  r := public.progress_save('{"gp": 1}'::jsonb, 2);
  checks := checks + 1;
  if not (r @> '{"ok": false}') or r ? 'data' then bad := bad || ('a guest token updated or was shown a save: ' || r::text); end if;

  perform set_config('request.jwt.claims', jsonb_build_object('sub', g, 'role', 'authenticated')::text, true);
  checks := checks + 1;
  begin
    r := public.progress_save('{"gp": 1}'::jsonb, null);
    bad := bad || ('a token with no is_anonymous saved: ' || r::text);
  exception when insufficient_privilege then null;
  end;

  -- ---------- nobody ----------
  perform set_config('role', 'anon', true);
  perform set_config('request.jwt.claims', '{"role": "anon"}', true);
  checks := checks + 1;
  begin
    select count(*) into n from public.progress;
    bad := bad || 'someone not signed in could read saves';
  exception when insufficient_privilege then null;
  end;

  perform set_config('role', 'none', true);
  select count(*) into n from public.progress where user_id = a and data = '{"gp": 200}'::jsonb and rev = 2;
  checks := checks + 1;
  if n <> 1 then bad := bad || 'the save is not what the account last saved'; end if;

  if array_length(bad, 1) is null then
    raise exception 'PROGRESS CHECK all passed (% checks)', checks;
  end if;
  raise exception 'PROGRESS CHECK FAILED: %', array_to_string(bad, ' | ');
end $check$;
