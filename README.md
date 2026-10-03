# @amrshbib/react-native-pushbib

Self-hosted push notifications for React Native — three lines of setup and
**zero Firebase configuration inside your app**:

- ❌ no `google-services.json`
- ❌ no `GoogleService-Info.plist`
- ❌ no google-services gradle plugin
- ❌ no Firebase pods on iOS (pure APNs)
- ❌ no AppDelegate / MainApplication changes

```js
import Pushbib from '@amrshbib/react-native-pushbib';

Pushbib.initialize({
  baseUrl: 'https://push.your-server.com',
  appId: 'PROJECT_ID', // Dashboard → App Settings
  appKey: 'nk_xxxxxxxx', // Dashboard → App Settings → Secure Keys (kind: mobile)
});

Pushbib.Notifications.requestPermission();
Pushbib.login('user-42');
Pushbib.User.addTags({ plan: 'pro', city: 'DXB' });

Pushbib.Notifications.addEventListener('click', (e) => {
  console.log('clicked', e.notification, e.result.actionId, e.result.url);
});
```

## How it works

- **Android** — the SDK bundles `firebase-messaging` internally and calls
  `FirebaseApp.initializeApp(context, options)` **at runtime** with client
  options fetched from your server (`GET /api/mobile/v1/config`). Those
  options are read straight out of Firebase by your server using the
  service-account JSON — no `google-services.json` anywhere, not in the app
  and not in the dashboard.
  The backend sends **data-only** messages to SDK devices and the SDK renders
  every notification natively — which is what makes action buttons, images,
  foreground control and consistent click handling work.
- **iOS** — no Firebase at all. The SDK registers directly with APNs, sends
  the raw hex device token to your server, and the server delivers over
  HTTP/2 APNs with the `.p8` key you uploaded in the dashboard. AppDelegate
  callbacks and the `UNUserNotificationCenter` delegate are hooked by
  runtime swizzling (installed before `didFinishLaunching`, forwarding to any
  handler your app installs) — which is also why the iOS bridge is
  Objective-C: swizzling needs `+load` and the ObjC runtime.

## Install

```bash
npm install @amrshbib/react-native-pushbib
# or: yarn add @amrshbib/react-native-pushbib

cd ios && pod install
```

Autolinking does the rest. **Requirements:** React Native ≥ 0.71 (old + new
architecture via interop), Android minSdk 24, iOS 13+.

### One-time dashboard setup (per app)

1. **FCM service account** (sending, Android): App Settings → upload the
   service-account JSON.
2. **Android client config**: App Settings → "Detect from service account".
   The server reads the client identifiers from Firebase itself.
3. **APNs key** (sending, iOS): App Settings → upload the `.p8` key with Key
   ID / Team ID / Bundle ID.
4. Create a **mobile Secure Key** (optionally locked to your bundle ids) —
   that's the `appKey`.

### The only platform config you can't avoid

- **iOS**: enable the *Push Notifications* capability (Xcode → Signing &
  Capabilities). That's an entitlement Apple requires per app; no SDK can
  inject it. Recommended: also enable *Background Modes → Remote
  notifications*.
- **Android**: nothing.

### Images in notifications (iOS only — Android needs nothing)

Android draws the picture itself. iOS does not: the system only attaches an image if the app
ships a *Notification Service Extension*, and Apple requires that to be its own target. The
SDK ships the whole extension — code, Info.plist and all. **Your app owns no file.**

**Xcode → File → New → Target → Notification Service Extension.** Name it
`PushbibNotificationService` and decline the "activate scheme" prompt so Run still launches
the app. Then, in the new target's Build Settings:

1. **Delete the `NotificationService.h` / `.m` and the `Info.plist` Xcode generated** —
   including from the target's *Compile Sources*. You need none of them.
2. Set **Info.plist File** to
   `$(SRCROOT)/../node_modules/@amrshbib/react-native-pushbib/ios/NotificationService/Info.plist`
3. Drag `node_modules/@amrshbib/react-native-pushbib/ios/NotificationService/PushbibNotificationService.m`
   into the target's *Compile Sources* (uncheck "Copy items if needed" — reference it in place).

There is no Podfile entry and no `pod install`: the target compiles that one file and links
`UserNotifications`, nothing else. Upgrading the SDK upgrades the extension on the next
build, because the app is pointing at the SDK rather than holding a copy.

That's it. The extension reads the image URL from `imageUrl` (a direct APNs push) or
`fcm_options.image` (one routed through FCM), downloads it, and attaches it. If the download
fails, times out, or the type is one iOS cannot attach, the notification still arrives with
its title and body — the picture is never worth losing the message over.

Two things that silently cost you the picture:

- **The URL must be `https`.** App Transport Security blocks plain `http` and reports nothing,
  so an image served from `http://localhost:5001` will never appear on a real device. The
  extension logs `notification image skipped, iOS only loads https` when it sees one.
- **The extension needs its own bundle id** under your app's, e.g.
  `com.yourcompany.app.PushbibNotificationService`, with a matching provisioning profile.
  Xcode does this for you when you create the target; it only bites on manual signing.

To change what it does, subclass `PushbibNotificationService`, override `willDeliverContent:`
or `imageURLKeys`, and name your subclass as the principal class instead.

## API

| Call | What it does |
| --- | --- |
| `Pushbib.initialize(opts)` | Boot the SDK: fetch config, mint the push token, register the device. All other calls queue behind it. |
| `Pushbib.login(externalId)` | Bind this install to your user id (`/auth/link`). Survives token rotation. |
| `Pushbib.logout()` | Unbind (`/auth/unlink`). |
| `Pushbib.getMe()` | Server-side view of the logged-in user (all subscriptions + tags). |
| `Pushbib.User.addTag(k, v)` / `addTags({...})` | Merge tags (user-level when logged in, else this install). `sendTag` / `sendTags` are aliases. |
| `Pushbib.User.addTag(k, [a, b])` | A tag value may be a list. |
| `Pushbib.User.removeTag(k)` / `removeTags([k])` | Delete tag keys. |
| `Pushbib.User.removeTag(k, [a])` | Drop items from a list, keeping the rest. |
| `Pushbib.User.getTags()` | Local cache of tags set through this install (a promise; `getTagsSync()` is the old synchronous read). |
| `Pushbib.User.setLanguage(code)` | Override the device locale used for targeting. Sent with every register/link and pushed with `/devices/profile`. |
| `Pushbib.User.getLanguage()` | The override, or the device locale when none was set. |
| `Pushbib.User.getExternalId()` | Current external id (or null). |
| `Pushbib.User.pushSubscription.getId()` | Stable subscription id (treat as a secret). `getIdAsync()` is the same call. |
| `Pushbib.User.pushSubscription.getToken()` | Raw push token (FCM registration token / APNs hex). |
| `Pushbib.User.pushSubscription.getOptedIn()` | Whether this device is subscribed. `getOptedInAsync()` is the same call. |
| `Pushbib.User.pushSubscription.optOut()` / `optIn()` | Server-side unsubscribe / resubscribe. |
| `Pushbib.User.pushSubscription.addEventListener('change', fn)` | Fires with `{ id, token, optedIn }` on register, token rotation and opt in/out. |
| `Pushbib.User.addEventListener('change', fn)` | Fires with `{ current: { externalId, subscriptionId } }` on login / logout. |
| `Pushbib.Location.setLocation(lat, long)` | Report coordinates for radius targeting. Shown as "Location point" on the user record. |
| `Pushbib.Location.getLocation()` | Last coordinates reported from this install (or null). |
| `Pushbib.Location.clearLocation()` | Forget them — the device stops matching radius segments. |
| `Pushbib.Location.setShared(bool)` / `isShared()` | Off stops `setLocation()` from sending and drops the point from the register payload. |
| `Pushbib.Location.requestPermission()` | Android only: ask the OS for location permission. |
| `Pushbib.Notifications.requestPermission(fallbackToSettings?)` | OS prompt (iOS, Android 13+). Resolves granted. With `true`, a second call opens the app's settings page, because the OS prompt only ever shows once. |
| `Pushbib.Notifications.hasPermission()` / `getPermissionAsync()` | Current permission state. |
| `Pushbib.Notifications.canRequestPermission()` | Whether the OS prompt can still appear. |
| `Pushbib.Notifications.permissionNative()` | Native permission state as a number: 0 not determined, 1 denied, 2 authorized, 3 provisional, 4 ephemeral. |
| `Pushbib.Notifications.removeNotification(id)` / `removeGroupedNotifications(group)` | Clear one notification, or a whole group. |
| `Pushbib.Notifications.clearAll()` | Clear delivered notifications (+ badge on iOS). |
| `Pushbib.Notifications.setForegroundDisplayEnabled(bool)` / `getForegroundDisplayEnabled()` | Device-local toggle: show pushes while the app is open. Default: true. |
| `Pushbib.Notifications.setBackgroundDisplayEnabled(bool)` / `getBackgroundDisplayEnabled()` | Device-local toggle: show pushes while the app is backgrounded (Android only). Default: true. |
| `Pushbib.Notifications.setLiveUpdateDisplayEnabled(bool)` / `getLiveUpdateDisplayEnabled()` | Device-local toggle: render live updates (Android only). Off reports `outcome: 'local-display-off'`. Default: true. |
| `Pushbib.Debug.setLogLevel(level)` | `'verbose'` or OneSignal's `LogLevel.Verbose`. `setAlertLevel` is accepted and ignored. |
| `Pushbib.LiveActivities.register({ activityId })` | Declare an activity live so the server can address it. On iOS also takes `activityPushToken` / `pushToStartToken` / `attributesType`. |
| `Pushbib.LiveActivities.end(activityId?)` | End one activity, or every live one for this device when called with no id. |
| `Pushbib.LiveActivities.getSessions()` | What the server still thinks is live for this device. |
| `Pushbib.LiveActivities.addEventListener('update', fn)` | Android only: every live update, with `displayed` and `outcome`. |
| `Pushbib.LiveActivities.enter(id, token?)` / `exit(id)` | OneSignal's names for `register` / `end`. These resolve to `null` on failure instead of rejecting. |
| `Pushbib.LiveActivities.setPushToStartToken(type, token)` / `removePushToStartToken(type)` | OneSignal's names for the push-to-start token calls. |
| `Pushbib.Channels.sync()` | Re-read the app's channels from `/config` and apply them. Runs on start already. |
| `Pushbib.Channels.list()` | Channels on this phone with their live importance and a `blocked` flag (Android only). |
| `Pushbib.Channels.definitions()` | What the server last sent, from the local cache. |
| `Pushbib.Channels.openSettings(key)` | Open the OS settings screen for one channel. |
| `Pushbib.setLogLevel('verbose')` | none / error / warn / info / verbose. |

### Location & radius targeting

Report where the device is and the dashboard can target it by radius —
"everyone within 5 km of the store". The coordinates land on the user record as
**Location point** (Audience → Users), and the segment builder gets a
**Location → is within radius of** filter taking latitude, longitude and a
radius in **metres**.

The SDK deliberately never reads GPS and never asks for the location
permission: your app already owns that decision (and the App Store / Play
justification for it), so it hands us a fix whenever it has one.

```js
import Geolocation from '@react-native-community/geolocation'; // or expo-location

Geolocation.getCurrentPosition(({ coords }) => {
  Pushbib.Location.setLocation(coords.latitude, coords.longitude);
});

await Pushbib.Location.getLocation();   // { lat, long } | null
await Pushbib.Location.clearLocation(); // user turned location sharing off
```

The value is cached on-device and replayed on every re-registration, so it
survives push-token rotation and reinstalls. Call `setLocation` again whenever
you have a newer fix — only the latest one is kept, and there is no background
tracking: a device that never reports coordinates simply never matches a radius
segment.

### Events

```js
// Tap on a notification or an action button — fires in every app state,
// including cold start (buffered until your listener registers).
Pushbib.Notifications.addEventListener('click', (e) => {
  e.notification; // { title, body, url, buttons, data, ... }
  e.result.actionId; // button id, or null for a body tap
  e.result.url; // data.url if the campaign set one
});

// Every incoming push, whether it was displayed or not — for logging,
// analytics, badge math. Buffered until your listener registers.
Pushbib.Notifications.addEventListener('received', (e) => {
  console.log('push received', e.notification.title, e.notification.data);
});

// Decide whether a push shows while the app is foregrounded.
// Default (no listener / no preventDefault): it shows.
Pushbib.Notifications.addEventListener('foregroundWillDisplay', (e) => {
  if (e.notification.data.silent === '1') e.preventDefault(); // synchronous!
});

Pushbib.Notifications.addEventListener('permissionChange', (granted) => {});
Pushbib.User.pushSubscription.addEventListener('change', ({ id, token, optedIn }) => {});
```

`data.url` from a campaign is opened automatically on click via `Linking`
(deep links included). Disable with `initialize({ autoOpenLaunchUrls: false })`.

### Navigate to a screen on click

The `click` event is the navigation hook — it fires when the app is in the
foreground, background, **and** on cold start (the tap that launched the app
is buffered until your listener registers, so register it early):

```js
// e.g. with React Navigation and a navigationRef
Pushbib.Notifications.addEventListener('click', (e) => {
  const { screen, ...params } = e.notification.data; // set in the campaign's data
  if (screen) navigationRef.current?.navigate(screen, params);
});
```

Send the campaign with data `{ "screen": "OrderDetails", "orderId": "42" }`
and set `autoOpenLaunchUrls: false` if you route everything yourself.

### Per-notification display control (showForeground / showBackground)

Add these keys to the notification's **data** payload when sending; the SDK
enforces them natively (default: both `true`):

| Data | Effect |
| --- | --- |
| `"showForeground": false` | Not displayed while the app is open (`foregroundWillDisplay` is skipped entirely) — the `received` listener still fires. |
| `"showBackground": false` | Android: not displayed in background/killed — delivery is silent, `received` is buffered for the next app session. iOS: not enforceable client-side (Apple displays background alerts; needs a Notification Service Extension — planned). |
| both `false` | A silent data push: nothing shows anywhere (Android), your `received` listener gets the payload. |

The `received` event **always** fires regardless of these flags — that's the
"catch and log it" hook. On iOS it fires in the foreground only (background
alert pushes never wake the app without an NSE).

### Device-local display toggles (foreground / background)

`showForeground` / `showBackground` above are set **per message by the sender**.
For a setting the *user* controls on their own device — "show me banners while
I'm using the app" vs. "while it's closed" — use the display toggles. They're
persisted natively and honored on every incoming push, even in a cold process:

```js
await Pushbib.Notifications.setForegroundDisplayEnabled(false); // silent while open
await Pushbib.Notifications.setBackgroundDisplayEnabled(false); // silent while closed (Android)
await Pushbib.Notifications.setLiveUpdateDisplayEnabled(false); // no live-update cards (Android)
await Pushbib.Notifications.getForegroundDisplayEnabled(); // → boolean
```

There is a third toggle for the lock-screen cards: `setLiveUpdateDisplayEnabled(false)`
stops live updates from rendering while ordinary notifications keep showing — worth
its own switch, since a delivery tracker is the kind of thing a user mutes on its own.
The `update` event still fires, reporting `outcome: 'local-display-off'`.

The device stays **subscribed** either way (unlike `optOut()`): the server keeps
targeting it and the `received` event keeps firing — only the visible banner is
suppressed. `setBackgroundDisplayEnabled` is **Android-only**: on iOS the OS
renders background alerts before the app runs, so it can't be honored
client-side without a Notification Service Extension.

## Live Activities (iOS) & Live Updates (Android)

The lock-screen card that tracks something in progress — a delivery, a ride, a
match score. Your **backend** drives it (`POST /api/server/v1/live-activity/…`);
the SDK's job is the device half.

**How much the SDK can do differs sharply by platform, and it's worth knowing why
before you plan the work.**

### Android — the SDK does everything

Live updates arrive on the device's normal FCM token and the native service
renders an ongoing notification, replacing it in place on every update. Nothing to
set up.

```js
// Optional: declare that an activity is live so the server can address it.
// You can skip this entirely and let your backend's /live-activity/start do it.
await Pushbib.LiveActivities.register({ activityId: 'order_12345' });

// Mirror the state in-app, or draw your own richer card from contentState.
// `displayed` / `outcome` tell you whether it actually reached the tray.
Pushbib.LiveActivities.addEventListener('update', ({ liveUpdate, displayed, outcome }) => {
  console.log(liveUpdate.action, liveUpdate.contentState); // START | UPDATE | END
  if (!displayed) console.warn('live update not shown:', outcome);
});

await Pushbib.LiveActivities.end('order_12345'); // user dismissed it locally
```

⚠️ **`POST_NOTIFICATIONS` must be granted, or nothing renders.** From Android 13
it is a runtime permission, and until the user grants it every live update is
dropped on arrival while your server still reports it as delivered. Call
`Pushbib.Notifications.requestPermission()` at startup. The `update` event then
reports `outcome: 'notifications-disabled'` rather than failing silently.

The ongoing notification is drawn from a small set of conventional keys, because
Android can't know how to render an app-defined struct:

| Shown | Read from |
|---|---|
| Title | `alert.title` → `contentState.title` → app name |
| Body | `alert.body` → `contentState.body` / `.message` / `.status` |
| Progress bar | `contentState.progress` (0-100), or `.indeterminate: true` |

Everything else is still available in full through the `update` event. Live
updates post to their own `pushbib_live_updates` channel (default importance, no
badge) so users can silence them separately from regular notifications — name it
with `com.amrshbib.pushbib.live_update_channel_name`.

Updates are silent unless the sender includes an `alert`, and an out-of-order
delivery is dropped natively as well as server-side — FCM makes no ordering
guarantee, so two updates sent moments apart can arrive reversed.

Out-of-order protection compares sequence numbers **within one numbering stream**
(`stream_id`: the server session, or `direct` for a session-less test send). It has
to: a session numbers its updates `1, 2, 3…` and restarts at `1` for every new
session, while a direct test send numbers by clock (`~1.78e9`). Comparing across
those would make one test send black-hole every later real update for that activity
id. A change of stream resets the mark instead of comparing against it.

#### "The dashboard says delivered but I see nothing"

Every way an update can fail to reach the screen is reported as `outcome` on the
`update` event (and logged under the `Pushbib` tag):

| `outcome` | Meaning |
|---|---|
| `shown` / `ended` | It reached the tray |
| `notifications-disabled` | `POST_NOTIFICATIONS` not granted, or the user turned notifications off — **by far the most common cause** |
| `stale-sequence` | Older than the last update shown for this activity in the same stream |
| `opted-out` | The device opted out of push entirely |
| `local-display-off` | `setLiveUpdateDisplayEnabled(false)` on this device |
| `notify-blocked` | The OS refused the `notify()` call |
| `render-error` | Building the notification threw |

```sh
adb logcat -s Pushbib:V   # the same reasons, from a terminal
```

### iOS — you own the activity, the SDK owns the server contract

**The SDK cannot start or render a Live Activity, and no library can.**
`ActivityAttributes` and its SwiftUI `ActivityConfiguration` are types that must
be declared in *your* Widget Extension, and `Activity<Attributes>` is generic over
them. There is no API by which a package could do this for you.

What the SDK does own is the server contract. Your Swift observes the tokens
ActivityKit hands you and reports them; registration, retry and the HTTP call are
handled:

First make the bridge visible to Swift. With the default React Native setup
(static libraries), add to your app's Objective-C bridging header:

```objc
#import "PushbibLiveActivities.h"
```

With `use_frameworks!`, `import react_native_pushbib` in the Swift file instead.

```swift
import ActivityKit

// 1. Start the activity — your attributes, your widget
let activity = try Activity<DeliveryAttributes>.request(
  attributes: DeliveryAttributes(orderId: "12345"),
  content: .init(state: .init(status: "Preparing", eta: 25), staleDate: nil),
  pushType: .token)

// 2. Report its token. Fires again on rotation, so keep observing.
Task {
  for await tokenData in activity.pushTokenUpdates {
    PushbibLiveActivities.reportActivityToken(
      PushbibLiveActivities.hexString(from: tokenData),
      forActivity: "order_12345",
      attributesType: "DeliveryAttributes")
  }
}

// 3. iOS 17.2+ — report ONCE at launch so your backend can start activities
//    while the app isn't running (POST /live-activity/start).
Task {
  for await tokenData in Activity<DeliveryAttributes>.pushToStartTokenUpdates {
    PushbibLiveActivities.reportPushToStartToken(
      PushbibLiveActivities.hexString(from: tokenData),
      attributesType: "DeliveryAttributes")
  }
}
```

Reports are queued until JS is ready, so a token observed during app launch is
never lost. Add `NSSupportsLiveActivities: true` to your `Info.plist`.

You don't need to call `register()` on iOS — reported tokens register themselves.
It's there if you'd rather pass a token in from JS.

There is no `update` event on iOS: ActivityKit updates the widget directly and
your app process is never woken.

### Reconciling after a cold start

iOS can restore activities your process knows nothing about. Ask the server what
it still thinks is live, then re-report tokens or end what's stale:

```js
const sessions = await Pushbib.LiveActivities.getSessions();
// [{ activity_id, status, activity_token_registered, last_sequence_number, … }]
```

## Android customization (optional)

`AndroidManifest.xml` meta-data, all optional:

```xml
<meta-data android:name="com.amrshbib.pushbib.default_notification_icon"
           android:resource="@drawable/ic_stat_notify" />
<meta-data android:name="com.amrshbib.pushbib.default_notification_color"
           android:value="#2E7DF6" />
<meta-data android:name="com.amrshbib.pushbib.default_channel_name"
           android:value="Notifications" />
```

Notifications post to a high-importance `pushbib_default` channel unless the
campaign names one — see below.

## Notification channels

Channels are defined server-side (dashboard → **App Settings → Channels**, the
third section beside Project and Team) and created on the phone by the SDK. One
channel holds the Android side (importance, sound, vibration, lights,
lock-screen visibility, badge, DND bypass, grouping) and the iOS side (sound,
interruption level, relevance score, thread id, critical alerts). An app
normally runs several at once.

`initialize()` fetches them from `/config` and creates them before the first push
can land. A channel created later still works on its first send: the definition
travels inside the push and the SDK creates the channel on the spot.

```js
await Pushbib.Channels.sync();
const onDevice = await Pushbib.Channels.list();      // [{ id, name, importance, blocked, showBadge }]
const muted = onDevice.filter((c) => c.blocked);
await Pushbib.Channels.openSettings('orders');       // wire this to your own settings screen
```

Importance has four rungs, and sound, vibration and lights are each **off**,
**default** or **custom**:

| Field | Values | On the phone |
| --- | --- | --- |
| `importance` | `low` | Silent, no banner (`IMPORTANCE_LOW`) |
| | `medium` | Makes a sound (`IMPORTANCE_DEFAULT`) |
| | `high` | Sound + heads-up banner (`IMPORTANCE_HIGH`) |
| | `urgent` | `IMPORTANCE_MAX` — the same threshold as `high` |
| `sound` | `{ mode: 'off' }` | No sound |
| | `{ mode: 'default' }` | The phone's default notification tone |
| | `{ mode: 'custom', name: 'order_ding' }` | `res/raw/order_ding` |
| `vibration` | `{ mode: 'off' }` | No vibration |
| | `{ mode: 'default' }` | Vibrates, OS pattern |
| | `{ mode: 'custom', pattern: [0, 250, 250, 250] }` | That pattern, in ms |
| `led` | `{ mode: 'off' }` | No light |
| | `{ mode: 'default' }` | Blinks, OS colour |
| | `{ mode: 'custom', color: '#2E7DF7' }` | Blinks in that colour |

`urgent` and `high` are indistinguishable on the device — Android clamps
`IMPORTANCE_MAX` down — so `Channels.list()` reports `4` for both.

**Sounds ship inside the app** — sound mode `custom` names a file, it never
downloads one:

| Platform | Where the file goes | What `sound.name` holds |
| --- | --- | --- |
| Android | `android/app/src/main/res/raw/order_ding.mp3` | `order_ding` — no extension, `[a-z0-9_]` |
| iOS | the app bundle, `order_ding.caf` | `order_ding.caf` — with extension, ≤ 30 s |

A name that is not bundled falls back to the default tone and logs a warning, so
a missing file never means a silent push.

Android channels are immutable after creation, so editing the sound or importance
server-side creates `nf_<key>_v<n+1>` and deletes `nf_<key>_v<n>` on the next
sync — a user who muted the old one starts hearing the new one. If your app
already creates its own channels, set **App-owned channel id** on the channel and
the SDK sends that id through without creating anything — but note that a pinned
id never changes, so a channel that carries one keeps whatever sound, importance
and vibration the phone created it with, no matter what the dashboard says.

## Drop-in beside OneSignal

The call names match `react-native-onesignal`, so one wrapper can drive both SDKs with no
per-provider branches:

| OneSignal | Pushbib |
| --- | --- |
| `OneSignal.initialize(appId)` | `Pushbib.initialize({ baseUrl, appId, appKey })` — self-hosted, so it needs the server and the key |
| `OneSignal.login(id)` / `logout()` | same |
| `OneSignal.Debug.setLogLevel(LogLevel.Verbose)` | same, and `'verbose'` also works |
| `OneSignal.User.addTag(s)` / `removeTag(s)` / `getTags()` | same |
| `OneSignal.User.setLanguage(code)` | same |
| `OneSignal.User.addEventListener('change', fn)` | same (`{ current: { externalId, subscriptionId } }`) |
| `OneSignal.User.pushSubscription.getIdAsync()` / `getTokenAsync()` / `getOptedInAsync()` / `optIn()` / `optOut()` | same |
| `OneSignal.Notifications.requestPermission(fallbackToSettings)` | same |
| `OneSignal.Notifications.getPermissionAsync()` / `canRequestPermission()` / `permissionNative()` | same |
| `OneSignal.Notifications.clearAll()` / `removeNotification(id)` / `removeGroupedNotifications(g)` | same |
| `OneSignal.Notifications.addEventListener('click' \| 'foregroundWillDisplay' \| 'permissionChange')` | same, plus `'received'` |
| `OneSignal.Location.setShared(bool)` / `isShared()` / `requestPermission()` | same (`requestPermission` is Android-only here) |
| `OneSignal.LiveActivities.enter(id, token)` / `exit(id)` | same, over `register()` / `end()` |
| `OneSignal.LiveActivities.setPushToStartToken(type, token)` / `removePushToStartToken(type)` | same |

`optIn()` / `optOut()` match OneSignal's meaning exactly: they turn push on and off for this
device without removing anything. An opted-out device still registers on every launch and stays
on the dashboard's device list as **unsubscribed** — with its token, tags, language and session
count intact — so it reappears the moment `optIn()` is called. Only an explicit delete in the
dashboard removes a device.

Two deliberate differences:

- **Nothing throws on empty or odd input.** `addTags({})`, `removeTags([])`, `login('')`,
  `setLocation(NaN)` log a warning and resolve, the way OneSignal ignores them, so call sites
  need no guards. The Pushbib-specific calls that return data (`register`, `getMe`) still reject
  on a real failure, while the OneSignal-named `enter` / `exit` resolve to `null` instead.
- **`initialize` still throws** when `baseUrl`, `appId` or `appKey` is missing, because that is a
  setup mistake worth failing loudly at boot.

Not implemented, because they need product decisions rather than a shim: aliases
(`addAlias`), email and SMS subscriptions (`addEmail`, `addSms`), outcomes
(`Session.addOutcome`), `User.trackEvent`, in-app messages, consent gating
(`setConsentRequired` / `setConsentGiven`), iOS provisional authorization, and
`LiveActivities.setupDefault` / `startDefault` (they own ActivityKit from inside the SDK).

## Known limitations

- **iOS action buttons** are not implemented — the SDK does not register
  `UNNotificationCategory`, so buttons sent to an iOS device are ignored.
  Android renders them natively.
- **iOS rich images** need the Notification Service Extension target described
  in [Images in notifications](#images-in-notifications-ios-only--android-needs-nothing)
  (an Apple constraint, not ours). Android needs nothing.
- `getTags()` returns the local cache; use `getMe()` for the server truth.
- **No badge API from JS.** Badges on Android are channel-driven (`showBadge` on the
  channel). The native module exports `setBadgeCount`, but nothing in the JS surface
  calls it and the Android implementation is an empty stub, so it is iOS-only and
  unreachable today.
- `Channels.list()` and `openSettings(key)` are Android-only; on iOS they return
  `[]` and open the app's own settings page, because iOS has no channels.
- iOS `time-sensitive` and `critical` need an Apple entitlement — without it APNs
  accepts the push and iOS ignores the level.
- **iOS Live Activities need app-side Swift** — the `ActivityAttributes` and the
  widget must live in your Widget Extension, so no library can supply them. The
  SDK provides the token bridge; see the section above. Android needs nothing.
- **Android live updates can't be scheduled for removal.** An `END` with a future
  `dismissal_date` leaves a dismissible card rather than auto-clearing later —
  Android has no equivalent of ActivityKit's dismissal date.

## Troubleshooting

- **`pushbib/no-android-config`** — the server has no Android push identity
  for this app yet: open App Settings and run "Detect from service account".
- **`pushbib/token-timeout` on iOS** — simulators can't get APNs tokens;
  use a real device and check the Push Notifications capability.
- **No pushes on iOS** — upload the APNs `.p8` key in App Settings; make sure
  *sandbox* matches your build type (debug = sandbox).
- **Android background pushes delayed** — aggressive OEM battery savers
  (Xiaomi/Huawei…) throttle high-priority data messages for apps the user
  force-stopped; this affects every push SDK equally.
