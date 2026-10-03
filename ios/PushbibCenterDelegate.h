#import <Foundation/Foundation.h>
#import <UserNotifications/UserNotifications.h>

NS_ASSUME_NONNULL_BEGIN

@interface PushbibCenterDelegate : NSObject <UNUserNotificationCenterDelegate>

+ (instancetype)shared;

@property (nonatomic, weak, nullable) id<UNUserNotificationCenterDelegate> original;

+ (NSDictionary *)normalizeContent:(UNNotificationContent *)content;

@end

NS_ASSUME_NONNULL_END
