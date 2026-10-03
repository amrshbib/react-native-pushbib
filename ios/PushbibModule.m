#import "PushbibModule.h"

#import <UIKit/UIKit.h>
#import <UserNotifications/UserNotifications.h>
#import <Security/Security.h>
#import <sys/utsname.h>

#import "PushbibEventBus.h"

static NSString *const kPrefsPrefix = @"pushbib_";
static NSString *const kDeviceIdService = @"com.amrshbib.pushbib.deviceid";
static NSString *const kDeviceIdAccount = @"device_id";
static const NSTimeInterval kTokenTimeout = 15.0;

static NSString *PushbibStableDeviceId(void) {
  NSDictionary *lookup = @{
    (__bridge id)kSecClass:       (__bridge id)kSecClassGenericPassword,
    (__bridge id)kSecAttrService: kDeviceIdService,
    (__bridge id)kSecAttrAccount: kDeviceIdAccount,
    (__bridge id)kSecReturnData:  @YES,
    (__bridge id)kSecMatchLimit:  (__bridge id)kSecMatchLimitOne,
  };
  CFTypeRef found = NULL;
  OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)lookup, &found);
  if (status == errSecSuccess && found != NULL) {
    NSData *data = (__bridge_transfer NSData *)found;
    NSString *existing = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
    if (existing.length > 0) return existing;
  }

  NSString *idfv = UIDevice.currentDevice.identifierForVendor.UUIDString;
  NSString *newId = (idfv.length > 0) ? idfv : NSUUID.UUID.UUIDString;
  NSDictionary *add = @{
    (__bridge id)kSecClass:        (__bridge id)kSecClassGenericPassword,
    (__bridge id)kSecAttrService:  kDeviceIdService,
    (__bridge id)kSecAttrAccount:  kDeviceIdAccount,
    (__bridge id)kSecValueData:    [newId dataUsingEncoding:NSUTF8StringEncoding],
    (__bridge id)kSecAttrAccessible: (__bridge id)kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
  };
  SecItemDelete((__bridge CFDictionaryRef)add);
  SecItemAdd((__bridge CFDictionaryRef)add, NULL);
  return newId;
}

@implementation PushbibModule

RCT_EXPORT_MODULE(Pushbib);

+ (BOOL)requiresMainQueueSetup {
  return NO;
}

- (NSArray<NSString *> *)supportedEvents {
  return @[ PushbibEventPushToken, PushbibEventClick, PushbibEventReceived,
            PushbibEventForegroundWillDisplay, PushbibEventLiveActivityToken ];
}

- (void)startObserving {
  __weak typeof(self) weakSelf = self;
  [[PushbibEventBus shared] setHandler:^(NSString *name, NSDictionary *body) {
    [weakSelf sendEventWithName:name body:body];
  }];
}

- (void)stopObserving {
  [[PushbibEventBus shared] setHandler:nil];
}

RCT_EXPORT_METHOD(getDeviceInfo:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  struct utsname systemInfo;
  uname(&systemInfo);
  NSString *model = [NSString stringWithCString:systemInfo.machine encoding:NSUTF8StringEncoding]
      ?: UIDevice.currentDevice.model;

  NSBundle *bundle = NSBundle.mainBundle;
  NSLocale *locale = NSLocale.currentLocale;
  NSString *deviceId = UIDevice.currentDevice.identifierForVendor.UUIDString;
  resolve(@{
    @"platform" : @"ios",
    @"deviceId" : deviceId ?: (id)kCFNull,
    @"osName" : @"iOS",
    @"osVersion" : UIDevice.currentDevice.systemVersion ?: @"",
    @"model" : model ?: @"",
    @"bundleId" : bundle.bundleIdentifier ?: @"",
    @"appVersion" : [bundle objectForInfoDictionaryKey:@"CFBundleShortVersionString"] ?: @"",
    @"language" : NSLocale.preferredLanguages.firstObject ?: @"en",
    @"country" : [locale objectForKey:NSLocaleCountryCode] ?: @"",
    @"timezone" : NSTimeZone.localTimeZone.name ?: @"",
  });
}

RCT_EXPORT_METHOD(getStableDeviceId:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  @try {
    NSString *deviceId = PushbibStableDeviceId();
    resolve(deviceId.length > 0 ? deviceId : (id)kCFNull);
  } @catch (__unused NSException *e) {
    resolve((id)kCFNull);
  }
}

RCT_EXPORT_METHOD(prefsGet:(NSString *)key
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  NSString *value =
      [[NSUserDefaults standardUserDefaults] stringForKey:[kPrefsPrefix stringByAppendingString:key]];
  resolve(value ?: (id)kCFNull);
}

RCT_EXPORT_METHOD(prefsSet:(NSString *)key
                  value:(NSString *)value
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  NSString *fullKey = [kPrefsPrefix stringByAppendingString:key];
  if (value == nil || [value isKindOfClass:NSNull.class]) {
    [defaults removeObjectForKey:fullKey];
  } else {
    [defaults setObject:value forKey:fullKey];
  }
  resolve(nil);
}

RCT_EXPORT_METHOD(getPushToken:(NSString *)configJson
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  PushbibEventBus *bus = [PushbibEventBus shared];
  if (bus.deviceTokenHex.length > 0) {
    resolve(bus.deviceTokenHex);
    return;
  }

  NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
  __block BOOL settled = NO;
  __block id successObserver = nil;
  __block id failureObserver = nil;

  void (^cleanup)(void) = ^{
    if (successObserver) [nc removeObserver:successObserver];
    if (failureObserver) [nc removeObserver:failureObserver];
  };

  successObserver = [nc addObserverForName:PushbibDeviceTokenNotification
                                    object:nil
                                     queue:NSOperationQueue.mainQueue
                                usingBlock:^(NSNotification *note) {
                                  if (settled) return;
                                  settled = YES;
                                  cleanup();
                                  resolve(note.userInfo[@"token"]);
                                }];
  failureObserver = [nc addObserverForName:PushbibDeviceTokenErrorNotification
                                    object:nil
                                     queue:NSOperationQueue.mainQueue
                                usingBlock:^(NSNotification *note) {
                                  if (settled) return;
                                  settled = YES;
                                  cleanup();
                                  reject(@"pushbib/apns-failed", note.userInfo[@"message"], nil);
                                }];

  dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(kTokenTimeout * NSEC_PER_SEC)),
                 dispatch_get_main_queue(), ^{
                   if (settled) return;
                   settled = YES;
                   cleanup();
                   reject(@"pushbib/token-timeout",
                          @"APNs did not return a device token in time (note: simulators cannot get "
                          @"push tokens; check the Push Notifications capability on a real device)",
                          nil);
                 });

  dispatch_async(dispatch_get_main_queue(), ^{
    [[UIApplication sharedApplication] registerForRemoteNotifications];
  });
}

RCT_EXPORT_METHOD(requestPermission:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  UNAuthorizationOptions options =
      UNAuthorizationOptionAlert | UNAuthorizationOptionSound | UNAuthorizationOptionBadge;
  [[UNUserNotificationCenter currentNotificationCenter]
      requestAuthorizationWithOptions:options
                    completionHandler:^(BOOL granted, NSError *_Nullable error) {
                      if (granted) {
                        dispatch_async(dispatch_get_main_queue(), ^{
                          [[UIApplication sharedApplication] registerForRemoteNotifications];
                        });
                      }
                      resolve(@(granted));
                    }];
}

RCT_EXPORT_METHOD(hasPermission:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  [[UNUserNotificationCenter currentNotificationCenter]
      getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
        BOOL granted = settings.authorizationStatus == UNAuthorizationStatusAuthorized ||
                       settings.authorizationStatus == UNAuthorizationStatusProvisional;
        if (@available(iOS 14.0, *)) {
          granted = granted || settings.authorizationStatus == UNAuthorizationStatusEphemeral;
        }
        resolve(@(granted));
      }];
}

RCT_EXPORT_METHOD(syncChannels:(NSString *)channelsJson
                  version:(NSString *)version
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  resolve(@(0));
}

RCT_EXPORT_METHOD(listChannels:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  resolve(@"[]");
}

RCT_EXPORT_METHOD(openChannelSettings:(NSString *)channelId
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  NSURL *url = [NSURL URLWithString:UIApplicationOpenSettingsURLString];
  dispatch_async(dispatch_get_main_queue(), ^{
    if (url && [[UIApplication sharedApplication] canOpenURL:url]) {
      [[UIApplication sharedApplication] openURL:url options:@{} completionHandler:nil];
      resolve(@(YES));
    } else {
      resolve(@(NO));
    }
  });
}

RCT_EXPORT_METHOD(setForegroundHandling:(BOOL)enabled) {
  [PushbibEventBus shared].hasForegroundHandler = enabled;
}

RCT_EXPORT_METHOD(completeNotificationDisplay:(NSString *)completionId display:(BOOL)display) {
  [[PushbibEventBus shared] completeDisplay:completionId display:display];
}

// OSNotificationPermission: 0 not determined, 1 denied, 2 authorized, 3 provisional, 4 ephemeral
RCT_EXPORT_METHOD(permissionNative:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  [[UNUserNotificationCenter currentNotificationCenter] getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
    NSInteger value;
    switch (settings.authorizationStatus) {
      case UNAuthorizationStatusNotDetermined: value = 0; break;
      case UNAuthorizationStatusDenied:        value = 1; break;
      case UNAuthorizationStatusAuthorized:    value = 2; break;
      case UNAuthorizationStatusProvisional:   value = 3; break;
      default:                                 value = 4; break;
    }
    resolve(@(value));
  }];
}

// Android-only in OneSignal; here they clear delivered notifications so the API matches.
RCT_EXPORT_METHOD(removeNotification:(double)identifier) {
  NSString *key = [NSString stringWithFormat:@"%lld", (long long)identifier];
  [[UNUserNotificationCenter currentNotificationCenter] removeDeliveredNotificationsWithIdentifiers:@[key]];
}

RCT_EXPORT_METHOD(removeGroupedNotifications:(NSString *)group) {
  if (group.length == 0) return;
  [[UNUserNotificationCenter currentNotificationCenter] getDeliveredNotificationsWithCompletionHandler:^(NSArray<UNNotification *> *delivered) {
    NSMutableArray<NSString *> *ids = [NSMutableArray array];
    for (UNNotification *notification in delivered) {
      if ([notification.request.content.threadIdentifier isEqualToString:group]) {
        [ids addObject:notification.request.identifier];
      }
    }
    if (ids.count > 0) {
      [[UNUserNotificationCenter currentNotificationCenter] removeDeliveredNotificationsWithIdentifiers:ids];
    }
  }];
}

RCT_EXPORT_METHOD(clearAllNotifications) {
  [[UNUserNotificationCenter currentNotificationCenter] removeAllDeliveredNotifications];
  dispatch_async(dispatch_get_main_queue(), ^{
    [UIApplication sharedApplication].applicationIconBadgeNumber = 0;
  });
}

RCT_EXPORT_METHOD(setBadgeCount:(double)count) {
  dispatch_async(dispatch_get_main_queue(), ^{
    [UIApplication sharedApplication].applicationIconBadgeNumber = (NSInteger)count;
  });
}

RCT_EXPORT_METHOD(getInitialClickEvent:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  resolve((id)kCFNull);
}

@end
