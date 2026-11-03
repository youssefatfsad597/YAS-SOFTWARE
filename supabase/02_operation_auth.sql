-- =====================================================================================
-- 02_operation_auth.sql -- server-side authorization for every SENSITIVE OPERATION
-- (flash FRP, flash a partition, factory reset, change serial/CSC, ...), on top of the
-- account-level license check that supabase/schema.sql already does at login.
--
-- Run this AFTER schema.sql, once, in the Supabase SQL Editor. Safe to re-run later
-- (every object below uses "create or replace" / "if not exists").
--
-- WHY THIS EXISTS (read before running):
--   authorize_device() in schema.sql only runs once at login/app-open. Someone who
--   patches the APK to skip that one check would otherwise never be asked again until
--   they restart the app. This file adds a SECOND, independent check that the Android
--   app is expected to call right before each individual sensitive action, and that
--   check also hands back a piece of key material (via HMAC, using a secret that is
--   NEVER sent to the app except as this one-time derived fragment) which the app
--   combines with its own embedded secret to actually decrypt the bundled firmware
--   images. Without a fresh, valid grant from this function, the firmware files
--   decrypt to garbage -- so patching a boolean in the app no longer helps an attacker.
-- =====================================================================================

-- ---------------------------------------------------------------------------
-- 1) Server-only secret store. RLS is enabled with NO policies, so nobody using the
--    public anon/authenticated API (i.e. the Android app, the admin dashboard, or
--    any other client) can read or write this table -- only you, running SQL directly
--    in the Supabase SQL Editor (which connects as the table owner and bypasses RLS),
--    and the SECURITY DEFINER functions below (also owned by you) can touch it.
-- ---------------------------------------------------------------------------
create table if not exists public.yas_server_secrets (
  name       text primary key,
  secret_hex text not null,
  created_at timestamptz not null default now()
);
alter table public.yas_server_secrets enable row level security;
-- No grants to anon/authenticated on purpose -- see comment above.
revoke all on table public.yas_server_secrets from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- 2) Audit log of every authorize_operation() call (granted or denied), so an admin
--    can see exactly which account/device asked to flash what, and when.
-- ---------------------------------------------------------------------------
create table if not exists public.yas_operation_log (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid references auth.users(id) on delete set null,
  device_id  text,
  operation  text,
  granted    boolean not null,
  status     text,
  created_at timestamptz not null default now()
);
alter table public.yas_operation_log enable row level security;
revoke all on table public.yas_operation_log from public, anon, authenticated;

drop policy if exists yas_operation_log_admin_select on public.yas_operation_log;
create policy yas_operation_log_admin_select on public.yas_operation_log
  for select to authenticated
  using (exists(select 1 from public.yas_profiles where user_id = auth.uid() and role = 'admin'));
grant select on table public.yas_operation_log to authenticated; -- RLS policy above still restricts rows to admins

-- ---------------------------------------------------------------------------
-- 3) Kill-switch flag on the profile. admin_force_logout() sets it; the Android app's
--    background KillSwitchWorker, authorize_device(), and authorize_operation() all
--    check it and deny/clear the local session when it is set.
-- ---------------------------------------------------------------------------
alter table public.yas_profiles add column if not exists force_logout_at timestamptz;

-- ---------------------------------------------------------------------------
-- 3b) yas_auto_ban_device: INTERNAL ONLY (no grants below -- callable only from other
--    SECURITY DEFINER functions owned by you, never directly by the app). Shared by
--    authorize_operation, authorize_firmware_download and report_tamper so a device
--    caught tampering (root/Frida/Xposed/emulator/bad signature/debugger -- see
--    TamperGuard.kt) is banned the SAME way admin_ban_device already bans one by hand:
--    inserted into yas_banned_devices (idempotent) and its yas_devices row revoked, so
--    it shows up in both the web dashboard's and the app's "الأجهزة المحظورة" list
--    immediately, with the reason explaining it was automatic.
-- ---------------------------------------------------------------------------
create or replace function public.yas_auto_ban_device(p_user_id uuid, p_device_id text, p_android_id text, p_reason text)
returns void language plpgsql security definer set search_path = '' as $$
declare
  v_dev public.yas_devices%rowtype;
  v_email text;
  v_label text;
  v_aid text := nullif(trim(coalesce(p_android_id, '')), '');
  v_did text := nullif(trim(coalesce(p_device_id, '')), '');
begin
  if v_did is null and v_aid is null then return; end if;
  if v_did is not null then
    select * into v_dev from public.yas_devices where user_id = p_user_id and device_id = v_did;
  end if;
  select email into v_email from public.yas_profiles where user_id = p_user_id;
  v_label := left(coalesce(v_dev.label, 'جهاز') || coalesce(' — ' || v_email, ''), 160);

  if not exists (
    select 1 from public.yas_banned_devices b where
      (v_did is not null and b.device_id = v_did) or (v_aid is not null and b.android_id = v_aid)
  ) then
    insert into public.yas_banned_devices(device_id, android_id, label, last_ip, reason, banned_by)
    values (v_did, v_aid, v_label, coalesce(v_dev.last_ip, public.yas_client_ip()),
      left('حظر تلقائي - رصد تلاعب: ' || coalesce(nullif(trim(p_reason), ''), 'غير محدد'), 200), null);
  end if;

  if v_dev.id is not null then
    update public.yas_devices set revoked_at = coalesce(revoked_at, now()) where id = v_dev.id;
  end if;
end; $$;
-- Intentionally no grants to anon/authenticated -- only this file's own SECURITY
-- DEFINER functions (which run as the owner) may call it.

-- ---------------------------------------------------------------------------
-- 4) authorize_operation: called by the app right before ONE sensitive action.
--    Re-checks everything authorize_device() checks (login, status, expiry, ban,
--    kill-switch, that this exact device is the account's currently-registered one)
--    PLUS issues short-lived (60s) key material via HMAC-SHA256, so a captured
--    request can't be replayed later and a grant for one operation can't be reused
--    for another (the operation name and device id are mixed into the HMAC input).
-- ---------------------------------------------------------------------------
drop function if exists public.authorize_operation(text, text, text);

create or replace function public.authorize_operation(p_device_id text, p_operation text, p_android_id text default null, p_tamper_reason text default null)
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
    perform public.yas_auto_ban_device(v_uid, p_device_id, v_aid, v_tamper);
    insert into public.yas_operation_log(user_id, device_id, operation, granted, status)
      values (v_uid, p_device_id, v_op, false, 'tamper_auto_banned');
    return jsonb_build_object('granted', false, 'status', 'tamper_auto_banned',
      'message', 'تم رصد محاولة تلاعب بالتطبيق وحظر هذا الجهاز تلقائيًا.');
  end if;

  if public.yas_is_banned(p_device_id, v_aid) then
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

  update public.yas_devices set last_seen_at = now() where id = v_dev.id;
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

revoke all on function public.authorize_operation(text, text, text, text) from public, anon;
grant execute on function public.authorize_operation(text, text, text, text) to authenticated;

-- ---------------------------------------------------------------------------
-- 4b) report_tamper: lets the app self-report tampering at moments OTHER than a
--    sensitive operation -- specifically the app-open/login tamper check in
--    AuthGate.kt, which today only sets a local boolean and never tells the server.
--    Requires an existing session (so it can only ever ban a device already tied to
--    a real account -- an anonymous caller cannot ban arbitrary device ids). Same
--    effect as a tamper_reason passed to authorize_operation: immediate, permanent
--    ban via yas_auto_ban_device, visible in the admin dashboard/app right away.
-- ---------------------------------------------------------------------------
create or replace function public.report_tamper(p_device_id text, p_android_id text default null, p_reason text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  v_aid text := left(nullif(trim(coalesce(p_android_id, '')), ''), 64);
  v_reason text := left(nullif(trim(coalesce(p_reason, '')), ''), 200);
begin
  if v_uid is null then
    return jsonb_build_object('ok', false, 'status', 'not_authenticated');
  end if;
  if p_device_id is null or length(p_device_id) < 8 or length(p_device_id) > 128 then
    return jsonb_build_object('ok', false, 'status', 'invalid_device');
  end if;
  perform public.yas_auto_ban_device(v_uid, p_device_id, v_aid, coalesce(v_reason, 'غير محدد'));
  insert into public.yas_operation_log(user_id, device_id, operation, granted, status)
    values (v_uid, p_device_id, 'tamper_report', false, 'tamper_auto_banned');
  return jsonb_build_object('ok', true, 'status', 'tamper_auto_banned');
end; $$;

revoke all on function public.report_tamper(text, text, text) from public, anon;
grant execute on function public.report_tamper(text, text, text) to authenticated;

-- ---------------------------------------------------------------------------
-- 5) Make authorize_device() (the login-time check in schema.sql) also honor the
--    kill-switch, so a forced logout is caught the moment the app re-checks the
--    license (every few minutes while open), not only on the next sensitive action.
--    This REPLACES the function from schema.sql with the same body plus one check.
-- ---------------------------------------------------------------------------
create or replace function public.authorize_device(p_device_id text, p_device_name text default null, p_android_id text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  p public.yas_profiles%rowtype;
  v_existing public.yas_devices%rowtype;
  v_other public.yas_devices%rowtype;
  v_status text;
  v_wait_seconds integer;
  v_cooldown_seconds constant integer := 4 * 3600;
  v_ip text := public.yas_client_ip();
  v_name text := left(nullif(trim(coalesce(p_device_name,'')),''), 80);
  v_aid text := left(nullif(trim(coalesce(p_android_id,'')),''), 64);
begin
  if v_uid is null then raise exception 'يجب تسجيل الدخول.'; end if;
  if p_device_id is null or length(p_device_id) < 8 or length(p_device_id) > 128 then raise exception 'معرّف الجهاز غير صالح.'; end if;
  if public.yas_is_banned(p_device_id, v_aid) then
    update public.yas_devices set revoked_at=coalesce(revoked_at,now()), last_ip=coalesce(v_ip,last_ip),
      android_id=coalesce(v_aid,android_id)
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
      update public.yas_devices set last_ip = coalesce(v_ip,last_ip), label = coalesce(v_name,label), android_id = coalesce(v_aid,android_id) where id=v_existing.id;
      return jsonb_build_object('active',false,'status','device_revoked','expires_at',p.expires_at,'message','تم إلغاء تسجيل هذا الجهاز. تواصل مع الإدارة.');
    end if;
    update public.yas_devices set last_seen_at=now(), last_ip = coalesce(v_ip,last_ip), label = coalesce(v_name,label), android_id = coalesce(v_aid,android_id) where id=v_existing.id;
    return jsonb_build_object('active',true,'status','active','expires_at',p.expires_at,'message','الترخيص ساري.');
  end if;

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
    update public.yas_devices set revoked_at = now() where id = v_other.id;
  end if;

  if v_existing.id is not null then
    update public.yas_devices set deleted_at=null, revoked_at=null, first_seen_at=now(), last_seen_at=now(),
      label=coalesce(v_name,label), first_ip=v_ip, last_ip=v_ip, android_id=v_aid where id=v_existing.id;
  else
    insert into public.yas_devices(user_id,device_id,label,first_ip,last_ip,android_id)
    values(v_uid,p_device_id,coalesce(v_name,'Android installation'),v_ip,v_ip,v_aid);
  end if;
  return jsonb_build_object('active',true,'status','active','expires_at',p.expires_at,'message','تم تسجيل الدخول من جهاز جديد.');
end; $$;

revoke all on function public.authorize_device(text,text,text) from public, anon;
grant execute on function public.authorize_device(text,text,text) to authenticated;

-- ---------------------------------------------------------------------------
-- 6) admin_force_logout: the "قطع فوري" button in the admin dashboard. Sets the
--    kill-switch flag; the account stays locked out of every operation (and the next
--    authorize_device/authorize_operation call denies it) until an admin reactivates
--    it normally (admin_set_license below clears the flag whenever it's called, so
--    the dashboard needs no separate "unlock" button).
-- ---------------------------------------------------------------------------
create or replace function public.admin_force_logout(p_user_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  update public.yas_profiles set force_logout_at = now() where user_id = p_user_id;
  if not found then raise exception 'المستخدم غير موجود.'; end if;
  return jsonb_build_object('ok', true);
end; $$;

revoke all on function public.admin_force_logout(uuid) from public, anon;
grant execute on function public.admin_force_logout(uuid) to authenticated;

-- admin_set_license (from schema.sql) now also clears the kill-switch, so reactivating
-- or editing an account's license from the dashboard is all it takes to let the force-
-- logged-out device/account back in -- no separate "unlock" control needed.
create or replace function public.admin_set_license(p_user_id uuid, p_status text, p_expires_at timestamptz)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  if p_status not in ('pending','active','suspended') then raise exception 'حالة غير صالحة.'; end if;
  if p_status='active' and (p_expires_at is null or p_expires_at <= now()) then raise exception 'يجب تحديد تاريخ انتهاء مستقبلي للتفعيل.'; end if;
  update public.yas_profiles set status=p_status, expires_at=p_expires_at, force_logout_at=null where user_id=p_user_id;
  if not found then raise exception 'الحساب غير موجود.'; end if;
  return jsonb_build_object('ok',true);
end; $$;

-- =====================================================================================
-- 7) ONE-TIME MANUAL STEP (do this yourself, right here in the SQL Editor, AFTER the
--    block above has run -- never from the Android app or the admin dashboard):
--
--      insert into public.yas_server_secrets (name, secret_hex)
--      values ('operation_hmac_key', '<paste the 64 hex chars from: openssl rand -hex 32>')
--      on conflict (name) do update set secret_hex = excluded.secret_hex;
--
--    Until this row exists, authorize_operation() will keep returning
--    status = 'server_not_configured' and every sensitive action in the app will be
--    denied (fails closed, by design).
-- =====================================================================================
