# Notification SSH Controller (Android)

An Android app that listens to device notifications and triggers an SSH command based on user configuration. Users can configure SSH host details and select which apps' notifications should be monitored.

## Design and Theme

- Material 3 DayNight theme with Ocean Professional palette:
  - Primary: #2563EB
  - Secondary/Accent: #F59E0B
  - Error: #EF4444
- Reddit Sans applied across the app via theme typography overrides.
- Subtle gradient window background in light mode; expressive container surfaces with proper state layers.

## Build and Run

Build all modules:
```shell
./gradlew build
```

Install debug build on a connected device/emulator:
```shell
./gradlew :app:installDebug
```

Launch "Notification SSH Controller" on the device.

## Permissions

- Notification Listener permission: Required for listening to notifications. Grant in system settings via the prompt in the app.
- Post Notifications (Android 13+): Optional, only for local status notifications.
- No Internet permission is explicitly declared because SSH library uses sockets; ensure network access is available on the device.

## Security Notes

- Password is stored with AndroidX Security Crypto (AES256_GCM) using EncryptedSharedPreferences.
- No secrets are logged. Error messages are sanitized.
- SSH host key checking is currently disabled (StrictHostKeyChecking=no) for MVP. Do not use with untrusted servers. See TODO in SshClient for hardening steps.
- Consider locking your device; an optional “run only when unlocked” flag is persisted via DataStore.

## Project Structure

- app: Android application module (activities, service, data, ssh).
- utilities, list: Sample library modules used by the app.

## Troubleshooting

- If you see UI text not using Reddit Sans, ensure the bundled font files exist under app/src/main/res/font and that the theme is Theme.NotificationSSH.
- On Android 13+, if you do not see local status notifications, grant the "Allow local notifications" permission from the main screen.
