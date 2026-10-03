#import "PushbibLiveActivities.h"
#import "PushbibEventBus.h"

@implementation PushbibLiveActivities

+ (void)reportActivityToken:(NSString *)tokenHex
                forActivity:(NSString *)activityId
             attributesType:(NSString *)attributesType {
  if (tokenHex.length == 0 || activityId.length == 0) {
    NSLog(@"[Pushbib] reportActivityToken ignored — token and activityId are both required");
    return;
  }
  NSMutableDictionary *body = [@{
    @"kind": @"activity",
    @"token": tokenHex,
    @"activityId": activityId,
  } mutableCopy];
  if (attributesType.length > 0) body[@"attributesType"] = attributesType;

  [[PushbibEventBus shared] postEvent:PushbibEventLiveActivityToken body:body];
}

+ (void)reportPushToStartToken:(NSString *)tokenHex
                attributesType:(NSString *)attributesType {
  if (tokenHex.length == 0) {
    NSLog(@"[Pushbib] reportPushToStartToken ignored — empty token");
    return;
  }
  NSMutableDictionary *body = [@{
    @"kind": @"pushToStart",
    @"token": tokenHex,
  } mutableCopy];
  if (attributesType.length > 0) body[@"attributesType"] = attributesType;

  [[PushbibEventBus shared] postEvent:PushbibEventLiveActivityToken body:body];
}

+ (NSString *)hexStringFromData:(NSData *)data {
  if (data.length == 0) return @"";
  const unsigned char *bytes = data.bytes;
  NSMutableString *hex = [NSMutableString stringWithCapacity:data.length * 2];
  for (NSUInteger i = 0; i < data.length; i++) [hex appendFormat:@"%02x", bytes[i]];
  return hex;
}

@end
