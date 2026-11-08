-- ============================================================================
-- Smarter AI 1.0 — Personal Intelligence Engine & Privacy-Conscious Memory Schema
-- Tables: user_memory, project_context, conversation_summaries
-- Enforces strict Row-Level Security (RLS) scoped to auth.uid() = user_id
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.user_memory (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    category TEXT NOT NULL DEFAULT 'USER_PREFERENCE',
    memory_key TEXT NOT NULL,
    content TEXT NOT NULL,
    source_type TEXT NOT NULL DEFAULT 'EXPLICIT_USER',
    confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    conversation_id UUID REFERENCES public.conversations(id) ON DELETE SET NULL,
    is_user_approved BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_memory_key UNIQUE (user_id, category, memory_key)
);

CREATE INDEX IF NOT EXISTS idx_user_memory_user_updated
    ON public.user_memory(user_id, updated_at DESC);

ALTER TABLE public.user_memory ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can read own memories" ON public.user_memory;
CREATE POLICY "Users can read own memories"
    ON public.user_memory FOR SELECT
    USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can insert own memories" ON public.user_memory;
CREATE POLICY "Users can insert own memories"
    ON public.user_memory FOR INSERT
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can update own memories" ON public.user_memory;
CREATE POLICY "Users can update own memories"
    ON public.user_memory FOR UPDATE
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can delete own memories" ON public.user_memory;
CREATE POLICY "Users can delete own memories"
    ON public.user_memory FOR DELETE
    USING (auth.uid() = user_id);

-- ============================================================================
-- Project Context Memory Table
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.project_context (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    project_name TEXT NOT NULL,
    summary TEXT NOT NULL,
    tech_stack TEXT NOT NULL DEFAULT '',
    recent_decisions TEXT NOT NULL DEFAULT '',
    open_tasks TEXT NOT NULL DEFAULT '',
    last_conversation_id UUID REFERENCES public.conversations(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_project_name UNIQUE (user_id, project_name)
);

CREATE INDEX IF NOT EXISTS idx_project_context_user_updated
    ON public.project_context(user_id, updated_at DESC);

ALTER TABLE public.project_context ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can manage own project contexts" ON public.project_context;
CREATE POLICY "Users can manage own project contexts"
    ON public.project_context FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- ============================================================================
-- Conversation Summaries Table for Cross-Session Semantic Retrieval
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.conversation_summaries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    summary_text TEXT NOT NULL,
    key_topics TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_conversation_summary UNIQUE (user_id, conversation_id)
);

CREATE INDEX IF NOT EXISTS idx_conversation_summaries_user_updated
    ON public.conversation_summaries(user_id, updated_at DESC);

ALTER TABLE public.conversation_summaries ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can manage own conversation summaries" ON public.conversation_summaries;
CREATE POLICY "Users can manage own conversation summaries"
    ON public.conversation_summaries FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);
