-- Phase 4.2–4.4: fail-closed defaults and least-privilege public API surfaces.

-- New app-owned public relations/sequences must opt in to client access.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  REVOKE ALL ON TABLES FROM anon, authenticated;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  REVOKE ALL ON SEQUENCES FROM anon, authenticated;

-- Do not make new postgres-owned functions executable by PUBLIC by default.
-- Public-schema RPCs must explicitly grant EXECUTE only after a security review.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres
  REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  REVOKE EXECUTE ON FUNCTIONS FROM anon, authenticated;

-- daily_stats is a read-only aggregate model. SECURITY INVOKER preserves
-- sessions RLS for authenticated callers; no direct DML is required.
REVOKE ALL PRIVILEGES ON TABLE public.daily_stats
  FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.daily_stats TO authenticated;

COMMENT ON VIEW public.daily_stats IS
  'Read-only aggregate view. SECURITY INVOKER ensures underlying sessions RLS applies; authenticated users receive SELECT only.';

-- Trigger functions are internal implementation details, not client RPCs.
REVOKE EXECUTE ON FUNCTION public.delete_old_logs()
  FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.update_account_stats()
  FROM PUBLIC, anon, authenticated;

-- The Phase 4.1 RPC is the only authenticated write path for user_backups.
-- Remove now-unneeded policies so accidental future table grants do not reopen
-- direct writes around revision compare-and-swap.
DROP POLICY IF EXISTS authenticated_insert_own_backup ON public.user_backups;
DROP POLICY IF EXISTS authenticated_update_own_backup ON public.user_backups;
