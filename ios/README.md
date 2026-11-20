# QuickTv — آيفون وآيباد

## التثبيت على جوالك (بدون حساب مطورين)

1. **ثبّت Xcode** من متجر الماك (مجاني). الأفضل Xcode 26 عشان تصميم Liquid Glass.
2. **ثبّت XcodeGen** (يولّد ملف المشروع): افتح Terminal واكتب:
   ```
   brew install xcodegen
   ```
   (لو ما عندك Homebrew: https://brew.sh)
3. **نزّل المشروع** من GitHub (Code ← Download ZIP) أو:
   ```
   git clone https://github.com/hamzah077/QuickIPTV.git
   ```
4. **ولّد المشروع وافتحه:**
   ```
   cd QuickIPTV/ios
   xcodegen
   open QuickTv.xcodeproj
   ```
5. في Xcode: **Settings ← Accounts** ← أضف حساب Apple حقك.
6. اضغط على المشروع **QuickTv** ← **Signing & Capabilities** ← **Team** اختر "(Personal Team)".
   لو طلع خطأ في Bundle Identifier غيّره لأي اسم فريد مثل `com.اسمك.quicktv`.
7. وصّل الجوال بالكيبل، واختره من فوق، واضغط ▶ (أو ⌘R).
8. أول مرة بالجوال: **الإعدادات ← عام ← VPN وإدارة الجهاز** ← وثّق حسابك.
   وفعّل **وضع المطوّر**: الإعدادات ← الخصوصية والأمان ← وضع المطوّر.

> بدون حساب مطورين التطبيق يشتغل **7 أيام**، بعدها وصّل الجوال واضغط ▶ مرة ثانية.

## ملاحظات
- الأفلام بصيغة MKV ما يشغّلها مشغّل آبل الأساسي — بتنضاف مكتبة مشغّل تدعمها بالمرحلة الجاية.
- البث المباشر يشتغل بصيغة HLS (m3u8).
