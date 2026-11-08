-- ============================================================================
-- NexusAI Cloud Database Schema & Row Level Security (RLS)
-- Target: Supabase PostgreSQL
-- ============================================================================

create extension if not exists "pgcrypto";
create extension if not exists "pg_trgm";

-- 1. PROFILES TABLE
create table if not exists public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    display_name text not null default '',
    avatar_url text,
    default_model text not null default '',
    fallback_model text,
    allow_fallback boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- 2. CONVERSATIONS TABLE
create table if not exists public.conversations (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    title text not null default 'New Conversation',
    selected_model text not null,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    archived_at timestamptz
);

create index if not exists idx_conversations_user_updated
    on public.conversations(user_id, updated_at desc)
    where archived_at is null;

create index if not exists idx_conversations_title_trgm
    on public.conversations using gin (title gin_trgm_ops);

-- 3. MESSAGES TABLE
create table if not exists public.messages (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references public.conversations(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    role text not null check (role in ('system', 'user', 'assistant')),
    content text not null default '',
    parts jsonb not null default '[]'::jsonb,
    model_id text not null,
    fallback_used boolean not null default false,
    requested_model_id text,
    status text not null default 'completed' check (status in ('sending', 'streaming', 'completed', 'interrupted', 'error')),
    error_code text,
    client_request_id text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_messages_client_request unique (conversation_id, client_request_id)
);

create index if not exists idx_messages_conversation_created
    on public.messages(conversation_id, created_at asc);

create index if not exists idx_messages_user_id
    on public.messages(user_id);

create index if not exists idx_messages_content_fts
    on public.messages using gin (to_tsvector('english', content));

-- 4. AUTOMATIC UPDATED_AT TRIGGERS
create or replace function public.handle_updated_at()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists trg_profiles_updated_at on public.profiles;
create trigger trg_profiles_updated_at
    before update on public.profiles
    for each row execute function public.handle_updated_at();

drop trigger if exists trg_conversations_updated_at on public.conversations;
create trigger trg_conversations_updated_at
    before update on public.conversations
    for each row execute function public.handle_updated_at();

drop trigger if exists trg_messages_updated_at on public.messages;
create trigger trg_messages_updated_at
    before update on public.messages
    for each row execute function public.handle_updated_at();

-- Touch parent conversation updated_at whenever a message is inserted or updated
create or replace function public.touch_conversation_updated_at()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    update public.conversations
       set updated_at = now()
     where id = new.conversation_id
       and user_id = new.user_id;
    return new;
end;
$$;

drop trigger if exists trg_touch_conversation on public.messages;
create trigger trg_touch_conversation
    after insert or update on public.messages
    for each row execute function public.touch_conversation_updated_at();

-- Auto-create profile when a new Supabase user signs up
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.profiles (id, display_name, avatar_url)
    values (
        new.id,
        coalesce(new.raw_user_meta_data->>'display_name', split_part(new.email, '@', 1), 'User'),
        new.raw_user_meta_data->>'avatar_url'
    )
    on conflict (id) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- 5. STRICT ROW LEVEL SECURITY (RLS)
alter table public.profiles enable row level security;
alter table public.conversations enable row level security;
alter table public.messages enable row level security;

alter table public.profiles force row level security;
alter table public.conversations force row level security;
alter table public.messages force row level security;

-- Profiles policies
drop policy if exists "profiles_select_own" on public.profiles;
create policy "profiles_select_own"
    on public.profiles for select
    to authenticated
    using (auth.uid() = id);

drop policy if exists "profiles_insert_own" on public.profiles;
create policy "profiles_insert_own"
    on public.profiles for insert
    to authenticated
    with check (auth.uid() = id);

drop policy if exists "profiles_update_own" on public.profiles;
create policy "profiles_update_own"
    on public.profiles for update
    to authenticated
    using (auth.uid() = id)
    with check (auth.uid() = id);

drop policy if exists "profiles_delete_own" on public.profiles;
create policy "profiles_delete_own"
    on public.profiles for delete
    to authenticated
    using (auth.uid() = id);

-- Conversations policies
drop policy if exists "conversations_select_own" on public.conversations;
create policy "conversations_select_own"
    on public.conversations for select
    to authenticated
    using (auth.uid() = user_id);

drop policy if exists "conversations_insert_own" on public.conversations;
create policy "conversations_insert_own"
    on public.conversations for insert
    to authenticated
    with check (auth.uid() = user_id);

drop policy if exists "conversations_update_own" on public.conversations;
create policy "conversations_update_own"
    on public.conversations for update
    to authenticated
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

drop policy if exists "conversations_delete_own" on public.conversations;
create policy "conversations_delete_own"
    on public.conversations for delete
    to authenticated
    using (auth.uid() = user_id);

-- Messages policies (also verifies parent conversation ownership)
drop policy if exists "messages_select_own" on public.messages;
create policy "messages_select_own"
    on public.messages for select
    to authenticated
    using (
        auth.uid() = user_id
        and exists (
            select 1 from public.conversations c
            where c.id = messages.conversation_id
              and c.user_id = auth.uid()
        )
    );

drop policy if exists "messages_insert_own" on public.messages;
create policy "messages_insert_own"
    on public.messages for insert
    to authenticated
    with check (
        auth.uid() = user_id
        and exists (
            select 1 from public.conversations c
            where c.id = conversation_id
              and c.user_id = auth.uid()
        )
    );

drop policy if exists "messages_update_own" on public.messages;
create policy "messages_update_own"
    on public.messages for update
    to authenticated
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

drop policy if exists "messages_delete_own" on public.messages;
create policy "messages_delete_own"
    on public.messages for delete
    to authenticated
    using (auth.uid() = user_id);

-- 6. FULL-TEXT & CASE-INSENSITIVE SEARCH RPC (Strictly scoped to auth.uid())
create or replace function public.search_user_chats(
    search_query text,
    page_limit int default 30,
    page_offset int default 0
)
returns table (
    conversation_id uuid,
    conversation_title text,
    selected_model text,
    snippet text,
    matched_message_id uuid,
    updated_at timestamptz
)
language plpgsql
security invoker
set search_path = public
as $$
declare
    trimmed_query text := trim(coalesce(search_query, ''));
begin
    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;

    if trimmed_query = '' then
        return query
        select
            c.id as conversation_id,
            c.title as conversation_title,
            c.selected_model,
            coalesce(
                (select left(m.content, 160)
                 from public.messages m
                 where m.conversation_id = c.id
                   and m.user_id = auth.uid()
                 order by m.created_at desc
                 limit 1),
                'No messages yet'
            ) as snippet,
            null::uuid as matched_message_id,
            c.updated_at
        from public.conversations c
        where c.user_id = auth.uid()
          and c.archived_at is null
        order by c.updated_at desc
        limit greatest(1, least(page_limit, 100))
        offset greatest(0, page_offset);
        return;
    end if;

    return query
    select distinct on (c.id, c.updated_at)
        c.id as conversation_id,
        c.title as conversation_title,
        c.selected_model,
        coalesce(
            left(m.content, 180),
            c.title
        ) as snippet,
        m.id as matched_message_id,
        c.updated_at
    from public.conversations c
    left join public.messages m
        on m.conversation_id = c.id
       and m.user_id = auth.uid()
       and (
           m.content ilike ('%' || trimmed_query || '%')
           or to_tsvector('english', m.content) @@ plainto_tsquery('english', trimmed_query)
       )
    where c.user_id = auth.uid()
      and c.archived_at is null
      and (
          c.title ilike ('%' || trimmed_query || '%')
          or m.id is not null
      )
    order by c.updated_at desc, c.id
    limit greatest(1, least(page_limit, 100))
    offset greatest(0, page_offset);
end;
$$;
