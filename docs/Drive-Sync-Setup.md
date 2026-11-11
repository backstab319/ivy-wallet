# Sync & automation (fork features)

This fork adds a **Settings → Sync & automation** screen with:

- **Backup & sync with Google Drive**
- **Automatic entries** from bank SMS and from UPI app notifications

The code lives in `screen/automation`.

## Google Drive: one-time setup for your own build

Google only gives Drive access to apps it knows. An app is identified by its
**package name** and the **SHA-1 of the key it is signed with**, so every build
variant you want to use has to be registered once. No key or secret goes into the code.

1. Open <https://console.cloud.google.com/> and create a project (any name).
2. **APIs & Services → Library →** enable **Google Drive API**.
3. **APIs & Services → OAuth consent screen:** user type **External**, fill in the app name
   and your email. Add the scope `.../auth/drive.file`. While the app is in *Testing*,
   add the Google accounts that will use it under **Test users**.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID →**
   application type **Android**:

   | Build | Package name | SHA-1 |
   |-------|--------------|-------|
   | debug | `com.backstab319.ivywallet.debug` | `./gradlew :app:signingReport` → variant `debug` |
   | release | `com.backstab319.ivywallet` | SHA-1 of your release keystore |

   The debug build is signed with `debug.jks` from this repository, so its SHA-1 is the
   same on every machine. That key is public: use the debug build for yourself, and sign
   anything you give to others with your own key.

Changes in the Google Cloud console can take a few minutes to apply. Until the client is
registered, turning the switch on shows "Couldn't connect to Google Drive" (error 10).

## How the sync works

- The app keeps one file, `ivy-wallet-sync.json`, in your Google Drive. It uses the
  `drive.file` scope, so it can read and write only that file, nothing else in your Drive.
- It syncs when you open the app, shortly after you leave it, after every automatic
  entry, and every 6 hours in the background (only with an internet connection).
- With one device this is an automatic backup. Turn it on with the same Google account on
  a second device and both get the same data: new items, edits and deletions travel in
  both directions. If the same item was edited on two devices between syncs, the copy
  already on Drive wins.
- On the first sync of a device that already has data, accounts and categories with the
  same name as the ones on Drive are joined instead of duplicated.
- A freshly installed or wiped app restores from Drive.
- Google Drive keeps older versions of the file for 30 days (**Drive → right click the
  file → File information → Manage versions**), which is the way back if a wrong change
  was synced.

## Automatic entries

- **From bank SMS** needs the SMS permission. Only messages from sender ids (like
  `VM-HDFCBK`) are read, never texts from phone numbers.
- **From UPI app notifications** needs *Notification access*. Only notifications of the
  apps listed in `PaymentNotificationListener.paymentApps` are read.
- Only money going out is recorded. OTPs, credits, payment requests, failed payments,
  reminders and offers are ignored (`PaymentMessageParser`, covered by
  `PaymentMessageParserTest`).
- When the bank SMS and the UPI app report the same payment, one expense is added: same
  amount and same UPI reference, or same amount within 3 minutes when a reference is
  missing.
- Entries go to the account chosen on the screen, with the payee as title, no category,
  and the original message in the description. A notification tells you what was added.

### "Restricted setting" on Android 13+

For apps installed from an APK file, Android blocks notification access (13+) and the
SMS permission (15+) until you allow it: **Settings → Apps → Ivy Wallet → ⋮ → Allow
restricted settings**. Apps installed with `adb install` or from a store aren't affected.

### Google Play

Google Play allows `RECEIVE_SMS` only for a few app categories and after a declaration
form. If this fork is ever published there, expect to either go through that review or
ship the Play build without the SMS switch (notification access is not restricted).
