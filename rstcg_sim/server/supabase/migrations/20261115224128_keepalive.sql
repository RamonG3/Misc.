-- A one-row table the weekly keep-alive reads, so the free project sees database activity and isn't paused.
create table public.keepalive (id int primary key, note text not null);
insert into public.keepalive values (1, 'Read weekly by the keep-alive ping. Safe to leave alone.');
alter table public.keepalive enable row level security;
create policy "anyone can read the keepalive row" on public.keepalive for select to anon, authenticated using (true);
