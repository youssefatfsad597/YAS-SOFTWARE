# YAS SOFTWARE — Account & License setup

**إعدادات مشروعك:** راجع `SUPABASE-YAS-CONFIG.md`؛ تم ملء URL ومفتاح Publishable اللذين أرسلتهما. لم يتم اختبار الاتصال أو تنفيذ SQL تلقائيًا.

This repository now contains an Android login/license gate, a Supabase SQL schema, and a static admin dashboard. It is **not deployed or connected yet**. The Android app intentionally denies protected access until a live Supabase project returns an active, unexpired license.

## 1. Create the Supabase project

1. Create a project at https://supabase.com/ and keep the project URL and **publishable/anon key** available.
2. In **Authentication → Providers → Email**, enable email sign-up and keep email confirmation enabled for production.
3. In **SQL Editor**, run the entire `supabase/schema.sql` file, then `supabase/02_operation_auth.sql`, then `supabase/04_device_fingerprint.sql` (in that order; each is safe to re-run). The last one adds a third device-ban identifier (Widevine device id, which survives a factory reset on most phones, unlike the installation id or ANDROID_ID alone) — see the comment at the top of that file for details, and rebuild/reinstall the Android app afterwards so it starts sending the new identifier.
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

1. `reset-password.html` مستضاف في ريبو عام (public) منفصل اسمه **Yas** عبر GitHub Pages
   (لازم يكون عام لأن GitHub Pages المجاني لا يعمل على ريبوهات خاصة):
   `https://youssefatfsad597.github.io/Yas/reset-password.html`
2. في Supabase → Authentication → URL Configuration:
   - **Site URL** و **Redirect URLs**: ضع نفس الرابط أعلاه بالظبط (بالمسار الكامل، مش الدومين لوحده).
3. `app/build.gradle.kts` فيه هذا الرابط كقيمة افتراضية لـ `RESET_REDIRECT_URL` الآن، فمش محتاج تمرر `-PRESET_REDIRECT_URL` يدويًا إلا لو غيّرت اسم الريبو أو الدومين.
4. الرابط في الإيميل ← صفحة `reset-password.html` على ريبو Yas ← زر «فتح التطبيق» ← شاشة «تعيين كلمة مرور جديدة» داخل التطبيق.

## 8. اسم المُرسِل وشكل الإيميل (من لوحة Supabase، لا من الكود)

- **اسم المُرسِل:** Authentication → Settings (أو Emails) → **SMTP Settings** → حقل **Sender name** = `YAS SOFTWARE`. (يظهر `me` فقط لو أرسلت من Gmail لنفس حسابك أو تركت الحقل فارغًا.)
- **القوالب:** Authentication → **Email Templates** → الصق محتوى `supabase/email-templates/recovery.html` (Reset Password) و`confirmation.html` (Confirm signup)، وغيّر Subject إلى `YAS SOFTWARE — إعادة تعيين كلمة المرور`.
- **الصورة:** الإيميل لا يستطيع عرض صورة من داخل المشروع؛ لازم رابط HTTPS عام. ارفع `yas_logo.png` لنفس ريبو **Yas** العام (بجانب `reset-password.html`) — القالبان `recovery.html` و`confirmation.html` فيهم الرابط جاهز بالفعل:
  `https://raw.githubusercontent.com/youssefatfsad597/Yas/main/yas_logo.png`
  بعض البرامج تحجب الصور حتى يضغط المستخدم «عرض الصور».

## 9. ملفات الفيرموير على السيرفر (بدل تضمينها داخل الـ APK)

هذا نظام بديل/إضافي لـ `app/src/main/assets/x216b/` (EmbeddedFirmware.kt): بدل ما الملف يتشفّر مرة واحدة وقت البناء ويتسافر جوه التطبيق، بيتاخد مشفّر من سيرفر خاص، والمفتاح بتاعه مايتسلّمش للتطبيق إلا لو الحساب فعّال في لحظة الطلب نفسها — حتى لو حد عدّل نسخة الـ APK محليًا.

1. **شغّل الملف `supabase/03_firmware_storage.sql`** في SQL Editor بعد `schema.sql` و`02_operation_auth.sql`.
2. **اعمل Storage bucket** اسمه بالظبط `yas-firmware` (Private، مش Public)، وضيف الـ policy المكتوبة في آخر السكريبت (قسم 5-ب).
3. **ارفع ملف فيرموير** من جهازك (مش من التطبيق ولا من لوحة الإدارة):
   ```bash
   pip install requests cryptography --break-system-packages
   export SUPABASE_URL=https://xxxx.supabase.co
   export SUPABASE_SERVICE_ROLE_KEY=ey...   # من Project Settings → API → service_role (سرّي، متشاركوش حد)
   python3 tools/upload_firmware.py \
     --file /path/to/sec_efs.bin \
     --slug x216b/sec_efs.bin \
     --display-name "X216B sec_efs.bin" \
     --device-model X216B \
     --cert-sha256 <SHA-256 شهادة التوقيع بتاعة الـ release، حروف صغيرة>
   ```
   لو سبت `--cert-sha256` فاضية بيستخدم شهادة الـ debug.keystore الافتراضية (مفيد للتجربة بس، مش للنشر).
4. **في الكود:** `RemoteFirmware.catalog(licenseService)` لعرض قائمة الملفات، و`RemoteFirmware.openStream(context, licenseService, slug)` للتنزيل وفك التشفير كـ `InputStream` عادي. كل استدعاء بيعمل تحقق كامل من حالة الاشتراك على السيرفر (`authorize_firmware_download`)، وبيتأكد في النهاية إن الـ SHA-256 بتاع الملف اللي اتفك مطابق للمسجل وقت الرفع — لو مش مطابق بيرمي استثناء بدل ما يسيب ملف فاسد يتستخدم في الفلاش.
5. **لو غيّرت المفاتيح** في `app/src/main/cpp/yas_native.c` (`EMBEDDED_PARTIAL_KEY_ENC` / `FW_KEY_MATERIAL` / `FW_KEY_MASK`)، **لازم** تحدّث نفس القيم (بصورتها الأصلية غير المشفّرة) في `tools/upload_firmware.py` (`EMBEDDED_PARTIAL_KEY` / `FW_KEY_MATERIAL`) — وإلا أي ملف جديد يترفع هيتشفر بمفتاح متوقع من التطبيق، لكن مش هو نفسه اللي البرنامج هيحسبه، فيفشل فك التشفير.

## 10. كل حاجة من الموبايل فقط، عن طريق GitHub Actions (من غير كمبيوتر)

المشروع ده أصلاً متبني على إنك تشتغل من تاب الـ **Actions** في GitHub من المتصفح أو تطبيق GitHub على الموبايل — من غير ما تفتح Android Studio أو تشغّل أي أمر على جهازك. فيه 5 ملفات Workflow جاهزة تحت `.github/workflows/`:

| الملف | الوظيفة | إزاي تشغّله |
|---|---|---|
| `generate-keystore.yml` | يعمل شهادة توقيع release جديدة (مرة واحدة بس) ويطلعلك `RELEASE_CERT_SHA256` و`KEYSTORE_BASE64` | Actions ← اختاره ← Run workflow ← حط كلمتين سر |
| `main.yml` | يبني نسخة **debug** (للتجربة) ويحط الـ APK كمرفق تقدر تنزله | يشتغل تلقائي مع كل push، أو Run workflow يدوي |
| `release.yml` | يبني نسخة **release** موقّعة جاهزة للنشر | Run workflow يدوي (محتاج الأسرار اللي تحت) |
| `upload-firmware.yml` | **جديد** — يرفع ملف فيرموير للسيرفر ويشفّره (بديل `tools/upload_firmware.py` اللي محتاج كمبيوتر) | Run workflow ← املا الفورم |
| `split.yml` | أداة مساعدة مرة واحدة (مش محتاجها غالبًا) | — |

**الأسرار المطلوبة** (Settings ← Secrets and variables ← Actions ← New repository secret)، لازم تتحطّ قبل أي حاجة:

- `SUPABASE_URL`, `SUPABASE_ANON_KEY` — لـ `main.yml`/`release.yml`.
- `RELEASE_CERT_SHA256`, `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD` — لـ `release.yml` (تطلع من `generate-keystore.yml`).
- `SUPABASE_SERVICE_ROLE_KEY` — **سرّ جديد مطلوب** لـ `upload-firmware.yml` بس (من Project Settings ← API ← `service_role`). لازم يتحط كـ secret، **ممنوع** يدخل أي كود أو build.gradle.

**لرفع ملف فيرموير من الموبايل (بدل الأمر اللي محتاج كمبيوتر):**
1. Actions ← **Upload Firmware File to Server** ← Run workflow.
2. في `firmware_url` حط رابط تنزيل مباشر للملف (مثلاً رابط تنزيل مباشر من Google Drive، أو أي استضافة ملفات بتديك رابط HTTPS مباشر) — **أو** لو الملف صغير (أقل من ~25MB) ممكن ترفعه هو نفسه للريبو الأول (زرار "Add file" في GitHub من الموبايل) وتحط مكانه في `repo_file_path`.
3. املا `slug` و`display_name` (و`device_model` و`cert_sha256` لو عندك).
4. Run workflow، وانتظر لحد ما يخلص (علامة ✓ خضرا). الملف الأصلي (plaintext) **مابيتسجلش في الريبو خالص** — بينتقل في مساحة مؤقتة على سيرفر GitHub وبيتمسح لما الـ job يخلص.

**ملاحظة مهمة:** ملفات الفيرموير المضمّنة جوه الـ APK (الفولدر `firmware/x216b/` وما شابه) **مختلفة تمامًا** عن النظام الجديد ده — دي لسه بتتشفر وقت البناء بـ `tools/encrypt_firmware_assets.sh` جوه `main.yml`/`release.yml` نفسهم، ومفيش داعي تغيّر فيها حاجة. النظام الجديد (`upload-firmware.yml`) للملفات اللي عايز تضيفها أو تحدّثها **من غير ما تعمل build جديد للتطبيق** كل مرة.
