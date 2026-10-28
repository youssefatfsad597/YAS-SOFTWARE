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

## 6. تحديث لوحة الإدارة (الأجهزة / IP / حذف الحساب / إنشاء حسابات)

أعد تشغيل ملف `supabase/schema.sql` كاملًا في SQL Editor (آمن لإعادة التشغيل). يضيف:
- إلغاء جهاز (يبقى ظاهرًا «ملغي») وحذف جهاز (يبقى في السجل «محذوف») واستعادة جهاز.
- تسجيل IP الجهاز (أول/آخر) من الخادم، واسم الجهاز يأتي من التطبيق (يظهر بعد أول دخول بالنسخة الجديدة).
- حذف حساب نهائيًا (`admin_delete_user`) ولا يعمل على حسابات المدير ولا حسابك.
- إنشاء حسابات متعددة بباسوردات عشوائية (`admin_create_users`) بحد أقصى 200 في المرة.
- حظر الأجهزة: قائمة `yas_banned_devices` تُفحص قبل شاشة الدخول (`is_device_banned`) وأيضًا داخل `authorize_device`. المطابقة بمعرّف التثبيت أو Android ID (يبقى بعد إعادة تثبيت التطبيق ويتغير فقط بعد فورمات المصنع).
- تفعيل عدة حسابات مع بعض: `admin_activate_users`. إنشاء الحسابات صار يأخذ تاريخ انتهاء (`p_expires_at`) بدل عدد الأيام.

## 7. إعادة تعيين كلمة المرور من الإيميل (Deep Link)

1. استضف `reset-password.html` على HTTPS (مثلاً GitHub Pages).
2. في Supabase → Authentication → URL Configuration:
   - **Site URL** و **Redirect URLs**: أضف رابط الصفحة المستضافة (مثال: `https://USER.github.io/YAS-SOFTWARE/reset-password.html`).
3. ابنِ التطبيق مع: `-PRESET_REDIRECT_URL=https://USER.github.io/YAS-SOFTWARE/reset-password.html`
   (لو تركته فارغًا يرسل `yassoftware://reset-password` مباشرة، ويجب إضافته أيضًا في Redirect URLs، لكن بعض تطبيقات الإيميل لا تفتح هذه الروابط).
4. الرابط في الإيميل ← الصفحة ← زر «فتح التطبيق» ← شاشة «تعيين كلمة مرور جديدة» داخل التطبيق.

## 8. اسم المُرسِل وشكل الإيميل (من لوحة Supabase، لا من الكود)

- **اسم المُرسِل:** Authentication → Settings (أو Emails) → **SMTP Settings** → حقل **Sender name** = `YAS SOFTWARE`. (يظهر `me` فقط لو أرسلت من Gmail لنفس حسابك أو تركت الحقل فارغًا.)
- **القوالب:** Authentication → **Email Templates** → الصق محتوى `supabase/email-templates/recovery.html` (Reset Password) و`confirmation.html` (Confirm signup)، وغيّر Subject إلى `YAS SOFTWARE — إعادة تعيين كلمة المرور`.
- **الصورة:** الإيميل لا يستطيع عرض صورة من داخل المشروع؛ لازم رابط HTTPS عام. ارفع `assets/yas_logo.png` (GitHub Pages أو `https://raw.githubusercontent.com/USER/REPO/main/assets/yas_logo.png`) واستبدل `LOGO_URL` في القالبين. بعض البرامج تحجب الصور حتى يضغط المستخدم «عرض الصور».
