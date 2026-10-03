#import <UIKit/UIKit.h>
#import <UserNotifications/UserNotifications.h>
#import <objc/message.h>
#import <objc/runtime.h>

#import "PushbibCenterDelegate.h"
#import "PushbibEventBus.h"

static IMP gOriginalAppSetDelegate = NULL;
static IMP gOriginalCenterSetDelegate = NULL;

static NSString *const kOriginalPrefix = @"pushbib_original_";

static void CallOriginal2(id self, SEL sel, id arg1, id arg2) {
  if ([self respondsToSelector:sel]) {
    ((void (*)(id, SEL, id, id))objc_msgSend)(self, sel, arg1, arg2);
  }
}

static SEL OriginalSelector(SEL sel) {
  return NSSelectorFromString([kOriginalPrefix stringByAppendingString:NSStringFromSelector(sel)]);
}

static void Pushbib_didRegisterForRemoteNotifications(id self, SEL _cmd, UIApplication *application, NSData *deviceToken) {
  NSMutableString *hex = [NSMutableString stringWithCapacity:deviceToken.length * 2];
  const unsigned char *bytes = deviceToken.bytes;
  for (NSUInteger i = 0; i < deviceToken.length; i++) [hex appendFormat:@"%02x", bytes[i]];

  PushbibEventBus *bus = [PushbibEventBus shared];
  bus.deviceTokenHex = hex;
  bus.deviceTokenErrorMessage = nil;
  [[NSNotificationCenter defaultCenter] postNotificationName:PushbibDeviceTokenNotification
                                                      object:nil
                                                    userInfo:@{@"token" : hex}];
  [bus postEvent:PushbibEventPushToken body:@{@"token" : hex}];

  CallOriginal2(self, OriginalSelector(@selector(application:didRegisterForRemoteNotificationsWithDeviceToken:)),
                application, deviceToken);
}

static void Pushbib_didFailToRegisterForRemoteNotifications(id self, SEL _cmd, UIApplication *application, NSError *error) {
  NSString *message = error.localizedDescription ?: @"APNs registration failed";
  [PushbibEventBus shared].deviceTokenErrorMessage = message;
  [[NSNotificationCenter defaultCenter] postNotificationName:PushbibDeviceTokenErrorNotification
                                                      object:nil
                                                    userInfo:@{@"message" : message}];

  CallOriginal2(self, OriginalSelector(@selector(application:didFailToRegisterForRemoteNotificationsWithError:)),
                application, error);
}

@interface PushbibSwizzler : NSObject
@end

@implementation PushbibSwizzler

+ (void)load {
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    [self hookApplicationSetDelegate];
    [self hookCenterSetDelegate];
  });
}

+ (void)hookApplicationSetDelegate {
  Method method = class_getInstanceMethod(UIApplication.class, @selector(setDelegate:));
  gOriginalAppSetDelegate = method_setImplementation(
      method, imp_implementationWithBlock(^(UIApplication *application, id<UIApplicationDelegate> delegate) {
        if (delegate) [PushbibSwizzler injectIntoDelegateClass:object_getClass(delegate)];
        [PushbibSwizzler installCenterDelegateProxy];
        ((void (*)(id, SEL, id))gOriginalAppSetDelegate)(application, @selector(setDelegate:), delegate);
      }));
}

+ (void)injectIntoDelegateClass:(Class)cls {
  static NSMutableSet<NSString *> *injected;
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    injected = [NSMutableSet new];
  });
  NSString *name = NSStringFromClass(cls) ?: @"?";
  @synchronized (injected) {
    if ([injected containsObject:name]) return;
    [injected addObject:name];
  }

  [self injectSelector:@selector(application:didRegisterForRemoteNotificationsWithDeviceToken:)
             intoClass:cls
        implementation:(IMP)Pushbib_didRegisterForRemoteNotifications];
  [self injectSelector:@selector(application:didFailToRegisterForRemoteNotificationsWithError:)
             intoClass:cls
        implementation:(IMP)Pushbib_didFailToRegisterForRemoteNotifications];
}

+ (void)injectSelector:(SEL)selector intoClass:(Class)cls implementation:(IMP)imp {
  const char *typeEncoding = "v@:@@";
  Method existing = class_getInstanceMethod(cls, selector);
  if (existing) {
    class_addMethod(cls, OriginalSelector(selector), method_getImplementation(existing),
                    method_getTypeEncoding(existing));
    if (!class_addMethod(cls, selector, imp, typeEncoding)) {
      method_setImplementation(class_getInstanceMethod(cls, selector), imp);
    }
  } else {
    class_addMethod(cls, selector, imp, typeEncoding);
  }
}

+ (void)installCenterDelegateProxy {
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
    PushbibCenterDelegate *proxy = [PushbibCenterDelegate shared];
    id<UNUserNotificationCenterDelegate> existing = center.delegate;
    if (existing && existing != (id)proxy) proxy.original = existing;
    center.delegate = proxy;
  });
}

+ (void)hookCenterSetDelegate {
  Method method = class_getInstanceMethod(UNUserNotificationCenter.class, @selector(setDelegate:));
  gOriginalCenterSetDelegate = method_setImplementation(
      method,
      imp_implementationWithBlock(^(UNUserNotificationCenter *center, id<UNUserNotificationCenterDelegate> delegate) {
        PushbibCenterDelegate *proxy = [PushbibCenterDelegate shared];
        if (delegate == nil || delegate == (id)proxy) {
          ((void (*)(id, SEL, id))gOriginalCenterSetDelegate)(center, @selector(setDelegate:), delegate);
          return;
        }
        proxy.original = delegate;
        ((void (*)(id, SEL, id))gOriginalCenterSetDelegate)(center, @selector(setDelegate:), proxy);
      }));
}

@end
