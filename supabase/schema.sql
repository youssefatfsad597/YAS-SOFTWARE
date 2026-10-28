-- YAS SOFTWARE licensing schema for Supabase.
-- Run in Supabase SQL Editor as project owner. Never put service_role key in the app or browser.
create extension if not exists pgcrypto with schema extensions;

-- One-time migration for projects created before the single-device policy:
-- drop the old per-account device_limit column and the old 4-argument
-- admin_set_license overload (its signature changed when device_limit was removed).
alter table if exists public.yas_profiles drop column if exists device_limit;
drop function if exists public.admin_set_license(uuid,text,timestamptz,integer);
-- authorize_device gained an optional device-name argument; drop the old 1-arg overload
-- so PostgREST never sees two candidates.
drop function if exists public.authorize_device(text);
drop function if exists public.authorize_device(text,text);
drop function if exists public.admin_create_users(jsonb,integer);
-- MAC address support was removed: drop its old function signatures and columns.
drop function if exists public.authorize_device(text,text,text);
drop function if exists public.is_device_banned(text,text);
drop function if exists public.yas_is_banned(text,text);
drop function if exists public.admin_ban_manual(text,text);
drop function if exists public.yas_norm_mac(text);
alter table if exists public.yas_devices drop column if exists mac_address;
alter table if exists public.yas_banned_devices drop column if exists mac_address;

create table if not exists public.yas_profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  email text not null,
  role text not null default 'user' check (role in ('user','admin')),
  status text not null default 'pending' check (status in ('pending','active','suspended')),
  expires_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.yas_devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  device_id text not null,
  label text not null default 'Android installation',
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  revoked_at timestamptz,
  unique(user_id, device_id)
);
create index if not exists yas_devices_user_idx on public.yas_devices(user_id);
-- Device states:
--   active  : revoked_at is null and deleted_at is null
--   revoked : revoked_at set  (blocked; slot freed so another device may sign in; admin can restore it)
--   deleted : deleted_at set  (removed from the account but kept in history; the same device may register again as new)
alter table public.yas_devices add column if not exists first_ip text;
alter table public.yas_devices add column if not exists last_ip text;
alter table public.yas_devices add column if not exists deleted_at timestamptz;
alter table public.yas_devices add column if not exists android_id text;

-- Permanent device ban list. A device is banned if ANY stored identifier matches at login time:
-- installation id or ANDROID_ID (survives reinstall, changes only on factory reset).
create table if not exists public.yas_banned_devices (
  id uuid primary key default gen_random_uuid(),
  device_id text,
  android_id text,
  label text,
  last_ip text,
  reason text,
  banned_at timestamptz not null default now(),
  banned_by uuid
);
alter table public.yas_banned_devices enable row level security;
revoke all on public.yas_banned_devices from anon, authenticated;

create or replace function public.yas_is_banned(p_device_id text, p_android_id text)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists(select 1 from public.yas_banned_devices b where
    (nullif(p_device_id,'') is not null and b.device_id = p_device_id)
    or (nullif(p_android_id,'') is not null and b.android_id = p_android_id));
$$;

-- Called by the app BEFORE the login screen (anon key), so it only returns a yes/no.
create or replace function public.is_device_banned(p_device_id text default null, p_android_id text default null)
returns jsonb language sql stable security definer set search_path = '' as $$
  select jsonb_build_object('banned', public.yas_is_banned(p_device_id, p_android_id));
$$;

-- Client IP as seen by the Supabase API gateway (server-side, cannot be spoofed by the app body).
create or replace function public.yas_client_ip()
returns text language plpgsql stable set search_path = '' as $$
declare h jsonb; v text;
begin
  begin h := nullif(current_setting('request.headers', true), '')::jsonb;
  exception when others then h := null; end;
  if h is null then return null; end if;
  v := coalesce(nullif(h->>'cf-connecting-ip',''), nullif(trim(split_part(coalesce(h->>'x-forwarded-for',''), ',', 1)),''), nullif(h->>'x-real-ip',''));
  return left(v, 64);
end; $$;

create or replace function public.yas_on_signup()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.yas_profiles(user_id,email) values (new.id, coalesce(new.email,''))
  on conflict (user_id) do update set email = excluded.email;
  return new;
end; $$;
drop trigger if exists yas_auth_user_created on auth.users;
create trigger yas_auth_user_created after insert on auth.users
for each row execute procedure public.yas_on_signup();

create or replace function public.yas_touch_updated_at()
returns trigger language plpgsql set search_path = '' as $$
begin new.updated_at = now(); return new; end; $$;
drop trigger if exists yas_profiles_touch on public.yas_profiles;
create trigger yas_profiles_touch before update on public.yas_profiles
for each row execute procedure public.yas_touch_updated_at();

alter table public.yas_profiles enable row level security;
alter table public.yas_devices enable row level security;
drop policy if exists "users read own profile" on public.yas_profiles;
create policy "users read own profile" on public.yas_profiles for select to authenticated using (auth.uid() = user_id);
drop policy if exists "users read own devices" on public.yas_devices;
create policy "users read own devices" on public.yas_devices for select to authenticated using (auth.uid() = user_id);
revoke all on public.yas_profiles from anon, authenticated;
revoke all on public.yas_devices from anon, authenticated;
grant select on public.yas_profiles to authenticated;
grant select on public.yas_devices to authenticated;

-- Single-device policy: only one device may hold an active session per account.
-- Switching to a different device is allowed only after a 4-hour cool-down since
-- the previous device's last activity, so sharing one account across devices is
-- effectively limited to one device at a time.
create or replace function public.authorize_device(p_device_id text, p_device_name text default null, p_android_id text default null)
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
  v_status := p.status;
  if p.status = 'active' and (p.expires_at is null or p.expires_at <= now()) then v_status := 'expired'; end if;
  if v_status <> 'active' then
    return jsonb_build_object('active',false,'status',v_status,'expires_at',p.expires_at,'message',case when v_status='pending' then 'الحساب بانتظار التفعيل من الإدارة.' when v_status='suspended' then 'تم إيقاف هذا الحساب من الإدارة.' else 'انتهت مدة التفعيل أو لم يتم تحديد تاريخ الانتهاء.' end);
  end if;

  select * into v_existing from public.yas_devices where user_id=v_uid and device_id=p_device_id;

  if v_existing.id is not null and v_existing.deleted_at is null then
    if v_existing.revoked_at is not null then
      -- Cancelled by the admin: stays blocked (still visible in the dashboard) until restored.
      update public.yas_devices set last_ip = coalesce(v_ip,last_ip), label = coalesce(v_name,label), android_id = coalesce(v_aid,android_id) where id=v_existing.id;
      return jsonb_build_object('active',false,'status','device_revoked','expires_at',p.expires_at,'message','تم إلغاء تسجيل هذا الجهاز. تواصل مع الإدارة.');
    end if;
    -- Same device continuing its session: no cool-down applies.
    update public.yas_devices set last_seen_at=now(), last_ip = coalesce(v_ip,last_ip), label = coalesce(v_name,label), android_id = coalesce(v_aid,android_id) where id=v_existing.id;
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
      label=coalesce(v_name,label), first_ip=v_ip, last_ip=v_ip, android_id=v_aid where id=v_existing.id;
  else
    insert into public.yas_devices(user_id,device_id,label,first_ip,last_ip,android_id)
    values(v_uid,p_device_id,coalesce(v_name,'Android installation'),v_ip,v_ip,v_aid);
  end if;
  return jsonb_build_object('active',true,'status','active','expires_at',p.expires_at,'message','تم تسجيل الدخول من جهاز جديد.');
end; $$;

create or replace function public.admin_list_users()
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  return coalesce((select jsonb_agg(jsonb_build_object(
    'user_id',p.user_id,'email',p.email,'role',p.role,'status',case when p.status='active' and (p.expires_at is null or p.expires_at<=now()) then 'expired' else p.status end,
    'expires_at',p.expires_at,'created_at',p.created_at,
    'devices',(select coalesce(jsonb_agg(jsonb_build_object('id',d.id,'device_id',d.device_id,'label',d.label,'first_seen_at',d.first_seen_at,'last_seen_at',d.last_seen_at,'revoked_at',d.revoked_at,'deleted_at',d.deleted_at,'first_ip',d.first_ip,'last_ip',d.last_ip,'android_id',d.android_id) order by (d.deleted_at is not null), d.last_seen_at desc),'[]'::jsonb) from public.yas_devices d where d.user_id=p.user_id)
  ) order by p.created_at desc) from public.yas_profiles p),'[]'::jsonb);
end; $$;

create or replace function public.admin_set_license(p_user_id uuid, p_status text, p_expires_at timestamptz)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  if p_status not in ('pending','active','suspended') then raise exception 'حالة غير صالحة.'; end if;
  if p_status='active' and (p_expires_at is null or p_expires_at <= now()) then raise exception 'يجب تحديد تاريخ انتهاء مستقبلي للتفعيل.'; end if;
  update public.yas_profiles set status=p_status, expires_at=p_expires_at where user_id=p_user_id;
  if not found then raise exception 'الحساب غير موجود.'; end if;
  return jsonb_build_object('ok',true);
end; $$;

-- Cheap "am I an admin" check the app can call right after sign-in to decide
-- whether to show the admin panel entry point at all.
create or replace function public.am_i_admin()
returns jsonb language sql security definer set search_path = '' as $$
  select jsonb_build_object('is_admin', exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin'));
$$;

-- Activates every currently pending account in one call, all with the same
-- expiry date, so an admin can approve a whole batch of new sign-ups at once
-- instead of one at a time.
create or replace function public.admin_activate_all_pending(p_expires_at timestamptz)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_count integer;
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  if p_expires_at is null or p_expires_at <= now() then raise exception 'يجب تحديد تاريخ انتهاء مستقبلي للتفعيل.'; end if;
  update public.yas_profiles set status='active', expires_at=p_expires_at where status='pending';
  get diagnostics v_count = row_count;
  return jsonb_build_object('ok',true,'activated_count',v_count);
end; $$;

-- Cancel (revoke): blocks this device and frees the account's single slot. Row stays visible as "ملغي".
create or replace function public.admin_revoke_device(p_device_row_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  update public.yas_devices set revoked_at=coalesce(revoked_at,now()) where id=p_device_row_id and deleted_at is null;
  if not found then raise exception 'الجهاز غير موجود أو محذوف.'; end if;
  return jsonb_build_object('ok',true);
end; $$;

-- Delete: removes the device from the account. Kept as history ("محذوف"); revoked_at is also set so
-- older clients that only look at revoked_at never count it as active.
create or replace function public.admin_delete_device(p_device_row_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  update public.yas_devices set deleted_at=coalesce(deleted_at,now()), revoked_at=coalesce(revoked_at,now()) where id=p_device_row_id;
  if not found then raise exception 'الجهاز غير موجود.'; end if;
  return jsonb_build_object('ok',true);
end; $$;

-- Restore a cancelled/deleted device to active. Single-device policy: any other active device is cancelled.
create or replace function public.admin_restore_device(p_device_row_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_row public.yas_devices%rowtype;
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  select * into v_row from public.yas_devices where id=p_device_row_id;
  if not found then raise exception 'الجهاز غير موجود.'; end if;
  update public.yas_devices set revoked_at=now() where user_id=v_row.user_id and id<>v_row.id and revoked_at is null;
  update public.yas_devices set revoked_at=null, deleted_at=null, last_seen_at=now() where id=v_row.id;
  return jsonb_build_object('ok',true);
end; $$;

-- Permanently deletes an account from the platform (auth user + profile + devices via ON DELETE CASCADE).
-- Refuses to delete yourself or any admin account.
create or replace function public.admin_delete_user(p_user_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_role text;
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  if p_user_id = auth.uid() then raise exception 'لا يمكنك حذف حسابك الحالي.'; end if;
  select role into v_role from public.yas_profiles where user_id=p_user_id;
  if v_role = 'admin' then raise exception 'لا يمكن حذف حساب مدير. غيّر صلاحيته إلى user أولًا.'; end if;
  delete from auth.users where id = p_user_id;
  if not found then raise exception 'الحساب غير موجود.'; end if;
  return jsonb_build_object('ok',true);
end; $$;

-- Creates many accounts at once (email already confirmed) with the passwords supplied by the dashboard.
-- p_expires_at (future) activates them until that time; null leaves them pending. One failing row never blocks the rest.
create or replace function public.admin_create_users(p_accounts jsonb, p_expires_at timestamptz default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_item jsonb; v_email text; v_pw text; v_id uuid;
  v_results jsonb := '[]'::jsonb; v_col text; v_expires timestamptz;
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  if p_accounts is null or jsonb_typeof(p_accounts) <> 'array' then raise exception 'قائمة الحسابات غير صالحة.'; end if;
  if jsonb_array_length(p_accounts) = 0 or jsonb_array_length(p_accounts) > 200 then raise exception 'عدد الحسابات يجب أن يكون بين 1 و200.'; end if;
  if p_expires_at is not null then
    if p_expires_at <= now() then raise exception 'يجب تحديد تاريخ انتهاء مستقبلي للتفعيل.'; end if;
    v_expires := p_expires_at;
  end if;

  for v_item in select * from jsonb_array_elements(p_accounts) loop
    v_email := lower(trim(coalesce(v_item->>'email','')));
    v_pw := coalesce(v_item->>'password','');
    begin
      if v_email !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' then raise exception 'بريد غير صالح.'; end if;
      if length(v_pw) < 8 then raise exception 'كلمة المرور أقصر من 8 أحرف.'; end if;
      if exists(select 1 from auth.users where lower(email)=v_email) then raise exception 'البريد مسجّل مسبقًا.'; end if;
      v_id := gen_random_uuid();
      insert into auth.users(instance_id,id,aud,role,email,encrypted_password,email_confirmed_at,
        raw_app_meta_data,raw_user_meta_data,created_at,updated_at,confirmation_token,recovery_token,email_change_token_new,email_change)
      values('00000000-0000-0000-0000-000000000000',v_id,'authenticated','authenticated',v_email,
        extensions.crypt(v_pw, extensions.gen_salt('bf')),now(),
        '{"provider":"email","providers":["email"]}'::jsonb,'{}'::jsonb,now(),now(),'','','','');
      insert into auth.identities(id,provider_id,user_id,identity_data,provider,last_sign_in_at,created_at,updated_at)
      values(gen_random_uuid(),v_id::text,v_id,
        jsonb_build_object('sub',v_id::text,'email',v_email,'email_verified',true,'phone_verified',false),
        'email',now(),now(),now());
      -- Some GoTrue versions fail to sign in when these token columns are NULL; blank them where they exist.
      foreach v_col in array array['confirmation_token','recovery_token','email_change_token_new','email_change','email_change_token_current','phone_change','phone_change_token','reauthentication_token'] loop
        if exists(select 1 from pg_attribute where attrelid='auth.users'::regclass and attname=v_col and not attisdropped) then
          execute format('update auth.users set %I = '''' where id = $1 and %I is null', v_col, v_col) using v_id;
        end if;
      end loop;
      if v_expires is not null then
        update public.yas_profiles set status='active', expires_at=v_expires where user_id=v_id;
      end if;
      v_results := v_results || jsonb_build_object('email',v_email,'ok',true,'user_id',v_id);
    exception when others then
      v_results := v_results || jsonb_build_object('email',v_email,'ok',false,'error',sqlerrm);
    end;
  end loop;
  return jsonb_build_object('ok',true,'results',v_results);
end; $$;

-- Bans a device permanently (all its identifiers) and cancels its row. Reversible only via admin_unban.
create or replace function public.admin_ban_device(p_device_row_id uuid, p_reason text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_row public.yas_devices%rowtype; v_email text;
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  select * into v_row from public.yas_devices where id=p_device_row_id;
  if not found then raise exception 'الجهاز غير موجود.'; end if;
  select email into v_email from public.yas_profiles where user_id=v_row.user_id;
  if not exists(select 1 from public.yas_banned_devices where device_id=v_row.device_id) then
    insert into public.yas_banned_devices(device_id,android_id,label,last_ip,reason,banned_by)
    values(v_row.device_id,v_row.android_id,
      left(coalesce(v_row.label,'جهاز') || coalesce(' — ' || v_email,''),160),v_row.last_ip,left(nullif(trim(coalesce(p_reason,'')),''),200),auth.uid());
  end if;
  update public.yas_devices set revoked_at=coalesce(revoked_at,now()) where id=v_row.id;
  return jsonb_build_object('ok',true);
end; $$;

-- Manual ban by ANDROID_ID (for a device that never signed in, or from another source).
create or replace function public.admin_ban_manual(p_android_id text, p_reason text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_aid text := left(nullif(trim(coalesce(p_android_id,'')),''),64);
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  if v_aid is null then raise exception 'أدخل Android ID.'; end if;
  insert into public.yas_banned_devices(android_id,label,reason,banned_by)
  values(v_aid,'إضافة يدوية',left(nullif(trim(coalesce(p_reason,'')),''),200),auth.uid());
  return jsonb_build_object('ok',true);
end; $$;

create or replace function public.admin_list_bans()
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  return coalesce((select jsonb_agg(jsonb_build_object('id',b.id,'device_id',b.device_id,'android_id',b.android_id,
    'label',b.label,'last_ip',b.last_ip,'reason',b.reason,'banned_at',b.banned_at) order by b.banned_at desc) from public.yas_banned_devices b),'[]'::jsonb);
end; $$;

-- Removes the ban only; a device cancelled by the ban stays "ملغي" until you restore it.
create or replace function public.admin_unban(p_ban_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  delete from public.yas_banned_devices where id=p_ban_id;
  if not found then raise exception 'الحظر غير موجود.'; end if;
  return jsonb_build_object('ok',true);
end; $$;

-- Activates several accounts (by email) together with the same expiry. Reports emails that were not found.
create or replace function public.admin_activate_users(p_emails text[], p_expires_at timestamptz)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_emails text[]; v_found text[]; v_missing text[];
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  if p_expires_at is null or p_expires_at <= now() then raise exception 'يجب تحديد تاريخ انتهاء مستقبلي للتفعيل.'; end if;
  select coalesce(array_agg(distinct lower(trim(e))),'{}') into v_emails from unnest(coalesce(p_emails,'{}')) e where trim(e) <> '';
  if cardinality(v_emails) = 0 then raise exception 'أدخل بريدًا واحدًا على الأقل.'; end if;
  if cardinality(v_emails) > 500 then raise exception 'الحد الأقصى 500 بريد في المرة.'; end if;
  with upd as (
    update public.yas_profiles set status='active', expires_at=p_expires_at where lower(email) = any(v_emails) returning lower(email) as em
  ) select coalesce(array_agg(em),'{}') into v_found from upd;
  v_missing := array(select e from unnest(v_emails) e where e <> all(v_found));
  return jsonb_build_object('ok',true,'activated_count',cardinality(v_found),'not_found',to_jsonb(v_missing));
end; $$;

revoke all on function public.authorize_device(text,text,text) from public, anon;
grant execute on function public.authorize_device(text,text,text) to authenticated;
revoke all on function public.admin_list_users() from public, anon;
grant execute on function public.admin_list_users() to authenticated;
revoke all on function public.admin_set_license(uuid,text,timestamptz) from public, anon;
grant execute on function public.admin_set_license(uuid,text,timestamptz) to authenticated;
revoke all on function public.admin_revoke_device(uuid) from public, anon;
grant execute on function public.admin_revoke_device(uuid) to authenticated;
revoke all on function public.am_i_admin() from public, anon;
grant execute on function public.am_i_admin() to authenticated;
revoke all on function public.admin_activate_all_pending(timestamptz) from public, anon;
grant execute on function public.admin_activate_all_pending(timestamptz) to authenticated;
revoke all on function public.admin_delete_device(uuid) from public, anon;
grant execute on function public.admin_delete_device(uuid) to authenticated;
revoke all on function public.admin_restore_device(uuid) from public, anon;
grant execute on function public.admin_restore_device(uuid) to authenticated;
revoke all on function public.admin_delete_user(uuid) from public, anon;
grant execute on function public.admin_delete_user(uuid) to authenticated;
revoke all on function public.admin_create_users(jsonb,timestamptz) from public, anon;
grant execute on function public.admin_create_users(jsonb,timestamptz) to authenticated;
revoke all on function public.yas_client_ip() from public, anon;
grant execute on function public.yas_client_ip() to authenticated;
revoke all on function public.admin_ban_device(uuid,text) from public, anon;
grant execute on function public.admin_ban_device(uuid,text) to authenticated;
revoke all on function public.admin_ban_manual(text,text) from public, anon;
grant execute on function public.admin_ban_manual(text,text) to authenticated;
revoke all on function public.admin_list_bans() from public, anon;
grant execute on function public.admin_list_bans() to authenticated;
revoke all on function public.admin_unban(uuid) from public, anon;
grant execute on function public.admin_unban(uuid) to authenticated;
revoke all on function public.admin_activate_users(text[],timestamptz) from public, anon;
grant execute on function public.admin_activate_users(text[],timestamptz) to authenticated;
revoke all on function public.yas_is_banned(text,text) from public, anon, authenticated;
-- The only function the signed-out app may call: yes/no ban check before the login screen.
revoke all on function public.is_device_banned(text,text) from public;
grant execute on function public.is_device_banned(text,text) to anon, authenticated;
