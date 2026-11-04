-- =====================================================================================
-- 05_unlimited_devices.sql -- lets specific accounts (admin/test accounts, VIP customers,
-- whoever you choose) sign in from more than one device at a time, instead of the normal
-- "one device, 4h cooldown to switch" policy enforced in authorize_device().
--
-- Run this AFTER 02_operation_auth.sql, once, in the Supabase SQL Editor. Safe to re-run
-- later (uses "add column if not exists" / "create or replace").
-- =====================================================================================

-- ---------------------------------------------------------------------------
-- 1) The flag itself. Defaults to false for every existing and future account, so
--    nothing changes unless you explicitly flip it for someone (step 3 below, or
--    manually/from an admin UI you add later).
-- ---------------------------------------------------------------------------
alter table public.yas_profiles add column if not exists unlimited_devices boolean not null default false;

-- ---------------------------------------------------------------------------
-- 2) authorize_device(): identical to the version in 02_operation_auth.sql, with ONE
--    change -- the "revoke whichever other device was active, or make them wait 4h"
--    block is skipped entirely when p.unlimited_devices is true. Everything else
--    (ban check, kill-switch, license status/expiry, per-device revoke/delete checks)
--    is untouched, so a device that's individually banned or revoked for this account
--    is still blocked exactly as before -- this only lifts the ONE-DEVICE-AT-A-TIME
--    limit, not the license or ban checks.
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

  -- *** THE ONLY CHANGE vs. 02_operation_auth.sql: this whole block (single-device
  -- cooldown / auto-revoke of the previous device) is skipped for unlimited_devices
  -- accounts, so registering a new device never touches any of their other devices. ***
  if not p.unlimited_devices then
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
-- 3) ONE-TIME MANUAL STEP: flip the flag for youssefatfsad597@gmail.com. Re-run this
--    any time for any other account -- it's idempotent and safe to repeat.
-- ---------------------------------------------------------------------------
update public.yas_profiles
set unlimited_devices = true
where user_id = (select id from auth.users where lower(email) = lower('youssefatfsad597@gmail.com'));

-- If the update above affected 0 rows, that account hasn't signed up / has no row in
-- yas_profiles yet -- sign in with it once first (so authorize_device/signUp creates its
-- profile row), then re-run just the update statement above.
