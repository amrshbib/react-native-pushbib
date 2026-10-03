#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^PushbibEventHandler)(NSString *name, NSDictionary *body);

extern NSString *const PushbibEventPushToken;
extern NSString *const PushbibEventClick;
extern NSString *const PushbibEventReceived;
extern NSString *const PushbibEventForegroundWillDisplay;
extern NSString *const PushbibEventLiveActivityToken;

extern NSString *const PushbibDeviceTokenNotification;
extern NSString *const PushbibDeviceTokenErrorNotification;

@interface PushbibEventBus : NSObject

+ (instancetype)shared;

@property (atomic, copy, nullable) NSString *deviceTokenHex;
@property (atomic, copy, nullable) NSString *deviceTokenErrorMessage;
@property (atomic, assign) BOOL hasForegroundHandler;

- (void)postEvent:(NSString *)name body:(NSDictionary *)body;
- (void)setHandler:(nullable PushbibEventHandler)handler;

- (void)storeDisplayDecision:(NSString *)completionId
                       block:(void (^)(BOOL display))block
                     timeout:(NSTimeInterval)timeout;
- (void)completeDisplay:(NSString *)completionId display:(BOOL)display;

@end

NS_ASSUME_NONNULL_END
