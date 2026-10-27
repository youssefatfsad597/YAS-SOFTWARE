-- YAS SOFTWARE licensing schema for Supabase.
-- Run in Supabase SQL Editor as project owner. Never put service_role key in the app or browser.
create extension if not exists pgcrypto with schema extensions;

create table if not exists public.yas_profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  email text not null,
  role text not null default 'user' check (role in ('user','admin')),
  status text not null default 'pending' check (status in ('pending','active','suspended')),
  expires_at timestamptz,
  device_limit integer not null default 1 check (device_limit between 1 and 100),
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

create or replace function public.authorize_device(p_device_id text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  p public.yas_profiles%rowtype;
  v_count integer;
  v_existing public.yas_devices%rowtype;
  v_status text;
begin
  if v_uid is null then raise exception 'يجب تسجيل الدخول.'; end if;
  if p_device_id is null or length(p_device_id) < 8 or length(p_device_id) > 128 then raise exception 'معرّف الجهاز غير صالح.'; end if;
  select * into p from public.yas_profiles where user_id = v_uid;
  if not found then return jsonb_build_object('active',false,'status','pending','message','الحساب غير مسجل في نظام التراخيص.'); end if;
  v_status := p.status;
  if p.status = 'active' and (p.expires_at is null or p.expires_at <= now()) then v_status := 'expired'; end if;
  if v_status <> 'active' then
    return jsonb_build_object('active',false,'status',v_status,'expires_at',p.expires_at,'device_limit',p.device_limit,'device_count',(select count(*) from public.yas_devices d where d.user_id=v_uid and d.revoked_at is null),'message',case when v_status='pending' then 'الحساب بانتظار التفعيل من الإدارة.' when v_status='suspended' then 'تم إيقاف هذا الحساب من الإدارة.' else 'انتهت مدة التفعيل أو لم يتم تحديد تاريخ الانتهاء.' end);
  end if;
  select * into v_existing from public.yas_devices where user_id=v_uid and device_id=p_device_id;
  if found and v_existing.revoked_at is not null then
    return jsonb_build_object('active',false,'status','device_revoked','expires_at',p.expires_at,'device_limit',p.device_limit,'message','تم إلغاء تسجيل هذا الجهاز. تواصل مع الإدارة.');
  end if;
  if not found then
    select count(*) into v_count from public.yas_devices where user_id=v_uid and revoked_at is null;
    if v_count >= p.device_limit then
      return jsonb_build_object('active',false,'status','device_limit','expires_at',p.expires_at,'device_limit',p.device_limit,'device_count',v_count,'message','وصل الحساب إلى الحد الأقصى للأجهزة المسجلة.');
    end if;
    insert into public.yas_devices(user_id,device_id) values(v_uid,p_device_id);
  else
    update public.yas_devices set last_seen_at=now() where id=v_existing.id;
  end if;
  select count(*) into v_count from public.yas_devices where user_id=v_uid and revoked_at is null;
  return jsonb_build_object('active',true,'status','active','expires_at',p.expires_at,'device_limit',p.device_limit,'device_count',v_count,'message','الترخيص ساري.');
end; $$;

create or replace function public.admin_list_users()
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  return coalesce((select jsonb_agg(jsonb_build_object(
    'user_id',p.user_id,'email',p.email,'role',p.role,'status',case when p.status='active' and (p.expires_at is null or p.expires_at<=now()) then 'expired' else p.status end,
    'expires_at',p.expires_at,'device_limit',p.device_limit,'created_at',p.created_at,
    'device_count',(select count(*) from public.yas_devices d where d.user_id=p.user_id and d.revoked_at is null),
    'devices',(select coalesce(jsonb_agg(jsonb_build_object('id',d.id,'device_id',d.device_id,'label',d.label,'first_seen_at',d.first_seen_at,'last_seen_at',d.last_seen_at,'revoked_at',d.revoked_at) order by d.last_seen_at desc),'[]'::jsonb) from public.yas_devices d where d.user_id=p.user_id)
  ) order by p.created_at desc) from public.yas_profiles p),'[]'::jsonb);
end; $$;

create or replace function public.admin_set_license(p_user_id uuid, p_status text, p_expires_at timestamptz, p_device_limit integer)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.yas_profiles where user_id=auth.uid() and role='admin') then raise exception 'غير مصرح: صلاحية المدير مطلوبة.'; end if;
  if p_status not in ('pending','active','suspended') then raise exception 'حالة غير صالحة.'; end if;
  if p_device_limit < 1 or p_device_limit > 100 then raise exception 'حد الأجهزة يجب أن يكون بين 1 و100.'; end if;
  if p_status='active' and (p_expires_at is null or p_expires_at <= now()) then raise exception 'يجب تحديد تاريخ انتهاء مستقبلي للتفعيل.'; end if;
  update public.yas_profiles set status=p_status, expires_at=p_expires_at, device_limit=p_device_limit where user_id=p_user_id;
  if not found then raise exception 'الحساب غير موجود.'; end if;
  return jsonb_build_object('ok',true);
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
revoke all on function public.admin_set_license(uuid,text,timestamptz,integer) from public, anon;
grant execute on function public.admin_set_license(uuid,text,timestamptz,integer) to authenticated;
revoke all on function public.admin_revoke_device(uuid) from public, anon;
grant execute on function public.admin_revoke_device(uuid) to authenticated;
