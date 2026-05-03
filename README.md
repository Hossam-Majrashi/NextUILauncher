# NextUI Launcher 🚀

<div align="center">

![NextUI](https://img.shields.io/badge/NextUI-v1.0-4285F4)
![Platform](https://img.shields.io/badge/Platform-Android-3DDC84)
![Tech](https://img.shields.io/badge/Built%20with-Jetpack%20Compose-4285F4)
![License](https://img.shields.io/badge/License-MIT-yellow)
![Arabic Support](https://img.shields.io/badge/Arabic-Supported-orange)

**لانشر أندرويد عصري، خفيف، ويركز على الإنتاجية مع أدوات مدمجة ونظام تنظيم ذكي**

**A modern, lightweight, and productivity-focused Android Launcher with integrated tools**

[المميزات](#-المميزات-الرئيسية) • [التثبيت](#-التثبيت) • [المطور](#-المطور) • [English Description](#-english-version)

</div>

---

## 🇸🇦 الواجهة العربية (Arabic)

## 📖 حول المشروع
**NextUI Launcher** هو واجهة مستخدم (Launcher) متطورة لهواتف أندرويد، تم بناؤها بالكامل باستخدام تقنية **Jetpack Compose**. يهدف التطبيق إلى تقليل التشتت وزيادة الإنتاجية من خلال دمج أدواتك اليومية مثل **التقويم الهجري** و **الملاحظات المدمجة** مباشرة في الشاشة الرئيسية، مع نظام ذكي لتنظيم التطبيقات وتصنيفها تلقائياً.

## ✨ المميزات الرئيسية

### 🏠 **الشاشة الرئيسية الذكية**
- **التطبيقات المثبتة**: وصول سريع لتطبيقاتك المفضلة مع نظام **سحب وإفلات (Drag & Drop)** سلس لإعادة الترتيب.
- **التقويم المدمج**: عرض التاريخ **الميلادي والهجري** بتصميم أنيق وعصري.
- **ملاحظات مدمجة**: سجل مهامك وأفكارك مباشرة من الشاشة الرئيسية دون الحاجة لفتح تطبيقات إضافية.
- **مجموعات التطبيقات**: تنظيم الشاشة الرئيسية باستخدام مجلدات ومجموعات مخصصة لتقليل الفوضى.

### 📱 **درج التطبيقات (App Drawer)**
- **تصنيف تلقائي**: يتم تجميع التطبيقات تلقائياً حسب الفئة (أدوات، ألعاب، اجتماعي، إلخ).
- **بحث ذكي وسريع**: ابحث عن تطبيقاتك بسرعة فائقة بالاسم، مع إغلاق تلقائي للوحة المفاتيح عند العودة للرئيسية.
- **سلة المحذوفات**: ميزة فريدة لتتبع التطبيقات التي قمت بحذفها مؤخراً لسهولة العودة إليها في متجر Google Play.
- **دعم إيماءات الهوم**: العودة للصفحة الأولى ومسح نتائج البحث تلقائياً عند الضغط على زر الهوم.

### 🛠️ **الإدارة والخصوصية**
- **إخفاء التطبيقات**: قسم خاص لإدارة وإخفاء التطبيقات الحساسة بعيداً عن المتطفلين.
- **إعدادات متقدمة**: خيارات لتخصيص الواجهة مثل إخفاء تطبيقات المجموعات من القائمة العامة لزيادة التنظيم.
- **الوضع الليلي والنهاري**: دعم كامل ومنسق لمنظومة Material 3 والتبديل التلقائي حسب وضع النظام.

## 🚀 التثبيت

### المتطلبات
- نظام أندرويد 8.0 (SDK 26) أو أحدث.
- برنامج Android Studio Koala أو إصدار أحدث للمطورين.

### الخطوات
1. قم بتحميل المشروع:
   ```bash
   git clone https://github.com/HossamMajrashi/NextUILauncher.git
   ```
2. افتح المشروع في Android Studio.
3. انتظر مزامنة Gradle ثم قم ببناء وتشغيل التطبيق على هاتفك.
4. اختر **NextUI** كواجهة افتراضية (Default Launcher) من إعدادات الهاتف.

---

## 🇺🇸 English Version

## 📖 About
**NextUI Launcher** is a modern Android home screen replacement built entirely with **Jetpack Compose**. It focuses on minimalism and productivity by integrating essential tools like a **Hijri calendar** and **on-screen notes** directly onto your home screen, paired with a smart app categorization system.

## ✨ Key Features

- ✅ **Productive Home**: Pinned apps with smooth drag-and-drop reordering.
- ✅ **Dual Calendar**: Integrated Hijri and Gregorian dates with a beautiful UI.
- ✅ **On-Screen Notes**: Track your thoughts or tasks directly from the home screen.
- ✅ **Smart Drawer**: Automatic app categorization and ultra-fast global search.
- ✅ **Recycle Bin**: Track recently uninstalled apps for quick re-discovery.
- ✅ **Home Button Logic**: Auto-scroll to page 0 and clear search/focus on Home press.
- ✅ **Privacy**: Dedicated section to hide sensitive apps and manage folders.
- ✅ **Material 3**: Modern UI with full Dark/Light mode support and fluid animations.

## 🏗️ Technical Stack

- **Jetpack Compose**: For the modern, declarative UI layer.
- **Kotlin Coroutines & Flow**: For efficient, reactive state management.
- **Room Database**: Local storage for app metadata, pins, folders, and notes.
- **MVVM Architecture**: Clean, maintainable, and scalable code structure.
- **Icon Caching**: High-performance system for loading and caching app icons.

## 📁 Project Structure

```text
NextUILauncher/
├── app/src/main/java/com/nextui/launcher/
│   ├── data/           # Data layer: Repository, Room entities, Icon cache
│   ├── ui/             # UI layer: Compose screens, ViewModels, Themes
│   │   ├── LauncherScreen.kt     # Core UI implementation
│   │   └── LauncherViewModel.kt  # Business logic and state handling
│   ├── MainActivity.kt # Entry point and system event handling
│   └── LauncherApp.kt  # Application initialization
└── README.md
```

---

## 👨‍💻 المطور / Developer

**حسام مجرشي (Hossam Majrashi)**

- **GitHub:** [HossamMajrashi](https://github.com/HossamMajrashi)
- **Email:** Hossam.Majrashi@gmail.com
- **Website:** [My Portfolio](https://hossam-majrashi.github.io/Works/)

---

<div align="center">

**NextUI Launcher - Simplify your Android Experience**

[⭐ Star this repo](https://github.com/HossamMajrashi/NextUILauncher) • [🐛 Report Bug](https://github.com/HossamMajrashi/NextUILauncher/issues)

</div>
