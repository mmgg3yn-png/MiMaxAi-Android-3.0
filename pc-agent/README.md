# Mi_Max Ai PC Bridge

هذا البرنامج المساعد يربط تطبيق Mi_Max Ai بكمبيوتر Windows أو Linux على الشبكة المحلية، ويتيح فحوصات ADB/Fastboot من الهاتف.

## التشغيل

1. ثبّت Python 3 على الكمبيوتر.
2. ثبّت Android SDK Platform-Tools، وتأكد أن الأمرين `adb` و`fastboot` يعملان من Terminal/Command Prompt.
3. شغّل:
   ```bash
   python mimax_pc_agent.py
   ```
4. سيعرض البرنامج عنوان الكمبيوتر ورمز اتصال عشوائياً. أدخل العنوان والرمز داخل تطبيق Mi_Max Ai ← إعدادات ← اتصال الكمبيوتر.
5. انسخ ملفات APK أو صور الروم التي تحتاجها إلى مجلد `workspace` بجانب السكربت.

## الأوامر المدعومة

- `adb version`, `adb devices`, `adb get-state`
- `adb shell getprop` أو `adb shell getprop ro.product.device`
- `adb logcat -d -t 500`
- `adb install app.apk` (من مجلد workspace)
- `fastboot devices`
- `fastboot getvar product`, `unlocked`, `current-slot`, `all` وغيرها من القائمة المسموحة
- `fastboot reboot`
- `fastboot flash PARTITION FILE` لقائمة محددة فقط، ويتطلب كتابة FLASH في خانة التأكيد، ووجود جهاز واحد وملف داخل workspace

## الأمان

- لا تستخدمه على شبكة عامة أو شبكة لا تثق بها.
- رمز الاتصال مطلوب لكل طلب.
- لا يقبل أوامر shell عامة ولا ينفذ عبر shell=True.
- ملفات التفليش يجب وضعها داخل workspace، والتفليش يحتاج تأكيداً منفصلاً.
- أوقف البرنامج بعد انتهاء الاستخدام. لا تشارك رمز الاتصال مع أحد.
