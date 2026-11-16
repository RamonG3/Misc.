-- Cloud save, a follow-up: guests can't read the progress table either.
--
-- A guest can never have a row there (the insert rule refuses them), so this changes nothing a player can
-- see. It says so in the rules themselves, which is also what Supabase's security advisor looks for.

alter policy "Players read their own progress" on public.progress rename to "Accounts read their own progress";

alter policy "Accounts read their own progress" on public.progress
  using (user_id = (select auth.uid()) and (select (auth.jwt() ->> 'is_anonymous')::boolean) is false);

alter policy "Accounts save their own progress" on public.progress
  using (user_id = (select auth.uid()) and (select (auth.jwt() ->> 'is_anonymous')::boolean) is false)
  with check (user_id = (select auth.uid()) and (select (auth.jwt() ->> 'is_anonymous')::boolean) is false);
