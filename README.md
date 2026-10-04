# Message Filter & Forwarder

An open-source, privacy-first Android application designed to eliminate notification overload, suppress unwanted spam, and streamline verification code handling across all messaging platforms. Built with Kotlin, Jetpack Compose, and Material Design 3.

---

## Why Message Filter & Forwarder Exists

Modern Android users rely on multiple communication platforms daily, including WhatsApp, Google Messages (SMS), Zalo, Telegram, Facebook Messenger, and Viber. However, this multi-app ecosystem introduces several persistent pain points:

1. **Notification Clutter and Marketing Spam:**  
   Service providers, brand channels, and groups frequently push promotional alerts, discount codes, vouchers, and automated bot messages that bury critical personal and work messages.

2. **The Friction of One-Time Passwords (OTPs):**  
   Receiving authentication codes (from Google, banks, or two-factor authentication services) requires either opening the SMS app, memorizing numbers, or attempting to highlight tiny numbers inside a dense notification body. Many apps do not provide a dedicated "Copy Code" action in their notification tray.

3. **Wearable and Smartwatch Redundancy:**  
   Smartwatches and fitness bands (Garmin, Wear OS, Amazfit) mirror every notification received on the phone. Background system tasks, backup reminders ("WhatsApp Web is active", "Checking for new messages"), and duplicate alerts trigger continuous unnecessary vibrations on wrists.

4. **Group Chat Backlog Flooding (The "Old Message Wall" on Wearables):**  
   When a user has unread messages in an active group chat (such as WhatsApp, Telegram, or Zalo), the origin application bundles the entire accumulated backlog of unread messages into every subsequent notification update. Because smartwatches (Garmin, Wear OS, Amazfit) and lock screens have strict display height and character limits, they frequently render notifications starting from the top of the text block. As a result, every single incoming message causes the smartwatch to vibrate only to show the oldest unread messages repeatedly, while the actual newest message remains truncated or pushed completely out of view.

5. **Privacy Concerns with Cloud-Based Filters:**  
   Most third-party notification organizers or SMS managers route messages through external cloud servers to parse text or apply machine learning models, posing severe security risks to sensitive communications and financial OTPs.

**Message Filter & Forwarder was created to solve these challenges with an absolute guarantee of local, on-device processing and granular per-application control.**

---

## Core Features

### 1. Four Operating Modes per Application
Each messaging app installed on the device can be independently assigned one of four distinct processing modes:
- **Forward (Green):** Intercepts notifications, formats clean message headers, groups conversations, and injects actionable buttons (Copy OTP, Mark as Read, Direct Reply).
- **Filter (Blue):** Continuously monitors incoming notifications against a customizable blacklist of spam/promotional keywords (e.g., voucher, sale, promotion, discount). Clean conversations pass through untouched, while matching spam notifications are silently dismissed.
- **Block (Red):** Silently intercepts and dismisses all notifications from noisy or non-essential applications.
- **Normal (White):** Leaves application notifications completely unmanaged, allowing standard system delivery.

### 2. Intelligent OTP & Verification Code Extraction
- Automatically parses incoming message text for verification codes, login tokens, and one-time passwords across multiple languages (English and Vietnamese).
- Supports prefixed codes (such as Google's `G-XXXXXX`, Facebook's `FB-XXXXX`, bank transaction codes), explicit assignment patterns (`Code is 123456`, `Mã xác minh là 123456`), and standalone numeric sequences.
- Attaches an interactive **"Copy [CODE]"** button directly into the notification banner, allowing one-tap clipboard copying without opening the app or navigating away from the current screen.

### 3. Smartwatch-Optimized Group Chat Parsing (Latest-Message Isolation)
- Solves the notorious "old message wall" problem on smartwatches and lock screens.
- Inspects underlying `MessagingStyle` and `android.messages` parcel bundles to isolate strictly the single latest incoming message, attributing the active sender cleanly (`Sender: Message`).
- Prevents smartwatches (Garmin, Wear OS, Amazfit) from rendering repeated backlogs of stale text, ensuring that when your wrist vibrates, you always see the actual newest message immediately at the top.

### 4. On-Device AI Group Notification Digest (SmolLM2-135M via llama.cpp)
- **Eliminates Wrist Notification Bombing:** When busy group chats erupt into dozens of rapid messages, the debouncer buffers incoming chatter over a customizable window (e.g., 20 to 60 seconds).
- **Direct Mention Awareness:** Scans for user nicknames (e.g., `@Name`, `Name ơi`, `anh Name`). If a message directly tags or calls the user, the debouncer is immediately bypassed to deliver an urgent, high-priority alert without delay.
- **100% Offline Local Summarization:** Runs a quantized `SmolLM2-135M-Instruct-Q4_K_M.gguf` model (~100MB) locally on device CPU/NEON using native `llama.cpp` (`org.codeshipping:llama-kotlin-android`).
- **RAM Protection via Dynamic Auto-Unload:** Automatically unloads the model from RAM after 2 minutes of idle inactivity, keeping memory consumption minimal and preventing Android Low Memory Killer (LMK) eviction on devices with 4GB to 6GB RAM (such as Snapdragon 720G / Redmi Note 9S).
- **Silent Digest Delivery:** Replaces dozens of disruptive wrist vibrations with a single consolidated summary notification (`[Tóm tắt AI] Group Name (X messages)`).

### 5. Integrated Action Forwarding (Mark as Read & Direct Reply)
- Preserves native messaging capabilities by extracting standard, wearable, and invisible actions from original notifications.
- Users can reply directly from the forwarded notification banner using Android `RemoteInput`, or dismiss the alert while triggering "Mark as Read" in the origin application.

### 6. Built-in Notification History & Search
- Logs processed notifications into a local database for review.
- Filter history entries by status (All, Forward, AI Digest, Filter, Block, Normal) or search by sender and message content.
- Individual messages or the entire history can be purged at any time.

### 7. Interactive In-App Simulator & AI Testbench
- Includes a built-in message simulator to test custom blacklist keywords, OTP extraction patterns, and live on-device AI group chat summarization against simulated alerts before applying them to real conversations.

---

## Privacy Architecture: 100% On-Device

Message Filter & Forwarder is engineered with a strict zero-data-collection philosophy:

- **No Remote Servers:** The application has no networking layer, no backend API, and no cloud synchronization.
- **No Third-Party SDKs:** No analytics frameworks (e.g., Google Analytics, Firebase), crash reporters with network access, or advertising libraries are included.
- **Local Persistence Only:** All configuration settings, keyword lists, and notification logs are stored inside private app storage using Android `SharedPreferences` and an offline SQLite database (`message_filter.db`).
- **Full Transparency:** A prominent disclosure is presented prior to requesting the Android Notification Listener permission, clearly detailing that notifications are processed locally in memory.

For full legal and compliance details, refer to [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

---

## Technical Architecture

- **Language:** Kotlin 2.2+
- **User Interface:** Jetpack Compose with Material Design 3 and Dynamic Color
- **State Management:** Unidirectional Data Flow (UDF) powered by Kotlin Coroutines and `StateFlow`
- **Background Processing:**
  - `MessageNotificationListenerService`: Subclass of Android `NotificationListenerService` responsible for listening, filtering, and delegating incoming notifications.
  - `NotificationHelper`: Handles notification channel configuration, conversation grouping, avatar rendering, and building `NotificationCompat` builders.
  - `OtpExtractor`: RegEx-based token recognition engine with year-exclusion heuristics (ignoring numbers starting with 202x or 199x).
  - `AiSummarizerManager`: On-demand lifecycle manager for `SmolLM2-135M-Instruct-Q4_K_M.gguf` using `llama-kotlin-android` (native `llama.cpp` C++17 arm64/x86_64).
  - `GroupNotificationDebouncer`: Sliding-window burst message collector with user mention detection and automatic debouncing.
  - Broadcast Receivers (`CopyOtpReceiver`, `MarkAsReadReceiver`, `ReplyReceiver`): Intercept action button clicks and dispatch corresponding system or clipboard tasks.
- **Local Storage:** SQLite (`AppDatabaseHelper`) with parameterized queries and index-optimized schema.
- **System Compatibility:** Android 7.0 (API Level 24) to Android 16 (API Level 36).

---

## Permissions

| Permission | Technical Requirement |
| :--- | :--- |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Required by the operating system for the service to intercept incoming notifications. |
| `POST_NOTIFICATIONS` | Required on Android 13 (API 33) and above to present forwarded notifications with action buttons. |
| `QUERY_ALL_PACKAGES` | Used locally to enumerate installed applications so users can configure rules on their installed messaging clients. |
| `INTERNET` | Optional; utilized strictly upon user initiation to download offline GGUF model weights (~100MB) from Hugging Face CDN. Zero notification or message data is ever transmitted. |

---

## Building and Installation

### Prerequisites
- Android Studio Ladybug (2024.2.1) or newer
- JDK 17 or JDK 21
- Android SDK Platform 36

### Build Steps
```bash
# Clone the repository
git clone https://github.com/dangphuc2470/MessageFilter.git
cd MessageFilter

# Compile and package debug APK
./gradlew assembleDebug

# Output APK path:
# app/build/outputs/apk/debug/app-debug.apk
```

### Installing via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
