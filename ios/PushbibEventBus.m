#import "PushbibEventBus.h"

NSString *const PushbibEventPushToken = @"Pushbib#pushToken";
NSString *const PushbibEventClick = @"Pushbib#click";
NSString *const PushbibEventReceived = @"Pushbib#received";
NSString *const PushbibEventForegroundWillDisplay = @"Pushbib#foregroundWillDisplay";
NSString *const PushbibEventLiveActivityToken = @"Pushbib#liveActivityToken";

NSString *const PushbibDeviceTokenNotification = @"PushbibDeviceTokenNotification";
NSString *const PushbibDeviceTokenErrorNotification = @"PushbibDeviceTokenErrorNotification";

static const NSUInteger kQueueCap = 20;

@interface PushbibEventBus ()
@property (nonatomic, strong) NSMutableArray<NSArray *> *queue;
@property (nonatomic, copy, nullable) PushbibEventHandler handler;
@property (nonatomic, strong) NSMutableDictionary<NSString *, void (^)(BOOL)> *pendingDisplays;
@end

@implementation PushbibEventBus

+ (instancetype)shared {
  static PushbibEventBus *instance;
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    instance = [PushbibEventBus new];
  });
  return instance;
}

- (instancetype)init {
  if (self = [super init]) {
    _queue = [NSMutableArray new];
    _pendingDisplays = [NSMutableDictionary new];
  }
  return self;
}

- (void)postEvent:(NSString *)name body:(NSDictionary *)body {
  PushbibEventHandler handler;
  @synchronized (self) {
    handler = self.handler;
    if (!handler) {
      if (self.queue.count >= kQueueCap) [self.queue removeObjectAtIndex:0];
      [self.queue addObject:@[ name, body ]];
      return;
    }
  }
  handler(name, body);
}

- (void)setHandler:(PushbibEventHandler)handler {
  NSArray<NSArray *> *drained = nil;
  @synchronized (self) {
    _handler = [handler copy];
    if (handler && self.queue.count > 0) {
      drained = [self.queue copy];
      [self.queue removeAllObjects];
    }
  }
  if (handler) {
    for (NSArray *entry in drained) handler(entry[0], entry[1]);
  }
}

- (void)storeDisplayDecision:(NSString *)completionId
                       block:(void (^)(BOOL))block
                     timeout:(NSTimeInterval)timeout {
  @synchronized (self.pendingDisplays) {
    self.pendingDisplays[completionId] = [block copy];
  }
  __weak typeof(self) weakSelf = self;
  dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(timeout * NSEC_PER_SEC)),
                 dispatch_get_main_queue(), ^{
    [weakSelf completeDisplay:completionId display:YES];
  });
}

- (void)completeDisplay:(NSString *)completionId display:(BOOL)display {
  void (^block)(BOOL);
  @synchronized (self.pendingDisplays) {
    block = self.pendingDisplays[completionId];
    if (block) [self.pendingDisplays removeObjectForKey:completionId];
  }
  if (block) block(display);
}

@end
