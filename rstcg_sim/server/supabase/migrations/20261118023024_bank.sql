-- Phase 3b: the server is the bank.
--
-- Every player who has been online (a guest or an account) has one row in `bank`: their GP, the cards they own
-- (basic materials are unlimited and never listed) and how many packs they have opened. Devices show these
-- numbers but can't set them. What changes them, and how (Ramon, 2026-11-17):
--   bot duels       the device reports each one (`bot:<seed>`), and the server believes it: 100 GP for a win,
--                   50 for a loss or a draw. Each duel once, at most 200 a day.
--   online duels    paid here, by duel_append, the moment the referee records the result: 100 for the winner,
--                   50 for the loser (or each, for a draw), nothing for conceding or running out of time.
--   packs           200 GP, and the server picks the cards (the `duel` function rolls them; web/packs.js).
--                   The tutorial's pack is free, once.
--   opening         the first time: the account's cloud save if it has one, else what the device reports if
--                   honest play could have made it (web/packs.js, plausible), else the three starter decks.
--   replacing       "which progress do you keep?" -> this device's: the same check as opening.
--   reset           back to the starter decks and 0 GP.
-- `bank_log` keeps one line per change, keyed by its `ref`, so a change sent twice (a retry after the network
-- dropped) happens once. Only the `duel` function (the secret key) calls these; players may read their own row.

create table public.bank (
  user_id uuid primary key references auth.users (id) on delete cascade,
  gp integer not null default 0,
  coll jsonb not null default '{}'::jsonb,       -- { card name: copies }
  packs integer not null default 0,
  free_pack boolean not null default false,      -- the tutorial's free pack has been given
  rev bigint not null default 1,                 -- goes up by one with every change
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint bank_gp check (gp >= 0),
  constraint bank_packs check (packs >= 0),
  constraint bank_coll_is_object check (jsonb_typeof(coll) = 'object')
);
comment on table public.bank is 'The bank: each player''s GP, cards and packs. Changed only by the duel function.';

create table public.bank_log (
  user_id uuid not null references auth.users (id) on delete cascade,
  ref text not null,                             -- bot:<seed>, online:<match>, pack:<nonce>, free, open, replace:<n>, reset:<n>
  kind text not null,
  gp integer not null default 0,
  cards jsonb,                                   -- the cards a pack gave
  at timestamptz not null default now(),
  primary key (user_id, ref)
);
comment on table public.bank_log is 'Every change to the bank, once each (by ref).';
create index bank_log_kind_at on public.bank_log (user_id, kind, at);

alter table public.bank enable row level security;
alter table public.bank_log enable row level security;
create policy "Players read their own bank" on public.bank for select to authenticated using (user_id = (select auth.uid()));
revoke all on public.bank, public.bank_log from anon, authenticated;
grant select on public.bank to authenticated;

-- a player's bank as the function hands it on
create function public.bank_row(v public.bank) returns jsonb
language sql immutable set search_path = '' as $$
  select jsonb_build_object('gp', v.gp, 'coll', v.coll, 'packs', v.packs, 'free', v.free_pack, 'rev', v.rev, 'at', v.updated_at)
$$;

-- card counts added together ({ a: 1 } + { a: 2, b: 1 } = { a: 3, b: 1 })
create function public.bank_add(a jsonb, b jsonb) returns jsonb
language sql immutable set search_path = '' as $$
  select coalesce(jsonb_object_agg(k, n), '{}'::jsonb) from (
    select key as k, sum(value::int) as n from (
      select * from jsonb_each_text(coalesce(a, '{}'::jsonb)) union all select * from jsonb_each_text(coalesce(b, '{}'::jsonb))
    ) x group by key having sum(value::int) > 0
  ) y
$$;

create function public.bank_get(p_user uuid) returns jsonb
language sql stable set search_path = '' as $$
  select public.bank_row(b) from public.bank b where b.user_id = p_user
$$;

-- The account's cloud save, for opening its bank (null when there is none).
create function public.bank_progress(p_user uuid) returns jsonb
language sql stable set search_path = '' as $$
  select p.data from public.progress p where p.user_id = p_user
$$;

-- Open a bank (if there isn't one yet) with these numbers. -> the bank, whichever opened it.
create function public.bank_open(p_user uuid, p_gp int, p_coll jsonb, p_packs int, p_free boolean, p_how text)
returns jsonb language plpgsql set search_path = '' as $$
declare v public.bank%rowtype;
begin
  insert into public.bank (user_id, gp, coll, packs, free_pack) values (p_user, p_gp, p_coll, p_packs, p_free)
    on conflict (user_id) do nothing returning * into v;
  if found then
    insert into public.bank_log (user_id, ref, kind, gp) values (p_user, 'open', p_how, p_gp) on conflict do nothing;
  else
    select * into v from public.bank where user_id = p_user;
  end if;
  return public.bank_row(v);
end $$;

-- One change: p_gp more (or less) GP, these cards added, p_packs more packs, the free pack used.
--   -> { ok: true, bank, cards }            done (or done before, with this ref: the same answer again)
--   -> { ok: false, why: 'nobank' | 'short' | 'free' | 'cap', bank }
create function public.bank_apply(p_user uuid, p_ref text, p_kind text, p_gp int, p_cards jsonb, p_packs int, p_free boolean)
returns jsonb language plpgsql set search_path = '' as $$
declare v public.bank%rowtype; l public.bank_log%rowtype;
begin
  select * into v from public.bank where user_id = p_user for update;
  if not found then return jsonb_build_object('ok', false, 'why', 'nobank'); end if;
  select * into l from public.bank_log where user_id = p_user and ref = p_ref;
  if found then return jsonb_build_object('ok', true, 'again', true, 'bank', public.bank_row(v), 'cards', l.cards); end if;
  if v.gp + p_gp < 0 then return jsonb_build_object('ok', false, 'why', 'short', 'bank', public.bank_row(v)); end if;
  if p_free and v.free_pack then return jsonb_build_object('ok', false, 'why', 'free', 'bank', public.bank_row(v)); end if;
  if p_kind = 'bot' and (select count(*) from public.bank_log where user_id = p_user and kind = 'bot' and at > now() - interval '1 day') >= 200 then
    return jsonb_build_object('ok', false, 'why', 'cap', 'bank', public.bank_row(v));
  end if;
  update public.bank set gp = gp + p_gp, coll = public.bank_add(coll, p_cards), packs = packs + p_packs,
    free_pack = free_pack or p_free, rev = rev + 1, updated_at = now()
    where user_id = p_user returning * into v;
  insert into public.bank_log (user_id, ref, kind, gp, cards) values (p_user, p_ref, p_kind, p_gp, p_cards);
  return jsonb_build_object('ok', true, 'bank', public.bank_row(v), 'cards', p_cards);
end $$;

-- Replace the whole bank (reset, or "keep this device's progress"). -> the bank
create function public.bank_set(p_user uuid, p_ref text, p_kind text, p_gp int, p_coll jsonb, p_packs int, p_free boolean)
returns jsonb language plpgsql set search_path = '' as $$
declare v public.bank%rowtype;
begin
  insert into public.bank (user_id, gp, coll, packs, free_pack) values (p_user, p_gp, p_coll, p_packs, p_free)
    on conflict (user_id) do update set gp = excluded.gp, coll = excluded.coll, packs = excluded.packs, free_pack = excluded.free_pack,
      rev = public.bank.rev + 1, updated_at = now()
    returning * into v;
  insert into public.bank_log (user_id, ref, kind, gp) values (p_user, p_ref, p_kind, p_gp) on conflict do nothing;
  return public.bank_row(v);
end $$;

-- An online duel has ended: pay both players (once each, by ref online:<match>).
create function public.bank_pay_duel(p_match uuid, p_seats uuid[], p_result jsonb)
returns void language plpgsql set search_path = '' as $$
declare i int; w int := (p_result ->> 'winner')::int; quit boolean := (p_result ->> 'reason') in ('concede', 'time'); pay int;
begin
  for i in 0..1 loop
    pay := case when w is null then 50 when w = i then 100 when quit then 0 else 50 end;
    if pay > 0 and p_seats[i + 1] is not null then
      perform public.bank_apply(p_seats[i + 1], 'online:' || p_match, 'online', pay, null, 0, false);
    end if;
  end loop;
end $$;

create or replace function public.duel_append(p_match uuid, p_seq int, p_move jsonb, p_pending jsonb, p_deadline timestamptz,
  p_strikes int[], p_result jsonb)
returns boolean language plpgsql set search_path = '' as $$
declare v_seats uuid[];
begin
  update public.matches set seq = p_seq + 1, pending = p_pending, deadline = p_deadline, strikes = p_strikes, result = p_result,
    status = case when p_result is null then 'playing' else 'done' end,
    ended_at = case when p_result is null then null else now() end, updated_at = now()
  where id = p_match and seq = p_seq and status = 'playing'
  returning seats into v_seats;
  if not found then return false; end if;
  insert into public.moves (match_id, seq, move) values (p_match, p_seq, p_move);
  if p_result is not null then perform public.bank_pay_duel(p_match, v_seats, p_result); end if;
  return true;
end $$;

revoke execute on function public.bank_row, public.bank_add, public.bank_get, public.bank_progress, public.bank_open,
  public.bank_apply, public.bank_set, public.bank_pay_duel from public, anon, authenticated;
grant execute on function public.bank_row, public.bank_add, public.bank_get, public.bank_progress, public.bank_open,
  public.bank_apply, public.bank_set, public.bank_pay_duel to service_role;
