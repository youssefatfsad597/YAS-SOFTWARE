-- YAS SOFTWARE licensing schema for Supabase.
-- Run in Supabase SQL Editor as project owner. Never put service_role key in the app or browser.
create extension if not exists pgcrypto with schema extensions;

-- One-time migration for projects created before the single-device policy:
-- drop the old per-account device_limit column and the old 4-argument
-- admin_set_license overload (its signature changed when device_limit was removed).
alter table if exists public.yas_profiles drop column if exists device_limit;
drop function if exists public.admin_set_license(uuid,text,timestamptz,integer);

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
create or replace function public.authorize_device(p_device_id text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  p public.yas_profiles%rowtype;
  v_existing public.yas_devices%rowtype;
  v_other public.yas_devices%rowtype;
  v_status text;
  v_wait_seconds integer;
  v_cooldown_seconds constant integer := 4 * 3600; -- 4 hours between sessions on different devices
begin
  if v_uid is null then raise exception 'يجب تسجيل الدخول.'; end if;
  if p_device_id is null or length(p_device_id) < 8 or length(p_device_id) > 128 then raise exception 'معرّف الجهاز غير صالح.'; end if;
  select * into p from public.yas_profiles where user_id = v_uid;
  if not found then return jsonb_build_object('active',false,'status','pending','message','الحساب غير مسجل في نظام التراخيص.'); end if;
  v_status := p.status;
  if p.status = 'active' and (p.expires_at is null or p.expires_at <= now()) then v_status := 'expired'; end if;
  if v_status <> 'active' then
    return jsonb_build_object('active',false,'status',v_status,'expires_at',p.expires_at,'message',case when v_status='pending' then 'الحساب بانتظار التفعيل من الإدارة.' when v_status='suspended' then 'تم إيقاف هذا الحساب من الإدارة.' else 'انتهت مدة التفعيل أو لم يتم تحديد تاريخ الانتهاء.' end);
  end if;

  select * into v_existing from public.yas_devices where user_id=v_uid and device_id=p_device_id;
  if found and v_existing.revoked_at is not null then
    return jsonb_build_object('active',false,'status','device_revoked','expires_at',p.expires_at,'message','تم إلغاء تسجيل هذا الجهاز. تواصل مع الإدارة.');
  end if;

  if found then
    -- Same device continuing its session: no cool-down applies.
    update public.yas_devices set last_seen_at=now() where id=v_existing.id;
    return jsonb_build_object('active',true,'status','active','expires_at',p.expires_at,'message','الترخيص ساري.');
  end if;

  select * into v_other from public.yas_devices where user_id=v_uid and revoked_at is null order by last_seen_at desc limit 1;
  if found then
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

  insert into public.yas_devices(user_id,device_id) values(v_uid,p_device_id);
  return jsonb_build_object('active',true,'status','active','expires_at',p.expires_at,'message','تم تسجيل الدخول من جهاز جديد.');
end; $$;

create or replace function public.admin_list_users()
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  return coalesce((select jsonb_agg(jsonb_build_object(
    'user_id',p.user_id,'email',p.email,'role',p.role,'status',case when p.status='active' and (p.expires_at is null or p.expires_at<=now()) then 'expired' else p.status end,
    'expires_at',p.expires_at,'created_at',p.created_at,
    'devices',(select coalesce(jsonb_agg(jsonb_build_object('id',d.id,'device_id',d.device_id,'label',d.label,'first_seen_at',d.first_seen_at,'last_seen_at',d.last_seen_at,'revoked_at',d.revoked_at) order by d.last_seen_at desc),'[]'::jsonb) from public.yas_devices d where d.user_id=p.user_id)
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

create or replace function public.admin_revoke_device(p_device_row_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  update public.yas_devices set revoked_at=now() where id=p_device_row_id;
  if not found then raise exception 'الجهاز غير موجود.'; end if;
  return jsonb_build_object('ok',true);
end; $$;

revoke all on function public.authorize_device(text) from public, anon;
grant execute on function public.authorize_device(text) to authenticated;
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
