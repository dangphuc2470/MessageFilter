# Privacy Policy for Message Filter & Forwarder

**Effective Date:** October 4, 2026  
**Last Updated:** October 4, 2026

**Message Filter & Forwarder** ("the App", "we", "us", or "our") is designed with a strict **privacy-first, offline architecture**. This Privacy Policy explains how our application handles information on your device.

---

## 1. Summary: Zero Data Collection

- **We do NOT collect, transmit, store on external servers, or sell any personal data.**
- All message processing, notification filtering, OTP extraction, and notification forwarding happen **100% locally on your device**.
- The App requires **NO internet connection** to perform its core functions and contains no tracking SDKs, analytics, or third-party advertising services.

---

## 2. Permissions Used and Why

To perform its functions as a notification organizer and message utility, the App requests the following Android permissions:

### A. Notification Listener Access (`BIND_NOTIFICATION_LISTENER_SERVICE`)
- **Purpose:** Used strictly to read incoming notifications from messaging applications that you have configured in the App.
- **Usage:**
  - Identifies incoming messages to filter out spam and promotional text based on your custom keywords.
  - Automatically detects one-time passwords (OTP) and verification codes, adding a quick one-tap "Copy OTP" action.
  - Generates clean, grouped notifications for apps in Forward mode.
- **Privacy Guarantee:** Notification content is processed in memory on your device. Only messages logged into your local SQLite history (`message_filter.db`) remain on your device, and you can clear this history at any time.

### B. Post Notifications (`POST_NOTIFICATIONS`)
- **Purpose:** Required on Android 13 (API level 33) and above to display clean, formatted notifications with interactive buttons (such as "Copy OTP", "Mark as Read", and "Reply").

### C. Query All Packages (`QUERY_ALL_PACKAGES`)
- **Purpose:** Allows you to browse installed messaging and communication applications on your device so you can assign custom filtering rules (Forward, Filter, Block, or Normal) to specific apps.
- **Privacy Guarantee:** The list of installed packages is only used locally to populate the App Filters screen. It is never transmitted off your device.

---

## 3. Data Storage and Retention

- **Local Storage Only:** Message logs, custom keyword filters, and app preference settings are stored strictly in an encrypted/private SQLite database and `SharedPreferences` within the App's isolated sandbox directory on your device.
- **User Control:** You have full control over your data. You can delete individual log entries or wipe the entire message history directly within the "History & Logs" tab.
- **Uninstalling:** Uninstalling the App immediately removes all stored messages and preferences from your device.

---

## 4. Third-Party Services and Analytics

- The App **does not use** any third-party SDKs, analytics trackers (such as Google Analytics, Firebase, or Facebook SDK), crash reporting tools with network capabilities, or advertising networks.

---

## 5. Security

Because all processing occurs locally without remote server communication, your messages and verification codes are never exposed to transit risks, cloud breaches, or remote interception by third parties.

---

## 6. Children's Privacy

The App does not knowingly collect or solicit personal information from anyone, including children under the age of 13.

---

## 7. Changes to This Privacy Policy

If we make any changes to this Privacy Policy, we will update the "Last Updated" date at the top of this document. Any updates will remain committed to our privacy-first, on-device model.

---

## 8. Contact Information

If you have any questions or feedback regarding this Privacy Policy or the App, please open an issue on GitHub or contact:

- **Developer:** dangphuc2470
- **Email:** dangnguyenhoangphuc1@gmail.com
- **Project Repository:** https://github.com/dangphuc2470/MessageFilter
