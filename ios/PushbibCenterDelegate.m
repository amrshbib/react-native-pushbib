#import "PushbibCenterDelegate.h"
#import "PushbibEventBus.h"

@implementation PushbibCenterDelegate

+ (instancetype)shared {
  static PushbibCenterDelegate *instance;
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    instance = [PushbibCenterDelegate new];
  });
  return instance;
}

static BOOL PushbibFlag(NSDictionary *userInfo, NSString *key, BOOL fallback) {
  id raw = userInfo[key];
  if (raw == nil || raw == NSNull.null) return fallback;
  if ([raw isKindOfClass:NSNumber.class]) return [raw boolValue];
  NSString *value = [[raw description] lowercaseString];
  if ([value isEqualToString:@"false"] || [value isEqualToString:@"0"] || [value isEqualToString:@"no"]) {
    return NO;
  }
  return YES;
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler {
  NSDictionary *userInfo = notification.request.content.userInfo ?: @{};

  [[PushbibEventBus shared]
      postEvent:PushbibEventReceived
           body:@{@"notification" : [PushbibCenterDelegate normalizeContent:notification.request.content]}];

  if ([[[NSUserDefaults standardUserDefaults] stringForKey:@"pushbib_opted_out"] isEqualToString:@"1"]) {
    completionHandler(UNNotificationPresentationOptionNone);
    return;
  }

  if (!PushbibFlag(userInfo, @"showForeground", YES)) {
    completionHandler(UNNotificationPresentationOptionNone);
    return;
  }

  if ([[[NSUserDefaults standardUserDefaults] stringForKey:@"pushbib_show_foreground"] isEqualToString:@"0"]) {
    completionHandler(UNNotificationPresentationOptionNone);
    return;
  }

  id<UNUserNotificationCenterDelegate> original = self.original;
  if (original && [original respondsToSelector:_cmd]) {
    [original userNotificationCenter:center
             willPresentNotification:notification
               withCompletionHandler:completionHandler];
    return;
  }

  UNNotificationPresentationOptions show;
  if (@available(iOS 14.0, *)) {
    show = UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList |
           UNNotificationPresentationOptionSound | UNNotificationPresentationOptionBadge;
  } else {
    show = UNNotificationPresentationOptionAlert | UNNotificationPresentationOptionSound |
           UNNotificationPresentationOptionBadge;
  }

  PushbibEventBus *bus = [PushbibEventBus shared];
  if (!bus.hasForegroundHandler) {
    completionHandler(show);
    return;
  }

  NSString *completionId = [[NSUUID UUID] UUIDString];
  [bus storeDisplayDecision:completionId
                      block:^(BOOL display) {
                        completionHandler(display ? show : UNNotificationPresentationOptionNone);
                      }
                    timeout:5.0];
  [bus postEvent:PushbibEventForegroundWillDisplay
            body:@{
              @"completionId" : completionId,
              @"notification" : [PushbibCenterDelegate normalizeContent:notification.request.content],
            }];
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
    didReceiveNotificationResponse:(UNNotificationResponse *)response
             withCompletionHandler:(void (^)(void))completionHandler {
  NSString *actionId = nil;
  if (![response.actionIdentifier isEqualToString:UNNotificationDefaultActionIdentifier] &&
      ![response.actionIdentifier isEqualToString:UNNotificationDismissActionIdentifier]) {
    actionId = response.actionIdentifier;
  }

  BOOL dismissed = [response.actionIdentifier isEqualToString:UNNotificationDismissActionIdentifier];
  if (!dismissed) {
    NSMutableDictionary *body = [NSMutableDictionary new];
    body[@"notification"] = [PushbibCenterDelegate normalizeContent:response.notification.request.content];
    if (actionId) body[@"actionId"] = actionId;
    [[PushbibEventBus shared] postEvent:PushbibEventClick body:body];
  }

  id<UNUserNotificationCenterDelegate> original = self.original;
  if (original && [original respondsToSelector:_cmd]) {
    [original userNotificationCenter:center
        didReceiveNotificationResponse:response
                 withCompletionHandler:completionHandler];
  } else {
    completionHandler();
  }
}

+ (NSDictionary *)normalizeContent:(UNNotificationContent *)content {
  NSDictionary *userInfo = content.userInfo ?: @{};
  NSSet *controlKeys = [NSSet setWithArray:@[
    @"aps", @"url", @"imageUrl", @"largeIcon", @"buttons", @"pushbib_payload",
    @"showForeground", @"showBackground"
  ]];

  NSMutableDictionary *data = [NSMutableDictionary new];
  for (NSString *key in userInfo) {
    if (![key isKindOfClass:NSString.class]) continue;
    if ([controlKeys containsObject:key]) continue;
    if ([key hasPrefix:@"gcm."] || [key hasPrefix:@"google."]) continue;
    data[key] = userInfo[key];
  }

  NSMutableDictionary *notification = [NSMutableDictionary new];
  if (content.title.length) notification[@"title"] = content.title;
  if (content.subtitle.length) notification[@"subtitle"] = content.subtitle;
  if (content.body.length) notification[@"body"] = content.body;
  if (userInfo[@"url"]) notification[@"url"] = [userInfo[@"url"] description];
  if (userInfo[@"imageUrl"]) notification[@"imageUrl"] = [userInfo[@"imageUrl"] description];
  notification[@"data"] = data;
  return notification;
}

@end
