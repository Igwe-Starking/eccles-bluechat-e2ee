# Eccles Bluechat privacy policy

*Applies to Eccles Bluechat 3.1.0 and later. Last updated: October 2026.*

**The short version:** Bluechat does not connect to the internet, has no accounts, and sends your data to nobody except the phone you choose to talk to.

## What stays on your phone

- Your chats and media (photos, voice notes, video), stored **encrypted** on the device.
- Your identity key and the fingerprints of devices you have met.
- Your display name, profile photo and preferences.

Uninstalling the app removes this data. App backup is disabled, so it is not copied to cloud backups.

## What is sent, and to whom

When you connect to another phone over Bluetooth, Bluechat sends it:

- your messages, media and call audio/video, **end-to-end encrypted**,
- your display name and profile photo, so the other person sees who you are,
- your device's Bluetooth name and address, which Bluetooth itself exposes.

Nothing is sent to the developer or to any server. The app does not declare the `INTERNET` permission, so it has no way to.

## What the developer collects

Nothing. There is no analytics, advertising, crash-reporting or tracking code in the app. Ads and billing code present in older versions were removed in 3.1.0.

## Permissions and why

| Permission | Why |
|---|---|
| Bluetooth connect and scan (`neverForLocation`) | Find and talk to nearby devices |
| Location (Android 11 and older only) | Required by those Android versions for Bluetooth scanning. Bluechat does not read your location. |
| Microphone | Voice notes, voice calls and video calls |
| Camera (optional) | Take photos, record video, video calls |
| Notifications | Messages, calls, and quick reply |
| Full-screen intent | Show the incoming-call screen |
| Foreground services | Keep calls and the Bluetooth listener running |
| Biometric | Optional app lock |
| Storage (Android 9 and older only) | Save received files |

## Your choices

You can block devices, go offline from the toolbar, turn on app lock, delete chats, and uninstall at any time.

## Children

Bluechat is a general-purpose messenger and is not directed at children.

## Changes and contact

Changes to this policy appear in this file's history. Questions: open an issue at https://github.com/Igwe-Starking/eccles-bluechat-e2ee/issues.
