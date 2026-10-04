# Message Filter & Forwarder (Android)

A lightweight, privacy-focused Android utility application built with **Jetpack Compose** and **Material 3** that organizes, filters, and forwards notifications from popular messaging apps with automatic OTP extraction and quick action support.

---

## Key Features

- **Four Flexible Processing Modes Per App:**
  - **Forward (Green):** Intercepts original notifications, parses clean message text, groups conversations, and provides interactive action buttons ("Copy OTP", "Mark as Read", "Reply").
  - **Filter (Blue):** Automatically suppresses spam, promotional, or unwanted notifications matching customizable keywords (e.g., voucher, sale, discount), while keeping normal chat messages intact.
  - **Block (Red):** Silently blocks and dismisses all notifications from selected apps.
  - **Normal / None (White):** Leaves notifications untouched.

- **Intelligent OTP & Verification Code Extraction:**
  - Automatically identifies verification codes and OTPs from SMS and chat messages (supporting Google, banks, WhatsApp, Facebook, TikTok, and standard formats like `G-XXXXXX` or standalone 4–8 digit codes).
  - One-tap "Copy OTP" button directly in the notification shade.

- **Direct Reply & Mark as Read Integration:**
  - Seamlessly forwards Android Wearable and RemoteInput reply intents back to the origin app (WhatsApp, Messenger, SMS, etc.).

- **Privacy-First & Completely Offline:**
  - **Zero cloud communication, zero analytics, zero trackers.**
  - All processing and storage occur 100% on-device inside a local SQLite database (`message_filter.db`).

- **Built-in Message Simulator:**
  - Test custom filter keywords and OTP patterns directly within the app before applying them to real notifications.

---

## Tech Stack & Architecture

- **Language:** Kotlin 2.2+
- **UI Framework:** Jetpack Compose with Material Design 3
- **Architecture:** Unidirectional Data Flow with Kotlin Coroutines & `StateFlow`
- **Core Services:**
  - `NotificationListenerService` (`MessageNotificationListenerService`)
  - `BroadcastReceiver` (`CopyOtpReceiver`, `MarkAsReadReceiver`, `ReplyReceiver`)
- **Persistence:** Local SQLite database via `SQLiteOpenHelper` & encrypted/sandbox `SharedPreferences`
- **Minimum SDK:** Android 7.0 (API 24)
- **Target SDK:** Android 16 (API 36)

---

## Permissions Overview

| Permission | Purpose |
| :--- | :--- |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Required to read and organize incoming notifications based on user rules. |
| `POST_NOTIFICATIONS` | Required on Android 13+ to post forwarded messages and OTP alerts. |
| `QUERY_ALL_PACKAGES` | Allows the user to select which installed messaging apps to manage. |

For detailed information on how data is handled, see the [Privacy Policy](PRIVACY_POLICY.md).

---

## Building from Source

### Prerequisites
- Android Studio Ladybug (or newer)
- JDK 17 or JDK 21
- Android SDK Platform 36

### Build Steps
```bash
# Clone the repository
git clone https://github.com/dangphuc2470/MessageFilter.git
cd MessageFilter

# Build Debug APK
./gradlew assembleDebug

# Output APK location:
# app/build/outputs/apk/debug/app-debug.apk
```

---

## License

This project is licensed under the MIT License - see the LICENSE file for details.
