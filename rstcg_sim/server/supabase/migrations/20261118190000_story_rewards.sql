-- Story Mode (2026-11-18, STORY_BRIEF.md): which roads of the story have given a player their card. Each road's
-- card is given once ever, logged in bank_log as ref 'story:<chapter>:<road>' (kind 'story' when the server gave
-- it, 'story-kept' when the player's own progress already held it as their bank opened). A reset or a replace
-- keeps those cards: the `duel` function asks this for the refs and knows each one's card (web/packs.js).
create function public.bank_story_refs(p_user uuid) returns jsonb
language sql stable set search_path = '' as $$
  select coalesce(jsonb_agg(l.ref order by l.ref), '[]'::jsonb)
  from public.bank_log l
  where l.user_id = p_user and l.kind in ('story', 'story-kept') and l.ref like 'story:%'
$$;
revoke execute on function public.bank_story_refs(uuid) from public, anon, authenticated;
grant execute on function public.bank_story_refs(uuid) to service_role;
