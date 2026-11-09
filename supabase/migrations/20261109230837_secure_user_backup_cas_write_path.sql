-- Phase 4.1: remove direct client DML and add the authenticated CAS RPC.
REVOKE ALL PRIVILEGES ON TABLE public.user_backups
  FROM anon, authenticated, PUBLIC;
GRANT SELECT ON TABLE public.user_backups TO authenticated;

CREATE OR REPLACE FUNCTION public.save_user_backup(
    p_expected_revision bigint,
    p_schema_version integer,
    p_payload text,
    p_checksum text
)
RETURNS TABLE(revision bigint)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
    v_owner_id uuid;
    v_revision bigint;
BEGIN
    v_owner_id := auth.uid();

    IF v_owner_id IS NULL THEN
        RAISE EXCEPTION 'AUTHENTICATION_REQUIRED'
            USING ERRCODE = '42501';
    END IF;

    IF p_expected_revision IS NULL
       OR p_expected_revision < 0
       OR p_schema_version IS NULL
       OR p_schema_version <= 0
       OR p_payload IS NULL
       OR pg_catalog.octet_length(p_payload) = 0
       OR pg_catalog.octet_length(p_payload) > 8388608
       OR pg_catalog.octet_length(p_payload) % 4 <> 0
       OR p_payload !~ '^[A-Za-z0-9+/]+={0,2}$'
       OR p_checksum IS NULL
       OR p_checksum !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'INVALID_BACKUP_PAYLOAD'
            USING ERRCODE = '22023';
    END IF;

    -- App payload format: Base64(GZIP(canonical UTF-8 JSON)).
    -- The database validates the Base64 envelope and checksum shape only.

    IF p_expected_revision = 0 THEN
        INSERT INTO public.user_backups AS current_backup
            (owner_id, revision, schema_version, payload, checksum, updated_at)
        VALUES
            (v_owner_id, 1, p_schema_version, p_payload, p_checksum, pg_catalog.now())
        ON CONFLICT (owner_id) DO UPDATE
           SET revision = current_backup.revision + 1,
               schema_version = EXCLUDED.schema_version,
               payload = EXCLUDED.payload,
               checksum = EXCLUDED.checksum,
               updated_at = pg_catalog.now()
         WHERE current_backup.revision = 0
        RETURNING public.user_backups.revision INTO v_revision;
    ELSE
        UPDATE public.user_backups AS current_backup
           SET revision = current_backup.revision + 1,
               schema_version = p_schema_version,
               payload = p_payload,
               checksum = p_checksum,
               updated_at = pg_catalog.now()
         WHERE current_backup.owner_id = v_owner_id
           AND current_backup.revision = p_expected_revision
        RETURNING current_backup.revision INTO v_revision;
    END IF;

    IF v_revision IS NULL THEN
        RAISE EXCEPTION 'BACKUP_REVISION_CONFLICT'
            USING ERRCODE = 'P0001';
    END IF;

    RETURN QUERY SELECT v_revision;
END;
$function$;

REVOKE EXECUTE ON FUNCTION public.save_user_backup(bigint, integer, text, text)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.save_user_backup(bigint, integer, text, text)
  TO authenticated;

COMMENT ON FUNCTION public.save_user_backup(bigint, integer, text, text) IS
  'Owner-scoped snapshot write with server-side revision CAS. Authenticated clients may SELECT their own snapshot but must use this RPC for writes.';
