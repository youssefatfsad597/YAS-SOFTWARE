# نسختين: Online (زي ما هي) + Offline (جديدة)

## الملفات اللي اتنقلت (من غير أي تعديل في محتواها) إلى app/src/online/
- auth/: AdminService.kt, AdminScreen.kt, AuthGate.kt, KillSwitchWorker.kt,
  LicenseService.kt, NativeCore.kt, OperationAuthClient.kt, OperationKeyClient.kt,
  RemoteFirmwareClient.kt, SensitiveOperationGate.kt, TamperGuard.kt, YasIcons.kt
- protocol/: EmbeddedFirmware.kt, T505NFirmware.kt, T509Firmware.kt,
  RemoteFirmware.kt, FirmwareCipher.kt

هدول هما بالظبط نفس الملفات اللي كانت في app/src/main/ قبل كده، منقولة بس.
لما تعمل build بـ `assembleOnlineRelease` أو `assembleOnlineDebug`، Gradle بياخد
النسخة دي، فالسلوك مطابق 100% لما كان شغال قبل إضافة الـ flavors.

## الملفات الجديدة في app/src/offline/ (بديل كل ملف فوق، بنفس الاسم ونفس الـ API)
- auth/LicenseService.kt — stub، مفيهوش اتصال إنترنت خالص
- auth/AuthGate.kt — بيدخل على طول من غير تسجيل دخول
- auth/SensitiveOperationGate.kt — بيرخّص أي عملية محليًا من غير سيرفر
- auth/KillSwitchWorker.kt — no-op
- protocol/EmbeddedFirmware.kt, T505NFirmware.kt, T509Firmware.kt —
  بتقرأ الملفات **من غير تشفير** مباشرة من assets بدل فك التشفير بمفتاح من السيرفر
- protocol/RemoteFirmware.kt — stub بيقول إن الكتالوج البعيد مش متاح أوفلاين
  (الأزرار الجاهزة X216B / T505N / T509 بتشتغل عادي من الملفات المرفقة)

## assets جديدة (ملفات الفيرموير الحقيقية، بدون تشفير)
app/src/offline/assets/{x216b,t505n,t509}/ — منسوخة من firmware/ الموجود
في الريبو أصلاً، بدون أي تعديل على firmware/ نفسه.

## build.gradle.kts
- اتضاف flavorDimensions "edition" بفلايفورين: online (applicationId زي ما هو
  بالظبط، com.yas.flashedl) و offline (applicationId + ".offline")
- مفيش أي تعديل على باقي الإعدادات (signing/minify/compose...الخ)

## main.yml / release.yml
- اتضافت خطوات build وupload منفصلة للـ offline، وخطوات الـ online اتحولت
  لتستهدف الفلايفور الصريح (assembleOnlineDebug / assembleOnlineRelease) بدل
  assembleDebug/assembleRelease العام — ده تغيير في اسم الـ task بس، مش في
  أي محتوى أو سلوك لنسخة الأونلاين نفسها.

## اللي محتاج تعمله بعد ما تدمج الملفات دي في الريبو
1. تتأكد إن `git status` بيوريك الملفات المنقولة كـ "renamed" مش "deleted+new"
   (لو مش مستخدم git mv، اعمل commit عادي، المهم الملفات موجودة في المكان الصح).
2. تبني `assembleOfflineDebug` محليًا مرة الأول للتأكد إن كله بيتجمّع صح.
3. حجم ملفات assets/x216b (خصوصًا sec_efs.bin ~20MB) هيكبّر حجم الـ APK
   الأوفلاين بشكل ملحوظ — ده متوقع ومقصود لأن الهدف "كله جوه البرنامج".
