# Mi_Max Ai Desktop

نسخة سطح مكتب مستقلة من Mi_Max Ai تستهدف Windows 10/11 بمعماريتي x86 (32-bit) وx64 (64-bit).

## البناء والتنزيل
- افتح GitHub Actions ثم اختر **Mi_Max Ai Desktop (Windows x86 + x64)**.
- كل بناء ينتج حزمتين منفصلتين: `MiMaxAi-Desktop-Windows-x86.zip` و`MiMaxAi-Desktop-Windows-x64.zip`.
- الحزم تحتوي Java Runtime المطابق لمعمارية الحزمة، ولا تتطلب تثبيت Java أو Android أو Termux.
- فك ضغط الحزمة وشغّل `Start-MiMaxAi.bat`.

## الوظائف الموجودة في نسخة سطح المكتب الأولية
- واجهة Swing عربية داكنة مستقلة عن Android.
- كشف أنواع ملفات الصور المدعومة من محركات ROM المشتركة.
- تحويل Android Sparse إلى Raw والعكس.
- قراءة بيانات أقسام super.img.
- استخراج boot.img وvendor_boot باستخدام محرك المشروع.
- قراءة وعرض واستخراج أرشيفات ZIP المتوافقة.
- إنشاء ZIP وملفات حزم Mi_Max ذات الامتدادين .mt1 و.mt_1.
- فك MediaTek logo.bin إلى صور PNG عند دعم تخطيط البكسل مع حفظ ملفات raw.z الأصلية.
- إعادة تجميع logo.bin من صور PNG أو ملفات raw.z.

## حدود الإصدار الأول
هذه نسخة سطح مكتب أولية وليست نقلاً كاملاً لكل خدمات Android. لا تحتوي حالياً على Widgets Android أو تثبيت APK على النظام أو تكامل ADB/Termux أو متجر الإضافات/خدمات الهاتف. بعض صيغ ROM المتقدمة قد تكون قابلة للكشف فقط ولا تدعم إعادة البناء بعد.
