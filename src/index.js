import { AppState, Linking, PermissionsAndroid, Platform } from 'react-native';
import { ApiClient, ApiError } from './api';
import { EventManager } from './EventManager';
import { log, setLogLevel } from './Logger';
import { NATIVE_EVENTS, PushbibNative, isNativeAvailable, nativeEmitter } from './NativePushbib';
import { SDK_NAME, SDK_VERSION } from './version';

const MAX_TAG_ITEMS = 100;

function toTagScalar(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === 'object') return null;
  return String(value);
}

function normalizeList(values) {
  const out = [];
  const seen = new Set();
  for (const raw of values) {
    const item = toTagScalar(raw);
    if (item === null || item === '') continue;
    if (seen.has(item)) continue;
    seen.add(item);
    out.push(item);
    if (out.length >= MAX_TAG_ITEMS) break;
  }
  return out;
}

function normalizeTagValue(value) {
  if (Array.isArray(value)) {
    const list = normalizeList(value);
    return list.length === 0 ? null : list;
  }
  return toTagScalar(value);
}

function tagItems(value) {
  if (Array.isArray(value)) return value.map((v) => String(v));
  if (value === null || value === undefined) return [];
  return [String(value)];
}

// Notification events carry OneSignal's shape as well as Pushbib's own fields: callers can read
// additionalData / launchURL and call getNotification() and display(), so one handler fits both SDKs.
function buildNotification(raw, display) {
  const payload = raw || {};
  const data = payload.data || {};
  return {
    ...payload,
    notificationId: payload.notificationId ?? null,
    title: payload.title ?? null,
    body: payload.body ?? null,
    launchURL: payload.url ?? null,
    additionalData: data,
    data,
    display: display || (() => {}),
  };
}

const PREFS = {
  subscriptionId: 'subscription_id',
  externalId: 'external_id',
  identityHash: 'identity_hash',
  optedOut: 'opted_out',
  tags: 'tags_cache',
  config: 'config_cache',
  channels: 'channels_cache',
  location: 'location_point',
  deviceId: 'device_id',
  showForeground: 'show_foreground',
  showBackground: 'show_background',
  showLiveUpdate: 'show_live_update',
  language: 'language_override',
  locationShared: 'location_shared',
  permissionAsked: 'permission_asked',
};

// OneSignal's LogLevel enum, so Debug.setLogLevel(LogLevel.Verbose) reads the same on both SDKs.
export const LogLevel = {
  None: 0,
  Fatal: 1,
  Error: 2,
  Warn: 3,
  Info: 4,
  Debug: 5,
  Verbose: 6,
};

// OneSignal's OSNotificationPermission enum, returned by Notifications.permissionNative().
export const NotificationPermission = {
  NotDetermined: 0,
  Denied: 1,
  Authorized: 2,
  Provisional: 3,
  Ephemeral: 4,
};

const SESSION_PING_MIN_INTERVAL_MS = 30 * 1000;
const REGISTER_RETRY_DELAYS_MS = [2000, 5000, 15000, 30000];

const BAD_DEVICE_IDS = new Set(['', '0', '0000000000000000', '9774d56d682e549c', 'unknown', 'null', 'undefined']);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function uuidv4() {
  const bytes = new Uint8Array(16);
  const g = typeof globalThis !== 'undefined' ? globalThis.crypto : undefined;
  if (g && typeof g.getRandomValues === 'function') {
    g.getRandomValues(bytes);
  } else {
    for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [];
  for (let i = 0; i < 16; i++) hex.push(bytes[i].toString(16).padStart(2, '0'));
  return (
    hex.slice(0, 4).join('') + '-' + hex.slice(4, 6).join('') + '-' +
    hex.slice(6, 8).join('') + '-' + hex.slice(8, 10).join('') + '-' +
    hex.slice(10, 16).join('')
  );
}

class PushbibSdk {
  constructor() {
    this._events = new EventManager();
    this._api = new ApiClient();
    this._options = null;
    this._deviceInfo = null;
    this._deviceId = null;
    this._state = {
      subscriptionId: null,
      externalId: null,
      // hex(HMAC-SHA256(app secret, externalId)), produced by your own backend. Only needed when
      // the app has identity verification turned on; harmless to send when it does not.
      identityHash: null,
      pushToken: null,
      optedOut: false,
      registered: false,
      tags: {},
      location: null,
      // User.setLanguage() wins over the device locale reported by the native module
      language: null,
      locationShared: true,
      permissionAsked: false,
    };
    this._needsRegister = false;
    this._lastSessionPingAt = 0;
    this._lastPermission = null;
    this._ready = new Promise((resolve, reject) => {
      this._readyResolve = resolve;
      this._readyReject = reject;
    });
    this._queue = Promise.resolve();

    // Same shape as OneSignal.Debug
    this.Debug = {
      setLogLevel: (level) => setLogLevel(level),
      setAlertLevel: (level) => log.info(`Debug.setAlertLevel(${level}) — Pushbib has no alert log, ignored`),
    };

    this.User = this._buildUserNamespace();
    this.Notifications = this._buildNotificationsNamespace();
    this.Location = this._buildLocationNamespace();
    this.LiveActivities = this._buildLiveActivitiesNamespace();
    this.Channels = this._buildChannelsNamespace();
  }

  initialize(options) {
    if (this._options) {
      log.warn('initialize() called twice — ignoring the second call.');
      return;
    }
    const { baseUrl, appId, appKey } = options || {};
    if (!baseUrl || !appId || !appKey) {
      throw new Error('Pushbib.initialize requires { baseUrl, appId, appKey }');
    }
    if (options.logLevel) setLogLevel(options.logLevel);
    this._options = { autoOpenLaunchUrls: true, ...options };

    this._start()
      .then(() => this._readyResolve())
      .catch((e) => {
        log.error('initialize failed:', e.message || e);
        this._readyReject(e);
      });
    this._ready.catch(() => {});
  }

  /**
   * Identify the person using the app.
   *
   * @param {string} externalId   your own id for this user
   * @param {string} [identityHash]
   *   hex(HMAC-SHA256(secret, externalId)), computed on YOUR SERVER with the signing secret from
   *   the app's dashboard, and returned to the app when it signs in.
   *
   *   Required when the app has identity verification turned on, and the reason it exists: the
   *   app key in this bundle can be read out of the binary by anyone who installs the app, so on
   *   its own it proves only that the caller has the app, never which user they are. Without a
   *   signature, a request could name anyone's external id and read or rewrite their tags.
   *
   *   NEVER ship the signing secret in the app. If the secret is in the bundle, the signature
   *   proves nothing, because an attacker can compute it too.
   */
  login(externalId, identityHash) {
    const id = externalId === null || externalId === undefined ? '' : String(externalId);
    if (!id) {
      log.warn('login(externalId) needs a non-empty id — ignoring the call');
      return Promise.resolve();
    }
    let hash = identityHash;
    if (hash !== undefined && hash !== null && typeof hash !== 'string') {
      log.warn('login(externalId, identityHash) — identityHash must be a string; ignoring it');
      hash = null;
    }
    return this._enqueue(async () => {
      this._state.externalId = id;
      this._state.identityHash = hash || null;
      await this._prefsSet(PREFS.externalId, id);
      await this._prefsSet(PREFS.identityHash, this._state.identityHash);
      this._events.emit('userChange', { current: { externalId: id, subscriptionId: this._state.subscriptionId } });
      if (this._state.subscriptionId) {
        await this._api.link({
          external_id: id,
          subscription_id: this._state.subscriptionId,
          ...this._identity(),
          ...this._deviceHints(),
        });
        log.info(`logged in as "${id}"`);
      }
    });
  }

  logout() {
    return this._enqueue(async () => {
      const { externalId, subscriptionId, identityHash } = this._state;
      this._state.externalId = null;
      this._state.identityHash = null;
      await this._prefsSet(PREFS.externalId, null);
      await this._prefsSet(PREFS.identityHash, null);
      this._events.emit('userChange', { current: { externalId: null, subscriptionId: subscriptionId } });
      if (externalId && subscriptionId) {
        await this._api
          .unlink({
            external_id: externalId,
            subscription_id: subscriptionId,
            ...(identityHash ? { identity_hash: identityHash } : {}),
          })
          .catch((e) => {
            if (!(e instanceof ApiError && e.status === 404)) throw e;
          });
        log.info(`logged out "${externalId}"`);
      }
    });
  }

  getMe() {
    return this._enqueue(async () => {
      if (!this._state.externalId) throw new Error('getMe() requires login() first');
      const res = await this._api.me({
        external_id: this._state.externalId,
        ...this._identity(),
      });
      return res.data;
    });
  }

  setLogLevel(level) {
    setLogLevel(level);
  }

  _buildUserNamespace() {
    const sdk = this;
    return {
      addTag(key, value) {
        return sdk.User.addTags({ [key]: value });
      },
      addTags(tags) {
        if (!tags || typeof tags !== 'object' || Array.isArray(tags) || Object.keys(tags).length === 0) {
          log.warn('User.addTags needs a non-empty object — ignoring the call');
          return Promise.resolve();
        }
        const normalized = {};
        for (const [k, v] of Object.entries(tags)) {
          const value = normalizeTagValue(v);
          if (value !== null) normalized[k] = value;
        }
        if (Object.keys(normalized).length === 0) {
          log.warn('User.addTags got no usable value — ignoring the call');
          return Promise.resolve();
        }
        return sdk._enqueue(async () => {
          for (const [k, v] of Object.entries(normalized)) sdk._state.tags[k] = v;
          await sdk._prefsSet(PREFS.tags, JSON.stringify(sdk._state.tags));
          await sdk._api.addTags({ ...sdk._tagTarget(), tags: normalized });
        });
      },
      sendTag(key, value) {
        return sdk.User.addTags({ [key]: value });
      },
      sendTags(tags) {
        return sdk.User.addTags(tags);
      },
      removeTag(key, items) {
        if (typeof key !== 'string' || key.length === 0) {
          log.warn('User.removeTag needs a tag key — ignoring the call');
          return Promise.resolve();
        }
        if (items === undefined || items === null) return sdk.User.removeTags([key]);

        const drop = normalizeList(Array.isArray(items) ? items : [items]);
        if (drop.length === 0) {
          log.warn('User.removeTag needs at least one item to remove — ignoring the call');
          return Promise.resolve();
        }
        return sdk._enqueue(async () => {
          const current = sdk._state.tags[key];
          if (current !== undefined) {
            const kept = tagItems(current).filter((item) => !drop.includes(item));
            if (kept.length === 0) delete sdk._state.tags[key];
            else sdk._state.tags[key] = Array.isArray(current) ? kept : kept[0];
            await sdk._prefsSet(PREFS.tags, JSON.stringify(sdk._state.tags));
          }
          await sdk._api.removeTags({ ...sdk._tagTarget(), tags: { [key]: drop } });
        });
      },
      removeTags(keys) {
        const list = Array.isArray(keys) ? keys.filter(Boolean) : keys ? [keys] : [];
        if (list.length === 0) {
          log.warn('User.removeTags needs at least one key — ignoring the call');
          return Promise.resolve();
        }
        return sdk._enqueue(async () => {
          for (const k of list) delete sdk._state.tags[k];
          await sdk._prefsSet(PREFS.tags, JSON.stringify(sdk._state.tags));
          await sdk._api.removeTags({ ...sdk._tagTarget(), tags: list });
        });
      },
      // OneSignal resolves the tags from a promise; getTagsSync() keeps the old synchronous read.
      async getTags() {
        await sdk._ready.catch(() => {});
        return { ...sdk._state.tags };
      },
      getTagsSync() {
        return { ...sdk._state.tags };
      },
      // Overrides the device locale the native module reports, and is sent with every register/link
      setLanguage(language) {
        const value = language === null || language === undefined ? '' : String(language).trim().slice(0, 16);
        if (!value) {
          log.warn('User.setLanguage(language) needs a language code — ignoring the call');
          return Promise.resolve();
        }
        return sdk._enqueue(async () => {
          if (sdk._state.language === value) return;
          sdk._state.language = value;
          await sdk._prefsSet(PREFS.language, value);
          if (!sdk._state.subscriptionId) return;
          await sdk._api.setProfile({ subscription_id: sdk._state.subscriptionId, language: value });
          log.info(`language set to "${value}"`);
        });
      },
      async getLanguage() {
        await sdk._ready.catch(() => {});
        return sdk._state.language || sdk._deviceInfo?.language || null;
      },
      addEventListener(event, handler) {
        if (event !== 'change') throw new Error('User supports only the "change" event');
        sdk._events.add('userChange', handler);
      },
      removeEventListener(event, handler) {
        sdk._events.remove('userChange', handler);
      },
      async getExternalId() {
        await sdk._ready.catch(() => {});
        return sdk._state.externalId;
      },
      pushSubscription: {
        async getId() {
          await sdk._ready.catch(() => {});
          return sdk._state.subscriptionId;
        },
        async getToken() {
          await sdk._ready.catch(() => {});
          return sdk._state.pushToken;
        },
        async getOptedIn() {
          await sdk._ready.catch(() => {});
          return !sdk._state.optedOut && sdk._state.registered;
        },
        optIn() {
          return sdk._enqueue(async () => {
            sdk._state.optedOut = false;
            await sdk._prefsSet(PREFS.optedOut, null);
            await sdk._registerWithRetry();
            sdk._events.emit('pushSubscriptionChange', await sdk._subscriptionSnapshot());
          });
        },
        optOut() {
          return sdk._enqueue(async () => {
            sdk._state.optedOut = true;
            await sdk._prefsSet(PREFS.optedOut, '1');
            if (sdk._state.subscriptionId) {
              await sdk._api
                .unregisterDevice({ subscription_id: sdk._state.subscriptionId })
                .catch((e) => log.warn('optOut sync failed (will stay opted-out locally):', e.message));
            }
            sdk._state.registered = false;
            sdk._events.emit('pushSubscriptionChange', await sdk._subscriptionSnapshot());
          });
        },
        // OneSignal's names for the same three reads
        getIdAsync() {
          return sdk.User.pushSubscription.getId();
        },
        getTokenAsync() {
          return sdk.User.pushSubscription.getToken();
        },
        getOptedInAsync() {
          return sdk.User.pushSubscription.getOptedIn();
        },
        addEventListener(event, handler) {
          if (event !== 'change') throw new Error('pushSubscription supports only the "change" event');
          sdk._events.add('pushSubscriptionChange', handler);
        },
        removeEventListener(event, handler) {
          sdk._events.remove('pushSubscriptionChange', handler);
        },
      },
    };
  }

  _buildLocationNamespace() {
    const sdk = this;
    return {
      setLocation(latitude, longitude) {
        const lat = Number(latitude);
        const long = Number(longitude);
        if (!Number.isFinite(lat) || !Number.isFinite(long)) {
          log.warn('Location.setLocation(latitude, longitude) needs two numbers — ignoring the call');
          return Promise.resolve();
        }
        if (lat < -90 || lat > 90 || long < -180 || long > 180) {
          log.warn(`Location.setLocation: coordinates out of range (${lat}, ${long}) — ignoring the call`);
          return Promise.resolve();
        }
        return sdk._enqueue(async () => {
          if (!sdk._state.locationShared) {
            log.info('location collection is off (Location.setShared(false)) — not sending');
            return;
          }
          sdk._state.location = { lat, long };
          await sdk._prefsSet(PREFS.location, JSON.stringify(sdk._state.location));
          if (!sdk._state.subscriptionId) return;
          await sdk._api.setLocation({ subscription_id: sdk._state.subscriptionId, lat, long });
          log.info(`location set (${lat}, ${long})`);
        });
      },
      async getLocation() {
        await sdk._ready.catch(() => {});
        return sdk._state.location ? { ...sdk._state.location } : null;
      },
      // OneSignal.Location.setShared(false) stops location collection; here it also stops
      // setLocation() from sending and drops the point from the register payload.
      setShared(shared) {
        const on = !!shared;
        return sdk._enqueue(async () => {
          if (sdk._state.locationShared === on) return;
          sdk._state.locationShared = on;
          await sdk._prefsSet(PREFS.locationShared, on ? null : '0');
          log.info(`location sharing ${on ? 'enabled' : 'disabled'}`);
          if (!on) return;
          if (!sdk._state.location || !sdk._state.subscriptionId) return;
          await sdk._api.setLocation({
            subscription_id: sdk._state.subscriptionId,
            lat: sdk._state.location.lat,
            long: sdk._state.location.long,
          });
        });
      },
      async isShared() {
        await sdk._ready.catch(() => {});
        return sdk._state.locationShared;
      },
      // Asks the OS for location permission. Android only: the SDK links no CoreLocation on iOS,
      // so there an app that wants coordinates asks with its own location library and calls
      // setLocation(). Returns whether permission is granted.
      async requestPermission() {
        if (Platform.OS !== 'android') {
          log.info('Location.requestPermission() is Android-only — ask with your location library on iOS');
          return false;
        }
        try {
          const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION);
          return result === PermissionsAndroid.RESULTS.GRANTED;
        } catch (e) {
          log.warn('Location.requestPermission failed:', e.message || e);
          return false;
        }
      },
      clearLocation() {
        return sdk._enqueue(async () => {
          sdk._state.location = null;
          await sdk._prefsSet(PREFS.location, null);
          if (!sdk._state.subscriptionId) return;
          await sdk._api
            .clearLocation({ subscription_id: sdk._state.subscriptionId })
            .catch((e) => {
              if (!(e instanceof ApiError && e.status === 404)) throw e;
            });
          log.info('location cleared');
        });
      },
    };
  }

  _buildLiveActivitiesNamespace() {
    const sdk = this;
    const ns = {
      register(options) {
        const o = options || {};
        if (!o.activityId && !o.pushToStartToken) {
          throw new Error(
            'Pushbib.LiveActivities.register requires activityId, or a pushToStartToken to store for later remote start',
          );
        }
        return sdk._enqueue(async () => {
          if (!sdk._state.subscriptionId) {
            throw new Error('LiveActivities.register requires a registered device — no subscription yet');
          }
          const res = await sdk._api.registerLiveActivity({
            subscription_id: sdk._state.subscriptionId,
            platform: Platform.OS,
            ...(o.activityId ? { activity_id: o.activityId } : {}),
            ...(o.activityPushToken ? { activity_push_token: o.activityPushToken } : {}),
            ...(o.pushToStartToken ? { push_to_start_token: o.pushToStartToken } : {}),
            ...(o.attributesType ? { attributes_type: o.attributesType } : {}),
            ...(Number.isInteger(o.notificationId) ? { notification_id: o.notificationId } : {}),
            ...(Number.isFinite(o.expiresIn) ? { expires_in: Math.floor(o.expiresIn) } : {}),
            ...(sdk._state.externalId ? { external_id: sdk._state.externalId } : {}),
          });
          log.info(
            o.activityId
              ? `live activity registered (${o.activityId})`
              : `push-to-start token registered (${o.attributesType || 'default'})`,
          );
          return res.data;
        });
      },

      end(activityId) {
        return sdk._enqueue(async () => {
          if (!sdk._state.subscriptionId) return { ended_count: 0 };
          const res = await sdk._api
            .endLiveActivity({
              subscription_id: sdk._state.subscriptionId,
              ...(activityId ? { activity_id: activityId } : {}),
            })
            .catch((e) => {
              if (e instanceof ApiError && e.status === 404) return { data: { ended_count: 0 } };
              throw e;
            });
          return res.data;
        });
      },

      getSessions() {
        return sdk._enqueue(async () => {
          if (!sdk._state.subscriptionId) return [];
          const res = await sdk._api.liveActivitySessions(sdk._state.subscriptionId);
          return res.data?.sessions || [];
        });
      },

      addEventListener(event, handler) {
        if (event !== 'update') {
          throw new Error('LiveActivities supports only the "update" event');
        }
        sdk._events.add('liveUpdate', handler);
      },
      removeEventListener(event, handler) {
        sdk._events.remove('liveUpdate', handler);
      },
    };

    // OneSignal's names. They are fire-and-forget there, so these never reject: a failure is
    // logged and resolves to null, which keeps call sites free of try/catch.
    const quiet = (label, promise) =>
      promise.catch((e) => {
        log.warn(`LiveActivities.${label} failed:`, e.message || e);
        return null;
      });

    ns.enter = (activityId, token, handler) =>
      quiet(
        `enter(${activityId})`,
        ns.register({ activityId, ...(token ? { activityPushToken: token } : {}) }),
      ).then((session) => {
        if (typeof handler === 'function') handler(session);
        return session;
      });

    ns.exit = (activityId, handler) =>
      quiet(`exit(${activityId})`, ns.end(activityId)).then((result) => {
        if (typeof handler === 'function') handler(result);
        return result;
      });

    ns.setPushToStartToken = (activityType, token) =>
      quiet(
        `setPushToStartToken(${activityType})`,
        ns.register({ pushToStartToken: token, ...(activityType ? { attributesType: activityType } : {}) }),
      );

    ns.removePushToStartToken = (activityType) =>
      quiet(
        `removePushToStartToken(${activityType})`,
        sdk._enqueue(async () => {
          if (!sdk._state.subscriptionId) return null;
          const res = await sdk._api.removePushToStart({
            subscription_id: sdk._state.subscriptionId,
            ...(activityType ? { attributes_type: activityType } : {}),
          });
          return res.data;
        }),
      );

    return ns;
  }

  _buildNotificationsNamespace() {
    const sdk = this;
    return {
      // fallbackToSettings sends the user to the app's settings page when the OS prompt is
      // spent, which is what OneSignal.Notifications.requestPermission(true) does.
      async requestPermission(fallbackToSettings = false) {
        if (sdk._options) await sdk._ready.catch(() => {});
        const askedBefore = sdk._state.permissionAsked;
        const granted = await PushbibNative.requestPermission();
        if (!askedBefore) {
          sdk._state.permissionAsked = true;
          await sdk._prefsSet(PREFS.permissionAsked, '1').catch(() => {});
        }
        sdk._notifyPermission(granted);
        if (!granted && fallbackToSettings && askedBefore) {
          Linking.openSettings().catch((e) => log.warn('openSettings failed:', e.message || e));
        }
        return granted;
      },
      hasPermission() {
        return PushbibNative.hasPermission();
      },
      // OneSignal's name for the same read
      getPermissionAsync() {
        return PushbibNative.hasPermission();
      },
      // True while the OS prompt can still appear — permission is not granted and we never asked.
      async canRequestPermission() {
        if (sdk._options) await sdk._ready.catch(() => {});
        if (await PushbibNative.hasPermission()) return false;
        return !sdk._state.permissionAsked;
      },
      // NotificationPermission enum, like OneSignal's permissionNative()
      async permissionNative() {
        if (typeof PushbibNative.permissionNative === 'function') {
          return PushbibNative.permissionNative();
        }
        if (await PushbibNative.hasPermission()) return NotificationPermission.Authorized;
        if (sdk._options) await sdk._ready.catch(() => {});
        return sdk._state.permissionAsked ? NotificationPermission.Denied : NotificationPermission.NotDetermined;
      },
      // Android only, same as OneSignal. Needs a build with the current native module.
      removeNotification(id) {
        if (Platform.OS !== 'android') return;
        if (typeof PushbibNative.removeNotification !== 'function') {
          log.warn('Notifications.removeNotification needs a rebuild with the current native module');
          return;
        }
        PushbibNative.removeNotification(Number(id));
      },
      removeGroupedNotifications(group) {
        if (Platform.OS !== 'android') return;
        if (typeof PushbibNative.removeGroupedNotifications !== 'function') {
          log.warn('Notifications.removeGroupedNotifications needs a rebuild with the current native module');
          return;
        }
        PushbibNative.removeGroupedNotifications(String(group));
      },
      clearAll() {
        PushbibNative.clearAllNotifications();
      },
      async setForegroundDisplayEnabled(enabled) {
        if (sdk._options) await sdk._ready.catch(() => {});
        await sdk._prefsSet(PREFS.showForeground, enabled ? null : '0');
      },
      async getForegroundDisplayEnabled() {
        if (sdk._options) await sdk._ready.catch(() => {});
        return (await sdk._prefsGet(PREFS.showForeground)) !== '0';
      },
      async setBackgroundDisplayEnabled(enabled) {
        if (sdk._options) await sdk._ready.catch(() => {});
        await sdk._prefsSet(PREFS.showBackground, enabled ? null : '0');
      },
      async getBackgroundDisplayEnabled() {
        if (sdk._options) await sdk._ready.catch(() => {});
        return (await sdk._prefsGet(PREFS.showBackground)) !== '0';
      },
      async setLiveUpdateDisplayEnabled(enabled) {
        if (sdk._options) await sdk._ready.catch(() => {});
        await sdk._prefsSet(PREFS.showLiveUpdate, enabled ? null : '0');
      },
      async getLiveUpdateDisplayEnabled() {
        if (sdk._options) await sdk._ready.catch(() => {});
        return (await sdk._prefsGet(PREFS.showLiveUpdate)) !== '0';
      },
      addEventListener(event, handler) {
        sdk._events.add(event, handler);
        if (event === 'foregroundWillDisplay' && isNativeAvailable) {
          PushbibNative.setForegroundHandling(true);
        }
      },
      removeEventListener(event, handler) {
        sdk._events.remove(event, handler);
        if (event === 'foregroundWillDisplay' && isNativeAvailable && sdk._events.count(event) === 0) {
          PushbibNative.setForegroundHandling(false);
        }
      },
    };
  }

  async _start() {
    this._deviceInfo = await PushbibNative.getDeviceInfo();
    this._deviceId = await this._resolveDeviceId();
    this._api.configure({
      baseUrl: this._options.baseUrl,
      appId: this._options.appId,
      appKey: this._options.appKey,
      bundleId: this._deviceInfo.bundleId,
    });

    const [sub, ext, hash, opted, tags, location, language, locationShared, permissionAsked] = await Promise.all([
      this._prefsGet(PREFS.subscriptionId),
      this._prefsGet(PREFS.externalId),
      this._prefsGet(PREFS.identityHash),
      this._prefsGet(PREFS.optedOut),
      this._prefsGet(PREFS.tags),
      this._prefsGet(PREFS.location),
      this._prefsGet(PREFS.language),
      this._prefsGet(PREFS.locationShared),
      this._prefsGet(PREFS.permissionAsked),
    ]);
    this._state.language = language || null;
    this._state.locationShared = locationShared !== '0';
    this._state.permissionAsked = permissionAsked === '1';
    this._state.subscriptionId = sub || null;
    this._state.externalId = ext || null;
    this._state.identityHash = hash || null;
    this._state.optedOut = opted === '1';
    try {
      this._state.tags = tags ? JSON.parse(tags) : {};
    } catch (e) {
      this._state.tags = {};
    }
    try {
      const parsed = location ? JSON.parse(location) : null;
      this._state.location =
        parsed && Number.isFinite(parsed.lat) && Number.isFinite(parsed.long) ? parsed : null;
    } catch (e) {
      this._state.location = null;
    }

    this._attachNativeListeners();

    const initialClick = await PushbibNative.getInitialClickEvent().catch(() => null);
    if (initialClick) this._handleClick(typeof initialClick === 'string' ? JSON.parse(initialClick) : initialClick);

    const config = await this._loadConfig();
    await this._syncChannels(config);

    let androidConfigJson = null;
    if (Platform.OS === 'android') {
      if (!config || !config.android) {
        throw new Error(
          'The server returned no Android push identity for this app — open App Settings in the dashboard ' +
            'and run "Detect from service account". No file is needed here or in the app.',
        );
      }
      androidConfigJson = JSON.stringify(config.android);
    }

    try {
      this._state.pushToken = await PushbibNative.getPushToken(androidConfigJson);
    } catch (e) {
      log.warn('No push token available:', e.message || e);
    }

    if (this._state.pushToken) {
      // Registers even while opted out: the device still belongs in the dashboard, listed as
      // unsubscribed, the way an opted-out OneSignal subscription stays on the record. The
      // payload carries subscribed: false, so the server never counts it as a push target.
      await this._registerWithRetry();
    }

    this._lastPermission = await PushbibNative.hasPermission().catch(() => null);

    this._watchAppState();
    log.info(`ready (sdk ${SDK_VERSION}, platform ${Platform.OS})`);
  }

  async _loadConfig() {
    try {
      const res = await this._api.getConfig();
      await this._prefsSet(PREFS.config, JSON.stringify(res.data));
      return res.data;
    } catch (e) {
      const cached = await this._prefsGet(PREFS.config);
      if (cached) {
        log.warn(`GET /config failed (${e.message}) — using cached config`);
        try {
          return JSON.parse(cached);
        } catch (parseErr) {
          return null;
        }
      }
      throw e;
    }
  }

  async _syncChannels(config) {
    if (Platform.OS !== 'android') return 0;
    const channels = config && Array.isArray(config.channels) ? config.channels : null;
    if (!channels || channels.length === 0) return 0;
    const version = (config && config.channels_version) || null;
    try {
      const created = await PushbibNative.syncChannels(JSON.stringify(channels), version);
      await this._prefsSet(PREFS.channels, JSON.stringify({ version, channels }));
      if (created > 0) log.info(`synced ${created} notification channel(s)`);
      return created;
    } catch (e) {
      log.warn(`channel sync failed (${e.message || e}) — the default channel still works`);
      return 0;
    }
  }

  _buildChannelsNamespace() {
    const sdk = this;
    return {
      async sync() {
        await sdk._ready.catch(() => {});
        const config = await sdk._loadConfig().catch(() => null);
        return sdk._syncChannels(config);
      },
      async list() {
        await sdk._ready.catch(() => {});
        if (Platform.OS !== 'android') return [];
        try {
          return JSON.parse(await PushbibNative.listChannels());
        } catch (e) {
          return [];
        }
      },
      async definitions() {
        await sdk._ready.catch(() => {});
        const cached = await sdk._prefsGet(PREFS.channels);
        if (!cached) return [];
        try {
          return JSON.parse(cached).channels || [];
        } catch (e) {
          return [];
        }
      },
      async openSettings(key) {
        await sdk._ready.catch(() => {});
        const wanted = key ? String(key) : null;
        let channelId = wanted;
        if (wanted && Platform.OS === 'android') {
          const known = await this.definitions();
          const match = known.find((c) => c.key === wanted || c.runtime_id === wanted || c.runtimeId === wanted);
          if (match) channelId = match.runtime_id || match.runtimeId || match.native_id || match.nativeId;
        }
        return PushbibNative.openChannelSettings(channelId);
      },
    };
  }

  _attachNativeListeners() {
    if (!nativeEmitter) return;

    nativeEmitter.addListener(NATIVE_EVENTS.pushToken, ({ token }) => {
      if (!token || token === this._state.pushToken) return;
      log.info('push token rotated — re-registering');
      this._state.pushToken = token;
      if (!this._state.optedOut) {
        this._enqueue(() => this._registerWithRetry()).catch(() => {});
      }
    });

    nativeEmitter.addListener(NATIVE_EVENTS.click, (raw) => this._handleClick(raw));

    nativeEmitter.addListener(NATIVE_EVENTS.received, (raw) => {
      const notification = buildNotification(raw.notification);
      this._events.emit('received', { notification, getNotification: () => notification });
    });

    if (Platform.OS === 'android') {
      nativeEmitter.addListener(NATIVE_EVENTS.liveUpdate, (raw) => {
        this._events.emit('liveUpdate', { liveUpdate: raw.liveUpdate || {} });
      });
    }

    if (Platform.OS === 'ios') {
      nativeEmitter.addListener(NATIVE_EVENTS.liveActivityToken, (raw) => {
        const { token, activityId, attributesType, kind } = raw || {};
        if (!token) return;
        const isPushToStart = kind === 'pushToStart';
        this.LiveActivities.register({
          ...(activityId ? { activityId } : {}),
          ...(attributesType ? { attributesType } : {}),
          ...(isPushToStart ? { pushToStartToken: token } : { activityPushToken: token }),
        }).catch((e) => log.warn(`live activity token registration failed: ${e.message}`));
      });
    }

    nativeEmitter.addListener(NATIVE_EVENTS.foregroundWillDisplay, (raw) => {
      let prevented = false;
      // display() puts the notification back on screen after preventDefault(), as it does on OneSignal
      const notification = buildNotification(raw.notification, () => {
        prevented = false;
      });
      const delivered = this._events.emit('foregroundWillDisplay', {
        notification,
        getNotification: () => notification,
        preventDefault: () => {
          prevented = true;
        },
      });
      PushbibNative.completeNotificationDisplay(raw.completionId, !(delivered && prevented));
    });
  }

  _handleClick(raw) {
    const notification = buildNotification(raw.notification);
    const actionId = raw.actionId || null;
    const url = notification.url || null;
    this._events.emit('click', { notification, getNotification: () => notification, result: { actionId, url } });
    if (url && this._options?.autoOpenLaunchUrls) {
      Linking.openURL(url).catch((e) => log.warn(`could not open launch URL "${url}": ${e.message}`));
    }
  }

  _watchAppState() {
    AppState.addEventListener('change', (state) => {
      if (state !== 'active') return;
      this._onForeground().catch((e) => log.verbose('foreground hook error:', e.message));
    });
  }

  async _onForeground() {
    const granted = await PushbibNative.hasPermission().catch(() => null);
    if (granted !== null) this._notifyPermission(granted);

    if (this._needsRegister || (!this._state.registered && !this._state.optedOut)) {
      await this._registerWithRetry(1);
      return;
    }
    if (this._state.optedOut) return;
    const now = Date.now();
    if (this._state.subscriptionId && now - this._lastSessionPingAt > SESSION_PING_MIN_INTERVAL_MS) {
      this._lastSessionPingAt = now;
      await this._api
        .pingSession({ subscription_id: this._state.subscriptionId })
        .catch((e) => log.verbose('session ping failed:', e.message));
    }
  }

  async _registerWithRetry(maxAttempts = REGISTER_RETRY_DELAYS_MS.length + 1) {
    if (!this._state.pushToken) {
      try {
        this._state.pushToken = await PushbibNative.getPushToken(null);
      } catch (e) {
        this._needsRegister = true;
        log.warn('register skipped — still no push token:', e.message || e);
        return;
      }
    }
    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        await this._register();
        this._needsRegister = false;
        return;
      } catch (e) {
        const retriable = e instanceof ApiError && (e.status === 0 || e.status >= 500 || e.status === 429);
        log.warn(`register attempt ${attempt + 1} failed: ${e.message}`);
        if (!retriable || attempt === maxAttempts - 1) {
          this._needsRegister = true;
          if (!retriable) throw e;
          return;
        }
        await sleep(REGISTER_RETRY_DELAYS_MS[attempt]);
      }
    }
  }

  async _resolveDeviceId() {
    const stored = await this._prefsGet(PREFS.deviceId);
    if (stored) return stored;

    let nativeId = null;
    if (isNativeAvailable && typeof PushbibNative.getStableDeviceId === 'function') {
      try {
        nativeId = await PushbibNative.getStableDeviceId();
      } catch (e) {
        log.verbose('getStableDeviceId failed, falling back:', e.message || e);
      }
    }
    if (nativeId) {
      await this._prefsSet(PREFS.deviceId, nativeId);
      log.info('device id resolved (native stable)');
      return nativeId;
    }

    const hw = this._deviceInfo?.deviceId;
    const usableHw = hw && !BAD_DEVICE_IDS.has(String(hw).trim().toLowerCase());
    const chosen = usableHw ? String(hw).trim().slice(0, 128) : `nfd-${uuidv4()}`;
    await this._prefsSet(PREFS.deviceId, chosen);
    log.info(`device id resolved (${usableHw ? 'hardware' : 'generated'})`);
    return chosen;
  }

  async _register() {
    if (!this._deviceId) this._deviceId = await this._resolveDeviceId();
    const payload = {
      token: this._state.pushToken,
      platform: Platform.OS,
      sdk: SDK_NAME,
      device_id: this._deviceId,
      ...(this._state.subscriptionId ? { subscription_id: this._state.subscriptionId } : {}),
      ...(this._state.externalId ? { external_id: this._state.externalId } : {}),
      ...this._identity(),
      ...(this._state.optedOut ? { subscribed: false } : {}),
      ...(Object.keys(this._state.tags).length > 0 ? { tags: this._state.tags } : {}),
      ...(this._state.location && this._state.locationShared
        ? { lat: this._state.location.lat, long: this._state.location.long }
        : {}),
      ...this._deviceHints(),
    };
    const res = await this._api.registerDevice(payload);
    const previousId = this._state.subscriptionId;
    this._state.subscriptionId = res.data.subscription_id;
    this._state.registered = !this._state.optedOut;
    if (res.data.subscription_id !== previousId) {
      await this._prefsSet(PREFS.subscriptionId, res.data.subscription_id);
    }
    log.info(`registered (subscription ${res.data.subscription_id})`);
    this._events.emit('pushSubscriptionChange', await this._subscriptionSnapshot());
  }

  _deviceHints() {
    const info = this._deviceInfo || {};
    return {
      ...(info.model ? { deviceModel: String(info.model).slice(0, 128) } : {}),
      ...(info.osVersion ? { deviceOs: `${info.osName || Platform.OS} ${info.osVersion}`.slice(0, 64) } : {}),
      ...(info.appVersion ? { appVersion: String(info.appVersion).slice(0, 32) } : {}),
      ...(this._state.language || info.language
        ? { language: String(this._state.language || info.language).slice(0, 16) }
        : {}),
      ...(info.country ? { country: String(info.country).slice(0, 8) } : {}),
      ...(info.timezone ? { timezone: String(info.timezone).slice(0, 64) } : {}),
    };
  }

  _tagTarget() {
    return this._state.externalId
      ? { external_id: this._state.externalId, ...this._identity() }
      : { subscription_id: this._state.subscriptionId };
  }

  // Spread into any body that names an external_id. Empty when there is no hash, so an app that
  // has not turned identity verification on sends exactly what it always sent.
  _identity() {
    return this._state.identityHash ? { identity_hash: this._state.identityHash } : {};
  }

  async _subscriptionSnapshot() {
    return {
      id: this._state.subscriptionId,
      token: this._state.pushToken,
      optedIn: !this._state.optedOut && this._state.registered,
    };
  }

  _notifyPermission(granted) {
    if (this._lastPermission === granted) return;
    this._lastPermission = granted;
    this._events.emit('permissionChange', granted);
  }

  _enqueue(op) {
    if (!this._options) {
      log.warn('Pushbib.initialize() has not been called yet — the call is queued and will run after it.');
    }
    const run = this._queue.then(() => this._ready).then(op);
    this._queue = run.catch(() => {});
    return run;
  }

  _prefsGet(key) {
    return PushbibNative.prefsGet(key);
  }
  _prefsSet(key, value) {
    return PushbibNative.prefsSet(key, value === undefined ? null : value);
  }
}

const Pushbib = new PushbibSdk();
export default Pushbib;
export { SDK_VERSION };
