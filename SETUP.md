# YAS SOFTWARE — Account & License setup

**إعدادات مشروعك:** راجع `SUPABASE-YAS-CONFIG.md`؛ تم ملء URL ومفتاح Publishable اللذين أرسلتهما. لم يتم اختبار الاتصال أو تنفيذ SQL تلقائيًا.

This repository now contains an Android login/license gate, a Supabase SQL schema, and a static admin dashboard. It is **not deployed or connected yet**. The Android app intentionally denies protected access until a live Supabase project returns an active, unexpired license.

## 1. Create the Supabase project

1. Create a project at https://supabase.com/ and keep the project URL and **publishable/anon key** available.
2. In **Authentication → Providers → Email**, enable email sign-up and keep email confirmation enabled for production.
3. In **SQL Editor**, run the entire `supabase/schema.sql` file.
4. Create your administrator account through Supabase Auth (the app registration screen or Authentication → Users). Confirm its email.
5. In SQL Editor, grant the admin role to that exact account. Replace the email carefully:

   ```sql
   update public.yas_profiles
   set role = 'admin'
   where email = 'YOUR_ADMIN_EMAIL@example.com';
   ```

   Verify exactly one row was updated. Do not expose the database owner/service-role credentials.

## 2. Configure the Android build

Pass the project URL and public anon/publishable key as Gradle properties; never use the `service_role` secret in Android:

```bash
gradle assembleDebug \
  -PSUPABASE_URL=https://YOUR_PROJECT.supabase.co \
  -PSUPABASE_ANON_KEY=YOUR_PUBLIC_ANON_KEY
```

If building in Android Studio, add these properties to your **local** `~/.gradle/gradle.properties` (not a public repository):

```properties
SUPABASE_URL=https://YOUR_PROJECT.supabase.co
SUPABASE_ANON_KEY=YOUR_PUBLIC_ANON_KEY
```

Then sync Gradle and build. The repository does not include a Gradle wrapper, so use an installed compatible Gradle/Android Studio. A build with placeholder values will compile but cannot authenticate.

## 3. Run the admin dashboard

1. Open `admin-dashboard/index.html` in a browser, or host this folder on a static HTTPS host.
2. Enter the Supabase Project URL and public anon/publishable key.
3. Sign in using the email/password of the account whose `yas_profiles.role` is `admin`.
4. The dashboard lists registered accounts and devices. Select a user and choose a status, a future expiration time, and device limit, then save.
5. New accounts are `pending` by default. They cannot use the app until an administrator sets `status='active'` and a future `expires_at`.

The dashboard uses only authenticated RPC functions for admin actions. The SQL functions check the caller's role on the server. The `service_role` key must never be added to HTML, JavaScript, Android, source control, or a client device.

## 4. Licensing behavior

- Registration does not grant a license.
- Login obtains a Supabase Auth session, then calls `authorize_device` on the server.
- The server checks status, expiry, and the per-account device limit before granting access.
- The app rechecks the license periodically while open; network failure fails closed (protected tools stay locked).
- Admin can suspend/reactivate an account, change expiration and device limit, or revoke a registered installation.
- Device count is based on a random installation ID stored in Android encrypted preferences. It is a practical limit, **not an unspoofable hardware identity**. Reinstalling/clearing app data may create a new ID; revoking an old device frees its slot only when the admin revokes it.

## 5. Security limitations and production checklist

- No client-side application or embedded firmware can be made literally impossible to reverse engineer. R8 shrinking/obfuscation is enabled for release builds, but files bundled in the APK can still be extracted by a determined user.
- Supabase anon/publishable keys are designed to be public. Database safety depends on RLS and server-side role checks. Never publish a service-role key.
- Use HTTPS hosting, strong unique administrator credentials, MFA for the Supabase admin account, email confirmation, and project backups.
- Set Supabase Auth rate limits and SMTP settings appropriate for production.
- Test pending, active, expired, suspended, device-limit, revoked-device, refresh-token, offline, and admin/non-admin cases before distributing an APK.
- The app's local gate protects normal app use, but an attacker who patches the APK may bypass local UI checks. Any truly sensitive operation must also be authorized by a trusted server at the moment it occurs. Device flashing is performed locally, so server-side licensing cannot prevent all patched-client use.
- `allowBackup=false`, cleartext traffic disabled, encrypted session preferences, and R8 release shrinking are configured. This is defense in depth, not a guarantee of invulnerability.
