-- Online play, phase 2: rematches.
--
-- After a duel either player can offer a rematch. The referee opens a room for it (as for a challenge) and
-- points the finished duel at that room (`next`). The other player sees the row change, and taking up the
-- offer joins the room. Only one offer stands at a time, and a room that closes takes its pointer with it.
-- Applied to the Supabase project "RuneScape TCG" (udmaqoxyhiwpvxkrugwr) as migration 20261116035711 online_rematch.

alter table public.matches add column next uuid references public.matches (id) on delete set null;

-- A room (or the duel it became) by its id, with the host's name.
create function public.duel_room(p_match uuid)
returns jsonb language sql stable set search_path = '' as $$
  select jsonb_build_object('id', m.id, 'host', m.host, 'guest', m.guest, 'status', m.status, 'host_name', p.name,
    'version', m.version, 'created_at', m.created_at)
  from public.matches m join public.profiles p on p.id = m.host
  where m.id = p_match
$$;

-- Point a finished duel at the room for its rematch. False if another offer still stands (an open room
-- on this version, less than a day old) or a rematch is already under way.
create function public.duel_next(p_match uuid, p_user uuid, p_next uuid, p_version text)
returns boolean language plpgsql set search_path = '' as $$
begin
  update public.matches m set next = p_next, updated_at = now()
  where m.id = p_match and m.status = 'done' and (m.host = p_user or m.guest = p_user)
    and (m.next is null or exists (select 1 from public.matches r where r.id = m.next
      and (r.status = 'cancelled' or (r.status = 'waiting' and (r.version <> p_version or r.created_at < now() - interval '1 day')))));
  return found;
end $$;

-- Closing rooms also withdraws the rematch offers that pointed at them.
create or replace function public.duel_create(p_host uuid, p_code text, p_version text, p_entry jsonb)
returns uuid language plpgsql set search_path = '' as $$
declare v_id uuid;
begin
  with gone as (update public.matches set status = 'cancelled', updated_at = now() where host = p_host and status = 'waiting' returning id)
  update public.matches set next = null, updated_at = now() where next in (select id from gone);
  insert into public.matches (kind, code, host, version) values ('challenge', p_code, p_host, p_version) returning id into v_id;
  insert into public.match_secrets (match_id, host_entry) values (v_id, p_entry);
  return v_id;
end $$;

create or replace function public.duel_start(p_match uuid, p_guest uuid, p_entry jsonb, p_setup jsonb, p_seats uuid[], p_names text[],
  p_pending jsonb, p_deadline timestamptz)
returns boolean language plpgsql set search_path = '' as $$
begin
  update public.matches set guest = p_guest, seats = p_seats, names = p_names, status = 'playing', pending = p_pending,
    deadline = p_deadline, started_at = now(), updated_at = now()
  where id = p_match and status = 'waiting' and guest is null and host <> p_guest and created_at > now() - interval '1 day';
  if not found then return false; end if;
  update public.match_secrets set guest_entry = p_entry, setup = p_setup where match_id = p_match;
  with gone as (update public.matches set status = 'cancelled', updated_at = now() where host = p_guest and status = 'waiting' returning id)
  update public.matches set next = null, updated_at = now() where next in (select id from gone);
  return true;
end $$;

create or replace function public.duel_cancel(p_match uuid, p_host uuid)
returns boolean language plpgsql set search_path = '' as $$
begin
  update public.matches set status = 'cancelled', updated_at = now() where id = p_match and host = p_host and status = 'waiting';
  if not found then return false; end if;
  update public.matches set next = null, updated_at = now() where next = p_match;
  return true;
end $$;

revoke execute on function public.duel_room, public.duel_next from public, anon, authenticated;
grant execute on function public.duel_room, public.duel_next to service_role;
