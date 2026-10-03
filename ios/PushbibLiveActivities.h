#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface PushbibLiveActivities : NSObject

+ (void)reportActivityToken:(NSString *)tokenHex
                forActivity:(NSString *)activityId
             attributesType:(nullable NSString *)attributesType;

+ (void)reportPushToStartToken:(NSString *)tokenHex
                attributesType:(nullable NSString *)attributesType;

+ (NSString *)hexStringFromData:(NSData *)data;

@end

NS_ASSUME_NONNULL_END
