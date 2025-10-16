# Notification SSH Controller (Android)

An Android app that listens to device notifications and executes a user-defined SSH command. Configure the SSH connection (host, port, username, password, and command template), select which apps to monitor, and optionally show local status notifications on Android 13+.

## Overview

Notification SSH Controller runs a NotificationListenerService that reacts to notifications posted by user‑selected apps. When an event arrives, the service builds a command from your template (with placeholders) and executes it over SSH using a password stored in encrypted storage. The app follows a modern Material 3 expressive dark theme with the Ocean Professional palette and Reddit Sans typography.

## Features

- Notification listener service with per‑app monitoring
- SSH execution using JSch (password auth)
- Secure password storage via AndroidX Security Crypto (EncryptedSharedPreferences)
- Command template with placeholders: ${package}, ${title}, ${text}, ${postTime}
- Test SSH command from the main screen
- Settings UI to manage SSH host/port/username/password/command template
- App selection UI with search and multi‑select
- Optional local status notifications (Android 13+ POST_NOTIFICATIONS)
- Expressive dark theme, Material 3, Reddit Sans font

## Prerequisites

- Android device or emulator with API 30+ (minSdk 30; target/compile 34)
- Java 17 for build environment
- Network access to the target SSH server
- SSH server reachable from the Android device

## Installation and Build

- Build all modules:
```shell
./gradlew build
```

- Install the debug build:
```shell
./gradlew :app:installDebug
```

- Launch the app: Notification SSH Controller

## Grant Notification Access

You must grant notification listener access so the service can receive posted notifications.

Steps:
- Open the app
- In the Permission card, tap Grant access
- In system settings, enable Notification SSH Controller
- Return to the app; the status updates to Access granted

Tip:
- You can also navigate via Settings > Apps > Special app access > Notification access, then enable the app.

## Configure SSH (Settings screen)

Open Settings from the main screen and configure:

- Host: IP or hostname (e.g., 192.168.1.10 or server.local)
- Port: SSH port (default 22)
- Username: Account on the SSH server
- Password: Stored securely via encrypted preferences
- Command template: The command to execute when a monitored app posts a notification

Placeholders supported in the command template:
- ${package}: Notification’s originating package name
- ${title}: Notification title (sanitized to a single line)
- ${text}: Notification text/body (sanitized to a single line)
- ${postTime}: Epoch millis when the notification was posted

Example command template:
```bash
/usr/local/bin/on_notify --pkg "${package}" --title "${title}" --text "${text}" --time ${postTime}
```

Notes:
- Host, port, username, password, and command template must be valid to run on real events.
- The password is never displayed; a hint indicates whether it is saved.

## Select Monitored Apps

- From the main screen, tap Select apps
- Search and multi‑select installed user apps
- Tap Apply to persist the list

Only notifications from selected apps will trigger SSH execution.

## Test SSH Command

- On the main screen, tap Run test
- Behavior:
  - If a password is saved, the app runs the configured command over SSH and shows a brief result.
  - If no password is saved, a safe simulation (FakeSshClient) runs so you can validate UI and flow.

The UI shows the last status and last successful result timestamps.

## How It Works (NotificationListenerService + SSH)

- The service NotifSshListenerService is registered with android.permission.BIND_NOTIFICATION_LISTENER_SERVICE and becomes active after you grant notification access.
- When a notification arrives from a monitored package:
  - The app takes a snapshot of SSH config and password from DataStore and SecureStorage.
  - It builds a single‑line command by replacing ${package}, ${title}, ${text}, ${postTime}.
  - It executes the command via JSch on a background thread with a timeout.
  - It records last status timestamp, and if successful, last result timestamp.
  - If POST_NOTIFICATIONS is granted (Android 13+), it posts a small, sanitized local status notification.

Implementation highlights:
- PreferencesRepository (DataStore Preferences): host, port, username, command template, selected packages, timestamps, behavior flags
- SecureStorage (AndroidX Security Crypto): password (AES256_GCM)
- Ssh client:
  - JSchSshClient: production client (StrictHostKeyChecking=no for MVP)
  - FakeSshClient: safe local simulation used for tests when password is missing

## Permissions

- Notification Listener access: Required. Grant via system settings (the app provides a CTA).
- POST_NOTIFICATIONS (Android 13+): Optional. Used only to show local status notifications summarizing success/failure.
- Network access: No explicit permission is declared for internet in the sample manifest; ensure your build includes the necessary networking capability through platform defaults or add INTERNET permission if required for your environment.

Runtime flow (Android 13+):
- The app presents a button to request POST_NOTIFICATIONS. Denying it only disables local status toasts/notifications; SSH still functions.

## Security Notes

- Password storage:
  - Stored with AndroidX Security Crypto using EncryptedSharedPreferences and a MasterKey (AES256_GCM).
  - Password is never logged or displayed; UI shows only a “saved” hint.
- Sensitive logging:
  - No secrets or full commands are logged. Error messages are sanitized.
- Host key checking:
  - StrictHostKeyChecking is set to "no" in JSchSshClient for MVP convenience.
  - Risk: Vulnerable to man‑in‑the‑middle attacks if an attacker can spoof the SSH server.
  - Recommendation: Use only with trusted networks and servers. For production, manage known_hosts and enable strict host key verification.
- Command safety:
  - Title and text values are sanitized to a single line; basic escaping reduces shell interpolation risk.
  - You are responsible for writing a safe command template appropriate to your server environment.

## Battery/Background Execution Notes

- NotificationListenerService runs when the system dispatches events; it does not run persistently or as a foreground service.
- If SSH execution appears delayed or not triggered:
  - Disable battery optimizations for the app (Manufacturer settings vary).
  - Keep the device unlocked if your workflow or policy “run only when unlocked” is enabled.
- Avoid aggressive task killers and “privacy” optimizers that revoke notification access or background execution privileges.

## Troubleshooting

- I granted access but the app still shows “Access not granted”:
  - Return from system settings and reopen the app. If needed, toggle access off and on again.
  - Ensure no enterprise policy blocks notification listener access.
- Test SSH fails:
  - Verify host/port/username/password are correct and reachable from the device network.
  - Confirm the SSH server accepts password auth and that firewalls allow your device.
  - Increase server logs to inspect failures; the app intentionally shows minimal error text.
- Real events do nothing:
  - Ensure the posting app is selected in “Select apps”.
  - Confirm command template is not blank.
  - Confirm password is saved.
  - Check if POST_NOTIFICATIONS is required for you to see local status notifications (Android 13+). SSH will still execute without it.
- Titles or texts look truncated:
  - The app sanitizes to single lines and truncates local notification bodies to avoid verbosity.
- Font/theme looks off:
  - Ensure Reddit Sans resources exist under app/src/main/res/font and the app theme is Theme.NotificationSSH.

## Known Limitations and Future Improvements

- Host key verification is disabled (StrictHostKeyChecking=no). Future work: manage known_hosts and enable strict verification.
- Password‑only authentication. Future work: support public key authentication with secure key management.
- Minimal placeholder set for templates. Future work: add more structured fields and robust shell quoting options.
- No per‑app command overrides. Future work: allow per‑app templates or filters.

## Credits/License

- SSH: JSch (com.jcraft:jsch:0.1.55)
- Secure storage: AndroidX Security Crypto
- UI: Material 3; Reddit Sans font
- Theme: Ocean Professional (Blue & amber accents)

This project is provided as an example implementation; review and harden security before use in sensitive environments.
