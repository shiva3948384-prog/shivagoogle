-- ============================================================================
-- Smarter AI — Server-Side Email OTP Verification Schema & RLS
-- ============================================================================
-- Stores salted SHA-256 hashes of 6-digit numeric verification codes.
-- Plaintext OTP codes are NEVER stored in the database.
-- Row Level Security is enabled and forced with zero client policies so only
-- the server-side Edge Function (using service_role) can access this table.
-- ============================================================================

create table if not exists public.email_verification_otps (
    id uuid primary key default gen_random_uuid(),
    email text not null,
    user_id uuid references auth.users(id) on delete cascade,
    otp_hash text not null,
    salt text not null,
    attempts int not null default 0 check (attempts >= 0),
    max_attempts int not null default 5 check (max_attempts > 0),
    expires_at timestamptz not null,
    resend_available_at timestamptz not null,
    used_at timestamptz,
    invalidated boolean not null default false,
    requester_ip text,
    created_at timestamptz not null default now()
);

create index if not exists idx_email_verification_otps_email_created
    on public.email_verification_otps (lower(email), created_at desc);

create index if not exists idx_email_verification_otps_user_id
    on public.email_verification_otps (user_id);

-- Strict Row Level Security: block all direct anon/authenticated PostgREST access.
-- Only the backend Edge Function using SUPABASE_SERVICE_ROLE_KEY can read or write.
alter table public.email_verification_otps enable row level security;
alter table public.email_verification_otps force row level security;

drop policy if exists "deny_all_client_access_otps" on public.email_verification_otps;
create policy "deny_all_client_access_otps"
    on public.email_verification_otps
    for all
    to anon, authenticated
    using (false)
    with check (false);

-- Helper function to purge expired/used OTP rows older than 24 hours
create or replace function public.cleanup_expired_email_otps()
returns void
language sql
security definer
set search_path = public
as $$
    delete from public.email_verification_otps
     where created_at < now() - interval '24 hours'
        or (invalidated = true and created_at < now() - interval '1 hour');
$$;
