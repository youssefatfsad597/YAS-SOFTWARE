-- =====================================================================================
-- 04_device_fingerprint.sql -- adds a THIRD device identifier (Widevine DRM "device
-- unique id") to the ban system, on top of the app-generated installation id
-- (device_id) and ANDROID_ID already used by schema.sql / 02_operation_auth.sql /
-- 03_firmware_storage.sql.
--
-- WHY:
--   device_id   lives in the app's own encrypted storage -> wiped by a plain uninstall.
--   android_id  survives an uninstall but is wiped by a factory reset (and can be
--               edited directly on a rooted device).
--   widevine_id comes from the DRM subsystem's hardware-backed provisioning (the
--               TrustZone/keybox on most OEM devices) instead of the userdata
--               partition, so on most real phones/tablets it survives BOTH an
--               uninstall/reinstall AND a factory reset -- the two things someone
--               trying to dodge a ban would try first. The app hashes it with
--               SHA-256 before sending it (see LicenseService.kt's widevineId()), so
--               no raw DRM provisioning data ever leaves the device, only an opaque
--               fingerprint.
--
-- HONESTY NOTE -- read this before treating a ban as unconditionally permanent:
--   Nothing a client app reports about itself is unspoofable by someone with root and
--   enough patience. On some devices the Widevine keybox CAN be reset (reflashing a
--   stock ROM, or re-provisioning on some Treble devices), so this raises the bar
--   substantially without making a ban mathematically absolute. That is exactly why
--   this file adds widevine_id as one MORE signal that must ALSO be reset to dodge the
--   ban, on top of device_id and android_id, rather than replacing them -- and why
--   TamperGuard's root/Frida/Xposed/emulator detection (yas_auto_ban_device below)
--   still matters as a separate layer: it catches the tooling someone would need to
--   even attempt resetting these identifiers in the first place.
--   IP address was deliberately never part of the match (see yas_is_banned) and still
--   isn't: an IP changes on its own with no action by the device at all (new Wi-Fi,
--   mobile data, a VPN), so matching on it would both fail to catch anyone determined
--   and risk banning an unrelated, innocent person who is later assigned the same
--   address by their ISP.
--
-- BACKWARD COMPATIBILITY: every new parameter below defaults to null, and every call
-- site in this project is updated alongside it (LicenseService.kt's identityBody(),
-- OperationAuthClient, RemoteFirmwareClient). An app build from before this migration
-- simply never sends p_widevine_id and everything keeps working exactly as before --
-- it just doesn't get the extra protection until the app is rebuilt and reinstalled.
--
-- Run this AFTER schema.sql, 02_operation_auth.sql and 03_firmware_storage.sql, once,
-- in the Supabase SQL Editor. Safe to re-run later (every object below uses
-- "create or replace" / "if not exists", matching the rest of this project).
-- =====================================================================================

alter table public.yas_devices add column if not exists widevine_id text;
alter table public.yas_banned_devices add column if not exists widevine_id text;

-- ---------------------------------------------------------------------------
-- 1) yas_is_banned / is_device_banned: now match on ANY of the three identifiers.
--    Explicitly dropped first (not just "create or replace") because the parameter
--    count is growing from 2 to 3 -- same reasoning as schema.sql's own header, which
--    always drops the old overload first so PostgREST never sees two candidates for
--    the same call.
-- ---------------------------------------------------------------------------
drop function if exists public.yas_is_banned(text,text);
drop function if exists public.is_device_banned(text,text);

create or replace function public.yas_is_banned(p_device_id text, p_android_id text, p_widevine_id text default null)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists(select 1 from public.yas_banned_devices b where
    (nullif(p_device_id,'') is not null and b.device_id = p_device_id)
    or (nullif(p_android_id,'') is not null and b.android_id = p_android_id)
    or (nullif(p_widevine_id,'') is not null and b.widevine_id = p_widevine_id));
$$;

-- Called by the app BEFORE the login screen (anon key), so it only returns a yes/no.
create or replace function public.is_device_banned(p_device_id text default null, p_android_id text default null, p_widevine_id text default null)
returns jsonb language sql stable security definer set search_path = '' as $$
  select jsonb_build_object('banned', public.yas_is_banned(p_device_id, p_android_id, p_widevine_id));
$$;

-- ---------------------------------------------------------------------------
-- 2) yas_auto_ban_device (internal-only, called from authorize_operation,
--    authorize_firmware_download and report_tamper when TamperGuard fires): now also
--    records and matches widevine_id. Dropped first for the same arity reason as above.
-- ---------------------------------------------------------------------------
drop function if exists public.yas_auto_ban_device(uuid,text,text,text);

create or replace function public.yas_auto_ban_device(p_user_id uuid, p_device_id text, p_android_id text, p_reason text, p_widevine_id text default null)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_dev public.yas_devices%rowtype;
  v_email text;
  v_label text;
  v_aid text := nullif(trim(coalesce(p_android_id, '')), '');
  v_did text := nullif(trim(coalesce(p_device_id, '')), '');
  v_wid text := nullif(trim(coalesce(p_widevine_id, '')), '');
begin
  if v_did is null and v_aid is null and v_wid is null then return; end if;
  if v_did is not null then
    select * into v_dev from public.yas_devices where user_id = p_user_id and device_id = v_did;
  end if;
  select email into v_email from public.yas_profiles where user_id = p_user_id;
  v_label := left(coalesce(v_dev.label, 'جهاز') || coalesce(' — ' || v_email, ''), 160);

  if not exists (
    select 1 from public.yas_banned_devices b where
      (v_did is not null and b.device_id = v_did) or (v_aid is not null and b.android_id = v_aid)
      or (v_wid is not null and b.widevine_id = v_wid)
  ) then
    insert into public.yas_banned_devices(device_id, android_id, widevine_id, label, last_ip, reason, banned_by)
    values (v_did, v_aid, coalesce(v_wid, v_dev.widevine_id), v_label, coalesce(v_dev.last_ip, public.yas_client_ip()),
      left('حظر تلقائي - رصد تلاعب: ' || coalesce(nullif(trim(p_reason), ''), 'غير محدد'), 200), null);
  end if;

  if v_dev.id is not null then
    update public.yas_devices set revoked_at = coalesce(revoked_at, now()), widevine_id = coalesce(v_wid, widevine_id) where id = v_dev.id;
  end if;
end; $$;
-- Intentionally no grants to anon/authenticated -- only this file's own SECURITY
-- DEFINER functions (which run as the owner) may call it.

-- ---------------------------------------------------------------------------
-- 3) authorize_device: the final version (schema.sql's version, then overridden by
--    02_operation_auth.sql's kill-switch version) now also stores/matches widevine_id.
--    This REPLACES the 3-argument version with the same body plus the new identifier.
-- ---------------------------------------------------------------------------
drop function if exists public.authorize_device(text,text,text);

create or replace function public.authorize_device(p_device_id text, p_device_name text default null, p_android_id text default null, p_widevine_id text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  p public.yas_profiles%rowtype;
  v_existing public.yas_devices%rowtype;
  v_other public.yas_devices%rowtype;
  v_status text;
  v_wait_seconds integer;
  v_cooldown_seconds constant integer := 4 * 3600; -- 4 hours between sessions on different devices
  v_ip text := public.yas_client_ip();
  v_name text := left(nullif(trim(coalesce(p_device_name,'')),''), 80);
  v_aid text := left(nullif(trim(coalesce(p_android_id,'')),''), 64);
  v_wid text := left(nullif(trim(coalesce(p_widevine_id,'')),''), 64);
begin
  if v_uid is null then raise exception 'يجب تسجيل الدخول.'; end if;
  if p_device_id is null or length(p_device_id) < 8 or length(p_device_id) > 128 then raise exception 'معرّف الجهاز غير صالح.'; end if;
  if public.yas_is_banned(p_device_id, v_aid, v_wid) then
    update public.yas_devices set revoked_at=coalesce(revoked_at,now()), last_ip=coalesce(v_ip,last_ip),
      android_id=coalesce(v_aid,android_id), widevine_id=coalesce(v_wid,widevine_id)
      where user_id=v_uid and device_id=p_device_id;
    return jsonb_build_object('active',false,'status','device_banned','message','هذا الجهاز محظور ولا يمكنه استخدام البرنامج.');
  end if;
  select * into p from public.yas_profiles where user_id = v_uid;
  if not found then return jsonb_build_object('active',false,'status','pending','message','الحساب غير مسجل في نظام التراخيص.'); end if;

  -- Kill-switch: an admin pressed "قطع فوري" since this device last checked in.
  if p.force_logout_at is not null then
    return jsonb_build_object('active',false,'status','force_logout','message','تم إنهاء الجلسة من لوحة الإدارة. سجّل الدخول من جديد بعد إعادة تفعيل الحساب.');
  end if;

  v_status := p.status;
  if p.status = 'active' and (p.expires_at is null or p.expires_at <= now()) then v_status := 'expired'; end if;
  if v_status <> 'active' then
    return jsonb_build_object('active',false,'status',v_status,'expires_at',p.expires_at,'message',case when v_status='pending' then 'الحساب بانتظار التفعيل من الإدارة.' when v_status='suspended' then 'تم إيقاف هذا الحساب من الإدارة.' else 'انتهت مدة التفعيل أو لم يتم تحديد تاريخ الانتهاء.' end);
  end if;

  select * into v_existing from public.yas_devices where user_id=v_uid and device_id=p_device_id;

  if v_existing.id is not null and v_existing.deleted_at is null then
    if v_existing.revoked_at is not null then
      -- Cancelled by the admin: stays blocked (still visible in the dashboard) until restored.
      update public.yas_devices set last_ip = coalesce(v_ip,last_ip), label = coalesce(v_name,label), android_id = coalesce(v_aid,android_id), widevine_id = coalesce(v_wid,widevine_id) where id=v_existing.id;
      return jsonb_build_object('active',false,'status','device_revoked','expires_at',p.expires_at,'message','تم إلغاء تسجيل هذا الجهاز. تواصل مع الإدارة.');
    end if;
    -- Same device continuing its session: no cool-down applies.
    update public.yas_devices set last_seen_at=now(), last_ip = coalesce(v_ip,last_ip), label = coalesce(v_name,label), android_id = coalesce(v_aid,android_id), widevine_id = coalesce(v_wid,widevine_id) where id=v_existing.id;
    return jsonb_build_object('active',true,'status','active','expires_at',p.expires_at,'message','الترخيص ساري.');
  end if;

  -- Unknown device, or one the admin deleted earlier (treated as a brand-new device).
  select * into v_other from public.yas_devices where user_id=v_uid and revoked_at is null and deleted_at is null order by last_seen_at desc limit 1;
  if v_other.id is not null then
    v_wait_seconds := v_cooldown_seconds - extract(epoch from (now() - v_other.last_seen_at))::integer;
    if v_wait_seconds > 0 then
      return jsonb_build_object(
        'active',false,'status','device_cooldown','expires_at',p.expires_at,'wait_seconds',v_wait_seconds,
        'message','لا يُسمح إلا بجهاز واحد في المرة الواحدة. يمكنك الدخول من جهاز آخر بعد ' ||
          case when v_wait_seconds >= 3600
            then (v_wait_seconds/3600)::text || ' ساعة و' || ((v_wait_seconds%3600)/60)::text || ' دقيقة'
            else (greatest(v_wait_seconds,60)/60)::text || ' دقيقة'
          end || '.'
      );
    end if;
    -- Cool-down elapsed: release the old device and hand the session to this one.
    update public.yas_devices set revoked_at = now() where id = v_other.id;
  end if;

  if v_existing.id is not null then
    update public.yas_devices set deleted_at=null, revoked_at=null, first_seen_at=now(), last_seen_at=now(),
      label=coalesce(v_name,label), first_ip=v_ip, last_ip=v_ip, android_id=v_aid, widevine_id=v_wid where id=v_existing.id;
  else
    insert into public.yas_devices(user_id,device_id,label,first_ip,last_ip,android_id,widevine_id)
    values(v_uid,p_device_id,coalesce(v_name,'Android installation'),v_ip,v_ip,v_aid,v_wid);
  end if;
  return jsonb_build_object('active',true,'status','active','expires_at',p.expires_at,'message','تم تسجيل الدخول من جهاز جديد.');
end; $$;

revoke all on function public.authorize_device(text,text,text,text) from public, anon;
grant execute on function public.authorize_device(text,text,text,text) to authenticated;

-- ---------------------------------------------------------------------------
-- 4) authorize_operation: adds p_widevine_id, used both for the ban check and
--    (on a tamper report) for what gets recorded in the ban row.
-- ---------------------------------------------------------------------------
drop function if exists public.authorize_operation(text, text, text, text);

create or replace function public.authorize_operation(p_device_id text, p_operation text, p_android_id text default null, p_tamper_reason text default null, p_widevine_id text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  p public.yas_profiles%rowtype;
  v_dev public.yas_devices%rowtype;
  v_secret bytea;
  v_nonce bytea;
  v_nonce_hex text;
  v_material_hex text;
  v_now bigint := extract(epoch from now())::bigint;
  v_ttl constant integer := 60;
  v_op text := left(nullif(trim(coalesce(p_operation, '')), ''), 64);
  v_aid text := left(nullif(trim(coalesce(p_android_id, '')), ''), 64);
  v_wid text := left(nullif(trim(coalesce(p_widevine_id, '')), ''), 64);
  v_tamper text := nullif(trim(coalesce(p_tamper_reason, '')), '');
  v_status text := 'denied';
begin
  if v_uid is null then
    return jsonb_build_object('granted', false, 'status', 'not_authenticated');
  end if;
  if p_device_id is null or length(p_device_id) < 8 or length(p_device_id) > 128 then
    return jsonb_build_object('granted', false, 'status', 'invalid_device');
  end if;
  if v_op is null then
    return jsonb_build_object('granted', false, 'status', 'invalid_operation');
  end if;

  -- Tamper signal reported BY THE CLIENT (root/Frida/Xposed/emulator/debugger -- see
  -- TamperGuard.kt). Banned immediately, before any other check, and the operation is
  -- denied in this same call -- no second round trip, no window to still get a key.
  if v_tamper is not null then
    perform public.yas_auto_ban_device(v_uid, p_device_id, v_aid, v_tamper, v_wid);
    insert into public.yas_operation_log(user_id, device_id, operation, granted, status)
      values (v_uid, p_device_id, v_op, false, 'tamper_auto_banned');
    return jsonb_build_object('granted', false, 'status', 'tamper_auto_banned',
      'message', 'تم رصد محاولة تلاعب بالتطبيق وحظر هذا الجهاز تلقائيًا.');
  end if;

  if public.yas_is_banned(p_device_id, v_aid, v_wid) then
    insert into public.yas_operation_log(user_id, device_id, operation, granted, status)
      values (v_uid, p_device_id, v_op, false, 'device_banned');
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
      values (v_uid, p_device_id, v_op, false, v_status);
    return jsonb_build_object('granted', false, 'status', v_status);
  end if;

  select * into v_dev from public.yas_devices
    where user_id = v_uid and device_id = p_device_id and deleted_at is null;
  if not found or v_dev.revoked_at is not null then
    insert into public.yas_operation_log(user_id, device_id, operation, granted, status)
      values (v_uid, p_device_id, v_op, false, 'device_not_registered');
    return jsonb_build_object('granted', false, 'status', 'device_not_registered');
  end if;

  select decode(secret_hex, 'hex') into v_secret
    from public.yas_server_secrets where name = 'operation_hmac_key';
  if v_secret is null then
    insert into public.yas_operation_log(user_id, device_id, operation, granted, status)
      values (v_uid, p_device_id, v_op, false, 'server_not_configured');
    return jsonb_build_object('granted', false, 'status', 'server_not_configured');
  end if;

  v_nonce := extensions.gen_random_bytes(16);
  v_nonce_hex := encode(v_nonce, 'hex');
  v_material_hex := encode(
    extensions.hmac(
      convert_to(v_op || '|' || p_device_id || '|' || v_nonce_hex || '|' || v_now::text, 'UTF8'),
      v_secret, 'sha256'
    ),
    'hex'
  );

  update public.yas_devices set last_seen_at = now(), widevine_id = coalesce(v_wid, widevine_id) where id = v_dev.id;
  insert into public.yas_operation_log(user_id, device_id, operation, granted, status)
    values (v_uid, p_device_id, v_op, true, 'granted');

  return jsonb_build_object(
    'granted', true,
    'status', 'granted',
    'issued_at', v_now,
    'ttl_seconds', v_ttl,
    'nonce', v_nonce_hex,
    'key_material', v_material_hex
  );
end; $$;

revoke all on function public.authorize_operation(text, text, text, text, text) from public, anon;
grant execute on function public.authorize_operation(text, text, text, text, text) to authenticated;

-- ---------------------------------------------------------------------------
-- 5) report_tamper: adds p_widevine_id so a self-reported tamper event bans it too.
-- ---------------------------------------------------------------------------
drop function if exists public.report_tamper(text, text, text);

create or replace function public.report_tamper(p_device_id text, p_android_id text default null, p_reason text default null, p_widevine_id text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  v_aid text := left(nullif(trim(coalesce(p_android_id, '')), ''), 64);
  v_wid text := left(nullif(trim(coalesce(p_widevine_id, '')), ''), 64);
  v_reason text := left(nullif(trim(coalesce(p_reason, '')), ''), 200);
begin
  if v_uid is null then
    return jsonb_build_object('ok', false, 'status', 'not_authenticated');
  end if;
  if p_device_id is null or length(p_device_id) < 8 or length(p_device_id) > 128 then
    return jsonb_build_object('ok', false, 'status', 'invalid_device');
  end if;
  perform public.yas_auto_ban_device(v_uid, p_device_id, v_aid, coalesce(v_reason, 'غير محدد'), v_wid);
  insert into public.yas_operation_log(user_id, device_id, operation, granted, status)
    values (v_uid, p_device_id, 'tamper_report', false, 'tamper_auto_banned');
  return jsonb_build_object('ok', true, 'status', 'tamper_auto_banned');
end; $$;

revoke all on function public.report_tamper(text, text, text, text) from public, anon;
grant execute on function public.report_tamper(text, text, text, text) to authenticated;

-- ---------------------------------------------------------------------------
-- 6) authorize_firmware_download: same treatment, mirrors authorize_operation.
-- ---------------------------------------------------------------------------
drop function if exists public.authorize_firmware_download(text, text, text, text);

create or replace function public.authorize_firmware_download(p_device_id text, p_slug text, p_android_id text default null, p_tamper_reason text default null, p_widevine_id text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  p public.yas_profiles%rowtype;
  v_dev public.yas_devices%rowtype;
  f public.yas_firmware_files%rowtype;
  v_aid text := left(nullif(trim(coalesce(p_android_id, '')), ''), 64);
  v_wid text := left(nullif(trim(coalesce(p_widevine_id, '')), ''), 64);
  v_slug text := left(nullif(trim(coalesce(p_slug, '')), ''), 200);
  v_tamper text := nullif(trim(coalesce(p_tamper_reason, '')), '');
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

  -- Same tamper short-circuit as authorize_operation (see 02_operation_auth.sql) --
  -- banned immediately, no firmware key released this call or any future one.
  if v_tamper is not null then
    perform public.yas_auto_ban_device(v_uid, p_device_id, v_aid, v_tamper, v_wid);
    insert into public.yas_operation_log(user_id, device_id, operation, granted, status)
      values (v_uid, p_device_id, 'download:' || v_slug, false, 'tamper_auto_banned');
    return jsonb_build_object('granted', false, 'status', 'tamper_auto_banned');
  end if;

  if public.yas_is_banned(p_device_id, v_aid, v_wid) then
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

  update public.yas_devices set last_seen_at = now(), widevine_id = coalesce(v_wid, widevine_id) where id = v_dev.id;
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

revoke all on function public.authorize_firmware_download(text, text, text, text, text) from public, anon;
grant execute on function public.authorize_firmware_download(text, text, text, text, text) to authenticated;

-- ---------------------------------------------------------------------------
-- 7) admin_ban_device: now carries the device row's widevine_id into the ban too
--    (same signature as before -- just "create or replace", no drop needed).
-- ---------------------------------------------------------------------------
create or replace function public.admin_ban_device(p_device_row_id uuid, p_reason text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_row public.yas_devices%rowtype; v_email text;
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  select * into v_row from public.yas_devices where id=p_device_row_id;
  if not found then raise exception 'الجهاز غير موجود.'; end if;
  select email into v_email from public.yas_profiles where user_id=v_row.user_id;
  if not exists(select 1 from public.yas_banned_devices where device_id=v_row.device_id) then
    insert into public.yas_banned_devices(device_id,android_id,widevine_id,label,last_ip,reason,banned_by)
    values(v_row.device_id,v_row.android_id,v_row.widevine_id,
      left(coalesce(v_row.label,'جهاز') || coalesce(' — ' || v_email,''),160),v_row.last_ip,left(nullif(trim(coalesce(p_reason,'')),''),200),auth.uid());
  end if;
  update public.yas_devices set revoked_at=coalesce(revoked_at,now()) where id=v_row.id;
  return jsonb_build_object('ok',true);
end; $$;

-- ---------------------------------------------------------------------------
-- 8) admin_ban_manual: can now ban by Android ID and/or Widevine ID directly (for a
--    device that never signed in, or an identifier obtained from another source).
--    Dropped first: p_android_id changes from required to optional.
-- ---------------------------------------------------------------------------
drop function if exists public.admin_ban_manual(text, text);

create or replace function public.admin_ban_manual(p_android_id text default null, p_reason text default null, p_widevine_id text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_aid text := left(nullif(trim(coalesce(p_android_id,'')),''),64);
  v_wid text := left(nullif(trim(coalesce(p_widevine_id,'')),''),64);
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  if v_aid is null and v_wid is null then raise exception 'أدخل Android ID أو Widevine ID على الأقل.'; end if;
  insert into public.yas_banned_devices(android_id,widevine_id,label,reason,banned_by)
  values(v_aid,v_wid,'إضافة يدوية',left(nullif(trim(coalesce(p_reason,'')),''),200),auth.uid());
  return jsonb_build_object('ok',true);
end; $$;

revoke all on function public.admin_ban_manual(text, text, text) from public, anon;
grant execute on function public.admin_ban_manual(text, text, text) to authenticated;

-- ---------------------------------------------------------------------------
-- 9) admin_list_bans / admin_list_users: expose widevine_id to the dashboard.
--    Same signatures as before -- just "create or replace".
-- ---------------------------------------------------------------------------
create or replace function public.admin_list_bans()
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  return coalesce((select jsonb_agg(jsonb_build_object('id',b.id,'device_id',b.device_id,'android_id',b.android_id,'widevine_id',b.widevine_id,
    'label',b.label,'last_ip',b.last_ip,'reason',b.reason,'banned_at',b.banned_at) order by b.banned_at desc) from public.yas_banned_devices b),'[]'::jsonb);
end; $$;

create or replace function public.admin_list_users()
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  return coalesce((select jsonb_agg(jsonb_build_object(
    'user_id',p.user_id,'email',p.email,'role',p.role,'status',case when p.status='active' and (p.expires_at is null or p.expires_at<=now()) then 'expired' else p.status end,
    'expires_at',p.expires_at,'created_at',p.created_at,
    'devices',(select coalesce(jsonb_agg(jsonb_build_object('id',d.id,'device_id',d.device_id,'label',d.label,'first_seen_at',d.first_seen_at,'last_seen_at',d.last_seen_at,'revoked_at',d.revoked_at,'deleted_at',d.deleted_at,'first_ip',d.first_ip,'last_ip',d.last_ip,'android_id',d.android_id,'widevine_id',d.widevine_id) order by (d.deleted_at is not null), d.last_seen_at desc),'[]'::jsonb) from public.yas_devices d where d.user_id=p.user_id)
  ) order by p.created_at desc) from public.yas_profiles p),'[]'::jsonb);
end; $$;

-- ---------------------------------------------------------------------------
-- 10) Final grants for the signatures that changed above.
-- ---------------------------------------------------------------------------
revoke all on function public.yas_is_banned(text,text,text) from public, anon, authenticated;
-- The only function the signed-out app may call: yes/no ban check before the login screen.
revoke all on function public.is_device_banned(text,text,text) from public;
grant execute on function public.is_device_banned(text,text,text) to anon, authenticated;
