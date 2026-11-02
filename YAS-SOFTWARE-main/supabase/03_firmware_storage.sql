-- =====================================================================================
-- 03_firmware_storage.sql -- server-hosted firmware files (replaces/extends the
-- build-time-embedded assets in app/src/main/assets/x216b/).
--
-- Run this AFTER schema.sql and 02_operation_auth.sql, once, in the Supabase SQL
-- Editor. Safe to re-run later (every object below uses "create or replace" /
-- "if not exists").
--
-- WHY THIS EXISTS:
--   Files bundled inside the APK (EmbeddedFirmware.kt) are encrypted ONCE at build
--   time with a key bound to KEY_MATERIAL + the signing certificate ONLY -- there is
--   no server involved in producing that ciphertext, so it cannot also depend on a
--   server-issued value. That's fine for files you ship inside the app, but it means
--   those files decrypt for ANYONE holding a correctly-signed APK, licensed or not,
--   the moment they reach that code path (TamperGuard/SensitiveOperationGate still
--   gate the UI action that calls it, but the UI is exactly the layer a patched APK
--   bypasses).
--
--   Server-hosted files close that gap: the ciphertext sits in a private Storage
--   bucket, and the per-file decryption secret (key_material_hex below) is released
--   ONLY by authorize_firmware_download(), which re-checks the full license/device/
--   ban/kill-switch state on every single call -- the same way authorize_operation()
--   already does for sensitive actions. A suspended or expired account simply never
--   receives the key, regardless of what the installed APK has been patched to do
--   locally.
--
--   The released key_material_hex is combined on-device with the app's own embedded
--   secrets through the SAME native derivation chain already built for embedded
--   assets (NativeCore.deriveFirmwareKeyNative + deriveBoundKeyNative in
--   app/src/main/cpp/yas_native.c) -- no new client-side crypto code needed. See
--   tools/upload_firmware.py for the matching server-side encryption step, which
--   must reproduce that exact derivation to produce ciphertext the app can open.
-- =====================================================================================

-- ---------------------------------------------------------------------------
-- 1) Catalog + per-file secret. RLS enabled with NO select/insert/update/delete
--    policies for anon/authenticated -- same pattern as yas_server_secrets in
--    02_operation_auth.sql. Only you via the SQL Editor (as table owner) and the
--    SECURITY DEFINER functions below can read key_material_hex.
-- ---------------------------------------------------------------------------
create table if not exists public.yas_firmware_files (
  id              uuid primary key default gen_random_uuid(),
  slug            text not null unique,          -- e.g. 'x216b/sec_efs.bin' -- what the app asks for
  display_name    text not null,
  device_model    text,                           -- free text, shown in the app's picker; not enforced server-side
  storage_path    text not null,                  -- path inside the 'yas-firmware' bucket, e.g. 'x216b/sec_efs.bin.enc'
  key_material_hex text not null,                 -- fed into NativeCore.deriveFirmwareKeyNative -- NEVER sent except via authorize_firmware_download
  sha256_plaintext text not null,                 -- hex, verified by the app after decrypting -- catches a corrupted/wrong-key download
  size_bytes      bigint not null,
  active          boolean not null default true,
  created_at      timestamptz not null default now()
);
alter table public.yas_firmware_files enable row level security;
revoke all on table public.yas_firmware_files from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- 2) Catalog listing: safe to expose to any signed-in user with an active license --
--    it never includes key_material_hex. The app uses this to populate a file picker
--    before the user picks a specific action.
-- ---------------------------------------------------------------------------
create or replace function public.firmware_catalog()
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null then
    return jsonb_build_object('items', '[]'::jsonb, 'status', 'not_authenticated');
  end if;
  if not exists(
    select 1 from public.yas_profiles
    where user_id = v_uid and status = 'active' and expires_at is not null and expires_at > now()
      and force_logout_at is null
  ) then
    return jsonb_build_object('items', '[]'::jsonb, 'status', 'inactive');
  end if;
  return jsonb_build_object(
    'status', 'ok',
    'items', coalesce((
      select jsonb_agg(jsonb_build_object(
        'slug', f.slug, 'display_name', f.display_name, 'device_model', f.device_model,
        'size_bytes', f.size_bytes
      ) order by f.device_model, f.display_name)
      from public.yas_firmware_files f where f.active
    ), '[]'::jsonb)
  );
end; $$;

revoke all on function public.firmware_catalog() from public, anon;
grant execute on function public.firmware_catalog() to authenticated;

-- ---------------------------------------------------------------------------
-- 3) authorize_firmware_download: the real gate. Mirrors authorize_operation()'s
--    checks (banned / status / expiry / kill-switch / device must be THIS account's
--    currently-registered device) exactly, then releases storage_path +
--    key_material_hex + sha256_plaintext for ONE named file. Every call is logged to
--    yas_operation_log the same way a sensitive operation is.
-- ---------------------------------------------------------------------------
create or replace function public.authorize_firmware_download(p_device_id text, p_slug text, p_android_id text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  p public.yas_profiles%rowtype;
  v_dev public.yas_devices%rowtype;
  f public.yas_firmware_files%rowtype;
  v_aid text := left(nullif(trim(coalesce(p_android_id, '')), ''), 64);
  v_slug text := left(nullif(trim(coalesce(p_slug, '')), ''), 200);
  v_status text := 'denied';
begin
  if v_uid is null then
    return jsonb_build_object('granted', false, 'status', 'not_authenticated');
  end if;
  if p_device_id is null or length(p_device_id) < 8 or length(p_device_id) > 128 then
    return jsonb_build_object('granted', false, 'status', 'invalid_device');
  end if;
  if v_slug is null then
    return jsonb_build_object('granted', false, 'status', 'invalid_file');
  end if;

  if public.yas_is_banned(p_device_id, v_aid) then
    insert into public.yas_operation_log(user_id, device_id, operation, granted, status)
      values (v_uid, p_device_id, 'download:' || v_slug, false, 'device_banned');
    return jsonb_build_object('granted', false, 'status', 'device_banned');
  end if;

  select * into p from public.yas_profiles where user_id = v_uid;
  if not found then
    v_status := 'pending';
  elsif p.force_logout_at is not null then
    v_status := 'force_logout';
  elsif p.status <> 'active' then
    v_status := p.status;
  elsif p.expires_at is null or p.expires_at <= now() then
    v_status := 'expired';
  else
    v_status := 'active';
  end if;

  if v_status <> 'active' then
    insert into public.yas_operation_log(user_id, device_id, operation, granted, status)
      values (v_uid, p_device_id, 'download:' || v_slug, false, v_status);
    return jsonb_build_object('granted', false, 'status', v_status);
  end if;

  select * into v_dev from public.yas_devices
    where user_id = v_uid and device_id = p_device_id and deleted_at is null;
  if not found or v_dev.revoked_at is not null then
    insert into public.yas_operation_log(user_id, device_id, operation, granted, status)
      values (v_uid, p_device_id, 'download:' || v_slug, false, 'device_not_registered');
    return jsonb_build_object('granted', false, 'status', 'device_not_registered');
  end if;

  select * into f from public.yas_firmware_files where slug = v_slug and active;
  if not found then
    insert into public.yas_operation_log(user_id, device_id, operation, granted, status)
      values (v_uid, p_device_id, 'download:' || v_slug, false, 'file_not_found');
    return jsonb_build_object('granted', false, 'status', 'file_not_found');
  end if;

  update public.yas_devices set last_seen_at = now() where id = v_dev.id;
  insert into public.yas_operation_log(user_id, device_id, operation, granted, status)
    values (v_uid, p_device_id, 'download:' || v_slug, true, 'granted');

  return jsonb_build_object(
    'granted', true,
    'status', 'granted',
    'storage_path', f.storage_path,
    'key_material_hex', f.key_material_hex,
    'sha256_plaintext', f.sha256_plaintext,
    'size_bytes', f.size_bytes
  );
end; $$;

revoke all on function public.authorize_firmware_download(text, text, text) from public, anon;
grant execute on function public.authorize_firmware_download(text, text, text) to authenticated;

-- ---------------------------------------------------------------------------
-- 4) admin_upsert_firmware_file: used by tools/upload_firmware.py (service_role key,
--    run by you locally -- never from the Android app or the admin dashboard's
--    anon/authenticated session) to register/update a catalog row after uploading
--    the encrypted object to Storage. Also callable by an admin's authenticated
--    session for convenience, in case you ever want an admin-dashboard uploader.
-- ---------------------------------------------------------------------------
create or replace function public.admin_upsert_firmware_file(
  p_slug text, p_display_name text, p_device_model text, p_storage_path text,
  p_key_material_hex text, p_sha256_plaintext text, p_size_bytes bigint
) returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is not null and not exists(
    select 1 from public.yas_profiles where user_id = auth.uid() and role = 'admin'
  ) then
    raise exception 'غير مصرح: صلاحية المدير مطلوبة.';
  end if;
  insert into public.yas_firmware_files(slug, display_name, device_model, storage_path, key_material_hex, sha256_plaintext, size_bytes)
  values (p_slug, p_display_name, p_device_model, p_storage_path, p_key_material_hex, p_sha256_plaintext, p_size_bytes)
  on conflict (slug) do update set
    display_name = excluded.display_name, device_model = excluded.device_model,
    storage_path = excluded.storage_path, key_material_hex = excluded.key_material_hex,
    sha256_plaintext = excluded.sha256_plaintext, size_bytes = excluded.size_bytes, active = true;
  return jsonb_build_object('ok', true);
end; $$;

revoke all on function public.admin_upsert_firmware_file(text,text,text,text,text,text,bigint) from public, anon;
grant execute on function public.admin_upsert_firmware_file(text,text,text,text,text,text,bigint) to authenticated;

-- admin_set_firmware_active: pull a file from the catalog (e.g. a bad upload)
-- without deleting its row/history.
create or replace function public.admin_set_firmware_active(p_slug text, p_active boolean)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.yas_profiles where user_id = auth.uid() and role = 'admin') then
    raise exception 'غير مصرح: صلاحية المدير مطلوبة.';
  end if;
  update public.yas_firmware_files set active = p_active where slug = p_slug;
  if not found then raise exception 'الملف غير موجود.'; end if;
  return jsonb_build_object('ok', true);
end; $$;

revoke all on function public.admin_set_firmware_active(text, boolean) from public, anon;
grant execute on function public.admin_set_firmware_active(text, boolean) to authenticated;

-- =====================================================================================
-- 5) ONE-TIME MANUAL STEPS (do these yourself in the Supabase dashboard / SQL Editor):
--
--    a) Storage -> Create bucket -> name EXACTLY "yas-firmware" -> Public = OFF.
--
--    b) Storage -> Policies -> yas-firmware -> New policy -> start from scratch:
--       Policy name: "active license can read"
--       Allowed operation: SELECT
--       Target roles: authenticated
--       USING expression:
--         exists(
--           select 1 from public.yas_profiles p
--           where p.user_id = auth.uid() and p.status = 'active'
--             and p.expires_at is not null and p.expires_at > now()
--             and p.force_logout_at is null
--         )
--       This is DEFENSE IN DEPTH, not the real gate: it stops a suspended/expired
--       account from even fetching the ciphertext object, on top of
--       authorize_firmware_download() withholding the decryption key. Downloading the
--       ciphertext alone is useless without the key, so this policy existing or not
--       does not change the actual security property -- it just saves bandwidth/avoids
--       handing ciphertext to accounts that have no business touching it at all.
--
--    c) Uploads happen ONLY via tools/upload_firmware.py using the service_role key
--       (bypasses Storage RLS entirely, same as the service_role already bypasses
--       every RLS policy in this project). Never add an INSERT/UPDATE storage policy
--       for anon/authenticated -- there is no legitimate client-side upload path.
-- =====================================================================================
