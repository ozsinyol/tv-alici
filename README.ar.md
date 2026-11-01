<div dir="rtl">

# AirPlay for Android

🌐 [Türkçe](README.tr.md) · [English](README.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português](README.pt.md) · [Italiano](README.it.md) · [Русский](README.ru.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [简体中文](README.zh.md) · **العربية**

[![Build](https://github.com/ozsinyol/tv-alici/actions/workflows/build.yml/badge.svg)](https://github.com/ozsinyol/tv-alici/actions/workflows/build.yml) [![Release](https://img.shields.io/github/v/release/ozsinyol/tv-alici)](https://github.com/ozsinyol/tv-alici/releases/latest) [![License: GPL v3](https://img.shields.io/badge/license-GPL%20v3-blue.svg)](LICENSE) ![Android 6.0+](https://img.shields.io/badge/Android-6.0%2B-3DDC84)

يحوّل جهاز Android (Android TV أو الهاتف أو الجهاز اللوحي أو جهاز الاستقبال) إلى **مستقبِل AirPlay** لأجهزة iPhone وiPad: عكس الشاشة والفيديو والصور وبث الصوت فقط (الموسيقى وSpotify وغيرها) وإرسال الفيديو من Safari دون تثبيت أي شيء.

عند التشغيل يعرض التطبيق رمز QR. امسحه بكاميرا iPhone لفتح جهاز التحكم عبر الويب، أو اختر **TV Alici** (يمكنك تغيير الاسم) من مركز التحكم > عكس الشاشة أو من قائمة AirPlay.

## الميزات

- **عكس الشاشة** (فيديو H.264 وصوت AAC-ELD) مع إعدادات مزامنة الصوت والصورة والذاكرة المؤقتة والتكبير ووضعي الملاءمة/الملء.
- **AirPlay للفيديو** (MP4 وHLS) و**AirPlay للصور**.
- **AirPlay للصوت فقط** (الموسيقى وSpotify والبودكاست) مع شاشة *قيد التشغيل* بملء الشاشة تعرض الغلاف ومعلومات الأغنية.
- **جهاز تحكم عبر الويب** على `http://<عنوان-IP-للجهاز>:8080` (محمي برمز PIN): أرسل الفيديو من Safari دون تثبيت، وضع عدة مقاطع في قائمة انتظار، تشغيل/إيقاف مؤقت/تقديم، مستوى الصوت، عرض رسالة على التلفاز، تشغيل رابط فيديو، وإضافته إلى الشاشة الرئيسية كتطبيق.
- **قائمة على شاشة التلفاز** — اضغط **MENU** (أو **OK** في أجهزة التحكم التي ليس فيها زر MENU مثل Mi TV Stick): التشغيل التلقائي، مؤقت النوم، مزامنة الصوت والصورة، الذاكرة المؤقتة، التكبير، ملاءمة الشاشة، الساعة، الإحصائيات، الأجهزة الموثوقة، التحقق من التحديثات.
- **التحكم في الوصول:** يجب الموافقة على الأجهزة الجديدة على التلفاز (السماح مرة واحدة / دائمًا / الرفض).
- **تحديثات داخل التطبيق** يتم التحقق منها عبر اسم الحزمة والإصدار وشهادة التوقيع.
- **12 لغة** (حسب لغة الجهاز/المتصفح): English وTürkçe وEspañol وFrançais وDeutsch وPortuguês وItaliano وРусский و日本語 و한국어 و简体中文 والعربية.

## البدء السريع

1. ابنِ ملف APK عبر GitHub Actions (ادفع إلى `main` ثم نزّل الملف الناتج `tv-alici-apk`) أو نزّل ملف APK موقّعًا من [Releases](https://github.com/ozsinyol/tv-alici/releases).
2. ثبّته على جهاز Android (`adb install app-release.apk` أو افتح ملف APK على الهاتف/الجهاز اللوحي). يجب أن يكون الجهازان على شبكة Wi-Fi نفسها.
3. افتح التطبيق وامسح رمز QR بكاميرا iPhone، أو اختر «TV Alici» من AirPlay / عكس الشاشة.

## القيود

- يدعم بروتوكولي AirPlay التقليدي (1) وعكس الشاشة فقط — بدون AirPlay 2 (الغرف المتعددة والصوت المخزّن مؤقتًا وSiri/المنزل).
- لا يُشغَّل الفيديو المحمي بـ FairPlay (YouTube وNetflix وApple TV+ وغيرها)، ويظهر محتوى DRM أسود عند العكس.
- عروض الصور الشرائحية غير مدعومة؛ وعكس الشاشة بصيغة H.264 فقط.
- AirPlay بروتوكول مملوك لشركة Apple وبلا ترخيص رسمي؛ هذا المشروع للاستخدام الشخصي/التجريبي.

## الأمان والتحديثات

يتطلب جهاز التحكم عبر الويب رمز PIN الظاهر على التلفاز (يُرسل في ترويسة الطلب؛ خمس محاولات خاطئة تحظر ذلك الجهاز لمدة 5 دقائق). تُوقَّع ملفات APK الخاصة بالإصدارات بمفتاح خاص محفوظ في GitHub Secrets، ولا يثبّت محدّث التطبيق ملف APK إلا إذا تطابقت اسم الحزمة والإصدار وSHA-256 للشهادة.

## التوثيق الكامل

التوثيق الكامل (بالإنجليزية) في [README.md](README.md): البناء والتوقيع، الأجهزة الموثوقة، ضبط المزامنة، واجهة الويب البرمجية، التحديث اليدوي، تصحيح الأخطاء. لإضافة لغة إلى التطبيق انسخ `app/src/main/res/values/strings.xml` إلى `values-<الرمز>/` وأضف كتلة إلى جدول `DIL` في `VideoServer.kt`.

## الترخيص

GNU GPL v3 — انظر [LICENSE](LICENSE). يستخدم المشروع شيفرة `playfair` من [UxPlay](https://github.com/FDH2/UxPlay) (GPL v3) ومفكّك ALAC مفتوح المصدر من Apple (Apple Public Source License 2.0، انظر `app/src/main/cpp/alac/APPLE_LICENSE.txt`).

</div>
