-- Online play, phase 0: names, duel rooms, the referee's secrets and move lists.
--
-- Players can read everyone's names and their own duels, and pick their own name. Everything else goes
-- through the `duel` Edge Function, which runs the referee (web/referee.js) with the service role.
-- Applied to the Supabase project "RuneScape TCG" (udmaqoxyhiwpvxkrugwr) as migration 20261115230722 online_v1.

-- ---------- names ----------
create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  name text not null,
  created_at timestamptz not null default now(),
  -- 3 to 12 letters, digits, spaces, _ or -, starting and ending with a letter or digit, no double spaces
  constraint profiles_name_shape check (name ~ '^[A-Za-z0-9][A-Za-z0-9 _-]{1,10}[A-Za-z0-9]$' and name !~ '  '),
  constraint profiles_name_reserved check (lower(name) not in ('you', 'bot'))
);
create unique index profiles_name_unique on public.profiles (lower(name));
alter table public.profiles enable row level security;
create policy "Players see every name" on public.profiles for select to authenticated using (true);
create policy "Players pick their own name" on public.profiles for insert to authenticated with check (id = (select auth.uid()));
create policy "Players change their own name" on public.profiles for update to authenticated
  using (id = (select auth.uid())) with check (id = (select auth.uid()));
revoke all on public.profiles from anon;
revoke delete, truncate, references, trigger on public.profiles from authenticated;

-- ---------- duels ----------
create table public.matches (
  id uuid primary key default gen_random_uuid(),
  kind text not null default 'challenge' check (kind in ('challenge', 'queue')),
  code text,                                 -- the 5-character invite code (challenges)
  status text not null default 'waiting' check (status in ('waiting', 'playing', 'done', 'cancelled')),
  host uuid not null references public.profiles (id) on delete cascade,
  guest uuid references public.profiles (id) on delete cascade,
  seats uuid[],                              -- who plays first and who second, once the duel starts
  names text[],                              -- their names, by seat
  version text not null,                     -- the engine and card data the duel is played with
  seq int not null default 0,                -- how many moves so far
  pending jsonb,                             -- {seat, t}: who must decide, and what kind of decision
  deadline timestamptz,                      -- when that decision runs out of time
  strikes int[] not null default '{0,0}',    -- times each seat has run out of time
  result jsonb,                              -- {winner: seat or null, reason}
  created_at timestamptz not null default now(),
  started_at timestamptz,
  ended_at timestamptz,
  updated_at timestamptz not null default now()
);
create unique index matches_open_code on public.matches (code) where status = 'waiting';
create index matches_host on public.matches (host);
create index matches_guest on public.matches (guest);
alter table public.matches enable row level security;
create policy "Players see their own duels" on public.matches for select to authenticated
  using ((select auth.uid()) = host or (select auth.uid()) = guest);
revoke all on public.matches from anon;
revoke insert, update, delete, truncate, references, trigger on public.matches from authenticated;

-- The seed and the decks. No player can read this: only the referee.
create table public.match_secrets (
  match_id uuid primary key references public.matches (id) on delete cascade,
  host_entry jsonb not null,                 -- {cards, cape} the host brought
  guest_entry jsonb,
  setup jsonb                                -- {seed, decks, capes, names}: what the referee rebuilds the duel from
);
alter table public.match_secrets enable row level security;
revoke all on public.match_secrets from anon, authenticated;

-- Every move, in order. Only ever added to, and only by the referee.
create table public.moves (
  match_id uuid not null references public.matches (id) on delete cascade,
  seq int not null,
  move jsonb not null,
  at timestamptz not null default now(),
  primary key (match_id, seq)
);
alter table public.moves enable row level security;
revoke all on public.moves from anon, authenticated;

-- Both players hear "something changed" when their duel's row changes (Realtime, with the select policy above).
alter publication supabase_realtime add table public.matches;

-- ---------- the referee's database calls (service role only) ----------
-- A new challenge room. A host has one open room at a time: opening one closes the last.
create function public.duel_create(p_host uuid, p_code text, p_version text, p_entry jsonb)
returns uuid language plpgsql set search_path = '' as $$
declare v_id uuid;
begin
  update public.matches set status = 'cancelled', updated_at = now() where host = p_host and status = 'waiting';
  insert into public.matches (kind, code, host, version) values ('challenge', p_code, p_host, p_version) returning id into v_id;
  insert into public.match_secrets (match_id, host_entry) values (v_id, p_entry);
  return v_id;
end $$;

-- An open room by its code, with the host's name.
create function public.duel_find(p_code text)
returns jsonb language sql stable set search_path = '' as $$
  select jsonb_build_object('id', m.id, 'host', m.host, 'host_name', p.name, 'version', m.version, 'created_at', m.created_at)
  from public.matches m join public.profiles p on p.id = m.host
  where m.code = upper(p_code) and m.status = 'waiting'
$$;

-- Everything the referee needs about one duel, in one call.
create function public.duel_load(p_match uuid)
returns jsonb language sql stable set search_path = '' as $$
  select jsonb_build_object('match', to_jsonb(m), 'secret', to_jsonb(s),
    'moves', coalesce((select jsonb_agg(mv.move order by mv.seq) from public.moves mv where mv.match_id = m.id), '[]'::jsonb))
  from public.matches m join public.match_secrets s on s.match_id = m.id
  where m.id = p_match
$$;

-- The guest's room or duel in progress, newest first (to rejoin after closing the app).
create function public.duel_mine(p_user uuid)
returns jsonb language sql stable set search_path = '' as $$
  select coalesce(jsonb_agg(jsonb_build_object('id', id, 'status', status, 'code', code, 'host', host = p_user) order by created_at desc), '[]'::jsonb)
  from (select * from public.matches where (host = p_user or guest = p_user) and status in ('waiting', 'playing')
        order by created_at desc limit 5) m
$$;

-- The guest takes the seat and the duel starts. False if someone else got there first, or the room is gone.
create function public.duel_start(p_match uuid, p_guest uuid, p_entry jsonb, p_setup jsonb, p_seats uuid[], p_names text[],
  p_pending jsonb, p_deadline timestamptz)
returns boolean language plpgsql set search_path = '' as $$
begin
  update public.matches set guest = p_guest, seats = p_seats, names = p_names, status = 'playing', pending = p_pending,
    deadline = p_deadline, started_at = now(), updated_at = now()
  where id = p_match and status = 'waiting' and guest is null and host <> p_guest and created_at > now() - interval '1 day';
  if not found then return false; end if;
  update public.match_secrets set guest_entry = p_entry, setup = p_setup where match_id = p_match;
  update public.matches set status = 'cancelled', updated_at = now() where host = p_guest and status = 'waiting';
  return true;
end $$;

-- One more move. `p_seq` is how many moves the referee saw: if another request got in first, nothing changes.
create function public.duel_append(p_match uuid, p_seq int, p_move jsonb, p_pending jsonb, p_deadline timestamptz,
  p_strikes int[], p_result jsonb)
returns boolean language plpgsql set search_path = '' as $$
begin
  update public.matches set seq = p_seq + 1, pending = p_pending, deadline = p_deadline, strikes = p_strikes, result = p_result,
    status = case when p_result is null then 'playing' else 'done' end,
    ended_at = case when p_result is null then null else now() end, updated_at = now()
  where id = p_match and seq = p_seq and status = 'playing';
  if not found then return false; end if;
  insert into public.moves (match_id, seq, move) values (p_match, p_seq, p_move);
  return true;
end $$;

-- The host closes a room nobody has joined.
create function public.duel_cancel(p_match uuid, p_host uuid)
returns boolean language plpgsql set search_path = '' as $$
begin
  update public.matches set status = 'cancelled', updated_at = now() where id = p_match and host = p_host and status = 'waiting';
  return found;
end $$;

revoke execute on function public.duel_create, public.duel_find, public.duel_load, public.duel_mine, public.duel_start,
  public.duel_append, public.duel_cancel from public, anon, authenticated;
grant execute on function public.duel_create, public.duel_find, public.duel_load, public.duel_mine, public.duel_start,
  public.duel_append, public.duel_cancel to service_role;
