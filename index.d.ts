export type LogLevelName = 'none' | 'error' | 'warn' | 'info' | 'verbose';

/** OneSignal's LogLevel enum; both the names above and these numbers are accepted. */
export declare const LogLevel: {
  None: 0;
  Fatal: 1;
  Error: 2;
  Warn: 3;
  Info: 4;
  Debug: 5;
  Verbose: 6;
};

/** OneSignal's OSNotificationPermission enum, returned by Notifications.permissionNative(). */
export declare const NotificationPermission: {
  NotDetermined: 0;
  Denied: 1;
  Authorized: 2;
  Provisional: 3;
  Ephemeral: 4;
};

export type LogLevel = LogLevelName | number;

export interface InitializeOptions {
  baseUrl: string;
  appId: string;
  appKey: string;
  autoOpenLaunchUrls?: boolean;
  logLevel?: LogLevel;
}

export interface NotificationButton {
  id: string;
  text: string;
}

export interface PushbibNotification {
  /** OneSignal's name for `data`. */
  additionalData: Record<string, string>;
  /** OneSignal's name for `url`. */
  launchURL?: string | null;
  /** Puts the notification back on screen after preventDefault(); a no-op otherwise. */
  display(): void;
  notificationId?: string;
  channelId?: string;
  title?: string;
  subtitle?: string;
  body?: string;
  imageUrl?: string;
  largeIcon?: string;
  url?: string;
  buttons?: NotificationButton[];
  data: Record<string, string>;
}

export interface NotificationClickEvent {
  notification: PushbibNotification;
  /** OneSignal's accessor for the same object. */
  getNotification(): PushbibNotification;
  result: {
    actionId: string | null;
    url: string | null;
  };
}

export interface NotificationWillDisplayEvent {
  notification: PushbibNotification;
  /** OneSignal's accessor for the same object. */
  getNotification(): PushbibNotification;
  preventDefault(): void;
}

export interface NotificationReceivedEvent {
  notification: PushbibNotification;
  /** OneSignal's accessor for the same object. */
  getNotification(): PushbibNotification;
}

export interface PushSubscriptionSnapshot {
  id: string | null;
  token: string | null;
  optedIn: boolean;
}

export interface MeResult {
  external_id: string;
  subscriptions: Array<{
    subscription_id: string;
    platform: string;
    status: string;
    subscribed: boolean;
    last_active: string;
  }>;
  tags: Record<string, TagValue>;
}

export type NotificationEventName = 'click' | 'received' | 'foregroundWillDisplay' | 'permissionChange';

export interface NotificationsNamespace {
  /** fallbackToSettings opens the app's settings page once the OS prompt is spent. */
  requestPermission(fallbackToSettings?: boolean): Promise<boolean>;
  hasPermission(): Promise<boolean>;
  /** OneSignal's name for hasPermission(). */
  getPermissionAsync(): Promise<boolean>;
  /** True while the OS prompt can still appear: not granted yet and never asked. */
  canRequestPermission(): Promise<boolean>;
  /** The device's native permission state as a NotificationPermission value. */
  permissionNative(): Promise<number>;
  clearAll(): void;
  /** Android: cancels one notification by its integer id. */
  removeNotification(id: number): void;
  /** Android: cancels every notification in a group. */
  removeGroupedNotifications(group: string): void;
  setForegroundDisplayEnabled(enabled: boolean): Promise<void>;
  getForegroundDisplayEnabled(): Promise<boolean>;
  setBackgroundDisplayEnabled(enabled: boolean): Promise<void>;
  getBackgroundDisplayEnabled(): Promise<boolean>;
  addEventListener(event: 'click', listener: (event: NotificationClickEvent) => void): void;
  addEventListener(event: 'received', listener: (event: NotificationReceivedEvent) => void): void;
  addEventListener(event: 'foregroundWillDisplay', listener: (event: NotificationWillDisplayEvent) => void): void;
  addEventListener(event: 'permissionChange', listener: (granted: boolean) => void): void;
  removeEventListener(event: NotificationEventName, listener: (...args: any[]) => void): void;
}

export interface PushSubscriptionNamespace {
  getId(): Promise<string | null>;
  getToken(): Promise<string | null>;
  getOptedIn(): Promise<boolean>;
  /** OneSignal's names for the three reads above. */
  getIdAsync(): Promise<string | null>;
  getTokenAsync(): Promise<string | null>;
  getOptedInAsync(): Promise<boolean>;
  optIn(): Promise<void>;
  optOut(): Promise<void>;
  addEventListener(event: 'change', listener: (snapshot: PushSubscriptionSnapshot) => void): void;
  removeEventListener(event: 'change', listener: (snapshot: PushSubscriptionSnapshot) => void): void;
}

export interface LocationPoint {
  lat: number;
  long: number;
}

export interface LocationNamespace {
  setLocation(latitude: number, longitude: number): Promise<void>;
  getLocation(): Promise<LocationPoint | null>;
  clearLocation(): Promise<void>;
  /** Off stops setLocation() from sending and drops the point from the register payload. */
  setShared(shared: boolean): Promise<void>;
  isShared(): Promise<boolean>;
  /** Android only: asks the OS for location permission. */
  requestPermission(): Promise<boolean>;
}

export type TagScalar = string | number | boolean;
export type TagValue = TagScalar | TagScalar[];

export interface UserNamespace {
  addTag(key: string, value: TagValue): Promise<void>;
  addTags(tags: Record<string, TagValue>): Promise<void>;
  sendTag(key: string, value: TagValue): Promise<void>;
  sendTags(tags: Record<string, TagValue>): Promise<void>;
  removeTag(key: string, items?: TagScalar | TagScalar[]): Promise<void>;
  removeTags(keys: string[] | string): Promise<void>;
  getTags(): Promise<Record<string, string | string[]>>;
  /** The synchronous read getTags() used to be. */
  getTagsSync(): Record<string, string | string[]>;
  /** Overrides the device locale for targeting; sent with every register/link. */
  setLanguage(language: string): Promise<void>;
  getLanguage(): Promise<string | null>;
  getExternalId(): Promise<string | null>;
  addEventListener(event: 'change', listener: (event: UserChangedState) => void): void;
  removeEventListener(event: 'change', listener: (event: UserChangedState) => void): void;
  pushSubscription: PushSubscriptionNamespace;
}

export interface UserChangedState {
  current: { externalId: string | null; subscriptionId: string | null };
}

export interface LiveActivityRegisterOptions {
  activityId?: string;
  activityPushToken?: string;
  pushToStartToken?: string;
  attributesType?: string;
  notificationId?: number;
  expiresIn?: number;
}

export interface LiveActivitySession {
  activity_id: string;
  session_id: string;
  platform: 'ios' | 'android';
  status: 'active' | 'ended' | 'expired';
  attributes_type: string | null;
  notification_id: number | null;
  activity_token_registered: boolean;
  push_to_start_registered: boolean;
  last_sequence_number: number;
  expires_at: string;
}

export interface LiveUpdateEvent {
  liveUpdate: {
    activityId: string;
    action: 'START' | 'UPDATE' | 'END';
    notificationId: number;
    sequenceNumber: number;
    streamId?: string;
    alerting: boolean;
    title?: string;
    body?: string;
    progress?: number;
    indeterminate?: boolean;
    staleDate?: number;
    dismissalDate?: number;
    contentState?: Record<string, unknown>;
    data: Record<string, string>;
  };
  displayed: boolean;
  outcome:
    | 'shown'
    | 'ended'
    | 'notifications-disabled'
    | 'stale-sequence'
    | 'opted-out'
    | 'notify-blocked'
    | 'render-error';
}

export interface LiveActivitiesNamespace {
  register(options: LiveActivityRegisterOptions): Promise<LiveActivitySession>;
  end(activityId?: string): Promise<{ ended_count: number }>;
  /**
   * OneSignal's names. Fire-and-forget like theirs: a failure is logged and resolves to null
   * instead of rejecting.
   */
  enter(activityId: string, token?: string, handler?: (session: LiveActivitySession | null) => void): Promise<LiveActivitySession | null>;
  exit(activityId?: string, handler?: (result: { ended_count: number } | null) => void): Promise<{ ended_count: number } | null>;
  setPushToStartToken(activityType: string, token: string): Promise<LiveActivitySession | null>;
  removePushToStartToken(activityType?: string): Promise<{ subscription_id: string; attributes_type: string } | null>;
  getSessions(): Promise<LiveActivitySession[]>;
  addEventListener(event: 'update', handler: (event: LiveUpdateEvent) => void): void;
  removeEventListener(event: 'update', handler: (event: LiveUpdateEvent) => void): void;
}

export type ChannelImportance = 'low' | 'medium' | 'high' | 'urgent';
export type ChannelVisibility = 'secret' | 'private' | 'public';
export type ChannelMode = 'off' | 'default' | 'custom';

export interface ChannelDefinition {
  key: string;
  runtime_id: string;
  previous_runtime_id: string | null;
  native_id: string | null;
  name: string;
  description: string | null;
  is_default: boolean;
  importance: ChannelImportance;
  sound: { mode: ChannelMode; name?: string };
  vibration: { mode: ChannelMode; pattern?: number[] };
  led: { mode: ChannelMode; color?: string };
  lockscreen_visibility: ChannelVisibility;
  show_badge: boolean;
  bypass_dnd: boolean;
  group_key: string | null;
  group_name: string | null;
}

export interface DeviceChannel {
  id: string;
  name: string | null;
  importance: number;
  blocked: boolean;
  showBadge: boolean;
  group?: string | null;
}

export interface ChannelsNamespace {
  sync(): Promise<number>;
  list(): Promise<DeviceChannel[]>;
  definitions(): Promise<ChannelDefinition[]>;
  openSettings(key?: string): Promise<boolean>;
}

export interface PushbibStatic {
  initialize(options: InitializeOptions): void;
  /**
   * Identify the person using the app.
   *
   * `identityHash` is hex(HMAC-SHA256(secret, externalId)), computed on YOUR SERVER with the
   * signing secret from the app's dashboard. Required once the app has identity verification
   * turned on: the app key in this bundle is readable by anyone who installs the app, so on its
   * own it proves the caller has the app, not which user they are.
   *
   * Never ship the signing secret in the app — a signature an attacker can compute proves
   * nothing.
   */
  login(externalId: string, identityHash?: string): Promise<void>;
  logout(): Promise<void>;
  getMe(): Promise<MeResult>;
  setLogLevel(level: LogLevel): void;
  /** Same shape as OneSignal.Debug. */
  Debug: {
    setLogLevel(level: LogLevel): void;
    /** Accepted for parity; Pushbib has no alert log. */
    setAlertLevel(level: LogLevel): void;
  };
  User: UserNamespace;
  Notifications: NotificationsNamespace;
  Location: LocationNamespace;
  LiveActivities: LiveActivitiesNamespace;
  Channels: ChannelsNamespace;
}

declare const Pushbib: PushbibStatic;
export default Pushbib;
export const SDK_VERSION: string;
