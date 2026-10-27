# YAS SOFTWARE — إعدادات مشروع Supabase الخاص بك

تم إدراج الإعدادات العامة التي أرسلتها في Gradle ولوحة الإدارة. هذا لا يعني أن الاتصال أو قاعدة البيانات قد تم اختبارهما.

- Project URL: `https://awsiewfvfdpcwugxvkfu.supabase.co`
- Publishable key: `sb_publishable_MOJvEpcs4ZSf-vvATkay1w_ZOvbTUH-`
- المفتاح من نوع publishable ومناسب للعميل؛ لا تضف `service_role` أو secret key إلى Android أو لوحة الإدارة.

## 1) تهيئة قاعدة البيانات

1. افتح [Supabase Dashboard](https://supabase.com/dashboard/project/awsiewfvfdpcwugxvkfu).
2. افتح SQL Editor.
3. افتح الملف `supabase/schema.sql` في المشروع وانسخ محتواه كاملًا إلى SQL Editor ثم نفّذه.
4. راجع النتائج وتأكد أن الجداول والدوال تم إنشاؤها دون أخطاء. لا تعتبر النظام جاهزًا قبل نجاح هذه الخطوة.

## 2) إنشاء حساب المدير

1. أنشئ حسابًا عبر التطبيق أو من Authentication → Users، وأكّد البريد الإلكتروني.
2. في SQL Editor نفّذ الاستعلام التالي بعد استبدال البريد بعنوان حسابك الفعلي:

```sql
update public.yas_profiles
set role = 'admin'
where email = 'YOUR_ADMIN_EMAIL@example.com';

select user_id, email, role, status
from public.yas_profiles
where email = 'YOUR_ADMIN_EMAIL@example.com';
```

تأكد أن الحساب الصحيح وحده أصبح `admin`. لا تجعل المستخدمين العاديين مدراء.

## 3) بناء تطبيق Android

الإعدادات محفوظة في `gradle.properties` بالمشروع. افتح المجلد في Android Studio، نفّذ Gradle Sync ثم جرّب بناء Debug. لا يوجد Gradle Wrapper في الأرشيف الحالي، لذلك يلزم Gradle/Android Studio مثبت ومتوافق.

## 4) لوحة الإدارة

افتح `admin-dashboard/index.html` في متصفح. حقلا URL وPublishable key مملوءان مسبقًا. سجّل الدخول بحساب المدير فقط. استخدم استضافة HTTPS عند نشر لوحة الإدارة؛ فتح الملف محليًا قد يتأثر بقيود المتصفح أو تحميل مكتبة Supabase من CDN.

## 5) اختبارات إلزامية قبل الاستخدام

- حساب جديد يظل `pending` ولا يصل للأدوات المحمية.
- حساب غير مدير لا يستطيع استدعاء دوال الإدارة.
- المدير يفعّل حسابًا بتاريخ انتهاء مستقبلي وحد أجهزة محدد.
- الحساب المنتهي أو الموقوف أو الذي تجاوز حد الأجهزة يُرفض.
- إلغاء جهاز يمنع ذلك التثبيت من استخدام الترخيص.
- لا يوجد مفتاح `service_role` في المشروع.

**الحالة الحالية:** إعدادات العميل العامة مضافة محليًا فقط. لم يتم تنفيذ SQL على مشروعك، ولم يتم اختبار الاتصال أو بناء APK في هذه الخطوة.
