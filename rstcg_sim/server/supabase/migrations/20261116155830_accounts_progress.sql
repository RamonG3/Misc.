-- Accounts (phase 3): cloud save.
--
-- A signed-in player's progress, as their devices report it. It is the game's own save, rstcg-profile-v1
-- (GP, collection, decks, packs, tutorial and tips, records), kept as one document per account.
--
-- Nothing here checks what a device reports. That is the "cloud save" decision (Ramon, 2026-11-16): fine
-- among friends, and the server becomes the bank before Find a match lets strangers in.
--
-- Guests (anonymous sign-ins) can never save: a guest has no way back to a save from another device, so
-- there is nothing to keep for them. (The next migration stops them reading the table as well.)
--
-- Two devices must not silently overwrite each other. Every save names the revision it was made from
-- (progress_save). If another device saved in between, nothing is written, and the caller gets the newer
-- copy back to merge with its own (web/cloudsave.js) before it tries again.
--
-- Applied to the Supabase project "RuneScape TCG" on 2026-11-16 (the note about guests is worded a little
-- differently there). Check the rules with server/progress_check.sql.

create table public.progress (
  user_id uuid primary key references auth.users (id) on delete cascade,
  data jsonb not null,
  rev bigint not null default 1,                 -- goes up by one with every save
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint progress_is_object check (jsonb_typeof(data) = 'object'),
  constraint progress_size check (octet_length(data::text) <= 400000)
);
comment on table public.progress is 'Cloud save: one document of game progress per signed-in account, as its devices report it.';

alter table public.progress enable row level security;

create policy "Players read their own progress" on public.progress
  for select to authenticated
  using (user_id = (select auth.uid()));

create policy "Accounts start their own progress" on public.progress
  for insert to authenticated
  with check (user_id = (select auth.uid()) and (select (auth.jwt() ->> 'is_anonymous')::boolean) is false);

create policy "Accounts save their own progress" on public.progress
  for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()) and (select (auth.jwt() ->> 'is_anonymous')::boolean) is false);

-- players may send only the document itself: the revision and the dates are the server's
revoke all on public.progress from anon, authenticated;
grant select on public.progress to authenticated;
grant insert (user_id, data) on public.progress to authenticated;
grant update (data) on public.progress to authenticated;

create function public.progress_touch() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.user_id := old.user_id;
  new.created_at := old.created_at;
  new.rev := old.rev + 1;
  new.updated_at := now();
  return new;
end $$;

create trigger progress_touch before update on public.progress
  for each row execute function public.progress_touch();

-- Save progress made from revision p_rev (null: there is nothing saved yet).
--   saved:              { ok: true,  rev, at }
--   someone was first:  { ok: false, rev, at, data }   their copy, to merge with
--   nothing to update:  { ok: false, rev: null }       (a revision was named, but nothing is saved)
-- It runs as the player, so the policies above decide what they may touch.
create function public.progress_save(p_data jsonb, p_rev bigint default null) returns jsonb
language plpgsql security invoker set search_path = '' as $$
declare
  v_uid uuid := (select auth.uid());
  v public.progress%rowtype;
begin
  if v_uid is null then
    raise exception 'not signed in' using errcode = '28000';
  end if;
  if p_rev is null then
    insert into public.progress (user_id, data) values (v_uid, p_data)
      on conflict (user_id) do nothing
      returning * into v;
  else
    update public.progress set data = p_data
      where user_id = v_uid and rev = p_rev
      returning * into v;
  end if;
  if found then
    return jsonb_build_object('ok', true, 'rev', v.rev, 'at', v.updated_at);
  end if;
  select * into v from public.progress where user_id = v_uid;
  if not found then
    return jsonb_build_object('ok', false, 'rev', null);
  end if;
  return jsonb_build_object('ok', false, 'rev', v.rev, 'at', v.updated_at, 'data', v.data);
end $$;

revoke execute on function public.progress_save(jsonb, bigint) from public, anon;
grant execute on function public.progress_save(jsonb, bigint) to authenticated;
revoke execute on function public.progress_touch() from public, anon, authenticated;
