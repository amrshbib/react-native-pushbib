#import "PushbibNotificationService.h"

// iOS gives a service extension about 30 seconds and then shows the notification without
// whatever it was still doing. Give up well before that so a slow image never costs the
// user the notification itself.
static NSTimeInterval const kPushbibImageTimeout = 12.0;

// Anything larger is not worth the wait on a phone network — the notification arrives with
// its text instead of hanging on the download.
static long long const kPushbibMaxImageBytes = 10LL * 1024 * 1024;

@interface PushbibNotificationService ()
@property (nonatomic, copy, nullable) void (^contentHandler)(UNNotificationContent *);
@property (nonatomic, strong, nullable) UNMutableNotificationContent *content;
@property (nonatomic, strong, nullable) NSURLSessionDownloadTask *task;
@property (nonatomic, strong, nullable) NSURL *attachedFile;
@end

@implementation PushbibNotificationService

+ (NSArray<NSString *> *)imageURLKeys {
  // A direct APNs push carries `imageUrl`; one routed through FCM carries the same URL under
  // `fcm_options.image`. Reading both means the extension works either way.
  return @[ @"imageUrl", @"fcm_options.image" ];
}

- (void)willDeliverContent:(UNMutableNotificationContent *)content {
  // Nothing by default.
}

#pragma mark - UNNotificationServiceExtension

- (void)didReceiveNotificationRequest:(UNNotificationRequest *)request
                   withContentHandler:(void (^)(UNNotificationContent *))contentHandler {
  self.contentHandler = contentHandler;
  self.content = [request.content mutableCopy];

  NSURL *url = [self imageURLInUserInfo:request.content.userInfo];
  if (url == nil) {
    [self deliver];
    return;
  }

  NSURLSessionConfiguration *config = NSURLSessionConfiguration.ephemeralSessionConfiguration;
  config.timeoutIntervalForResource = kPushbibImageTimeout;
  config.timeoutIntervalForRequest = kPushbibImageTimeout;

  __weak typeof(self) weakSelf = self;
  self.task = [[NSURLSession sessionWithConfiguration:config]
      downloadTaskWithURL:url
        completionHandler:^(NSURL *location, NSURLResponse *response, NSError *error) {
          [weakSelf attachDownloadAt:location response:response error:error sourceURL:url];
          [weakSelf deliver];
        }];
  [self.task resume];
}

- (void)serviceExtensionTimeWillExpire {
  // Out of time: show the text rather than letting iOS drop our changes entirely.
  [self.task cancel];
  [self deliver];
}

#pragma mark - Reading the URL

- (nullable NSURL *)imageURLInUserInfo:(NSDictionary *)userInfo {
  for (NSString *key in [self.class imageURLKeys]) {
    id raw = [self valueAtKeyPath:key inDictionary:userInfo];
    if (![raw isKindOfClass:NSString.class]) continue;

    NSString *text = [(NSString *)raw stringByTrimmingCharactersInSet:
                                         NSCharacterSet.whitespaceAndNewlineCharacterSet];
    if (text.length == 0) continue;

    NSURL *url = [NSURL URLWithString:text];
    // App Transport Security blocks plain http with no way to report it, so a non-https URL
    // would fail silently. Skipping it here at least leaves a readable reason in the log.
    if (url.scheme.length == 0 || url.host.length == 0) continue;
    if (![url.scheme.lowercaseString isEqualToString:@"https"]) {
      NSLog(@"[Pushbib] notification image skipped, iOS only loads https: %@", text);
      continue;
    }
    return url;
  }
  return nil;
}

- (nullable id)valueAtKeyPath:(NSString *)path inDictionary:(NSDictionary *)dictionary {
  id node = dictionary;
  for (NSString *part in [path componentsSeparatedByString:@"."]) {
    if (![node isKindOfClass:NSDictionary.class]) return nil;
    node = ((NSDictionary *)node)[part];
    if (node == nil || node == NSNull.null) return nil;
  }
  return node;
}

#pragma mark - Attaching

- (void)attachDownloadAt:(nullable NSURL *)location
                response:(nullable NSURLResponse *)response
                   error:(nullable NSError *)error
               sourceURL:(NSURL *)sourceURL {
  if (error != nil || location == nil) {
    NSLog(@"[Pushbib] notification image download failed (%@): %@", sourceURL, error.localizedDescription);
    return;
  }

  if ([response isKindOfClass:NSHTTPURLResponse.class]) {
    NSInteger status = ((NSHTTPURLResponse *)response).statusCode;
    if (status < 200 || status > 299) {
      NSLog(@"[Pushbib] notification image got HTTP %ld (%@)", (long)status, sourceURL);
      return;
    }
  }
  if (response.expectedContentLength > kPushbibMaxImageBytes) {
    NSLog(@"[Pushbib] notification image is too large (%lld bytes)", response.expectedContentLength);
    return;
  }

  // UNNotificationAttachment decides the type from the file extension and rejects a file
  // without a usable one, so the temp copy has to be named for what the server actually sent.
  NSString *extension = [self extensionForResponse:response sourceURL:sourceURL];
  if (extension == nil) {
    NSLog(@"[Pushbib] notification image is not a type iOS can attach (%@)", response.MIMEType);
    return;
  }

  NSURL *destination = [[NSURL fileURLWithPath:NSTemporaryDirectory()]
      URLByAppendingPathComponent:[NSUUID.UUID.UUIDString stringByAppendingPathExtension:extension]];

  NSError *moveError = nil;
  if (![NSFileManager.defaultManager moveItemAtURL:location toURL:destination error:&moveError]) {
    NSLog(@"[Pushbib] notification image could not be staged: %@", moveError.localizedDescription);
    return;
  }
  self.attachedFile = destination;

  NSError *attachError = nil;
  UNNotificationAttachment *attachment = [UNNotificationAttachment attachmentWithIdentifier:@"pushbib-image"
                                                                                        URL:destination
                                                                                    options:nil
                                                                                      error:&attachError];
  if (attachment == nil) {
    NSLog(@"[Pushbib] notification image could not be attached: %@", attachError.localizedDescription);
    [NSFileManager.defaultManager removeItemAtURL:destination error:nil];
    self.attachedFile = nil;
    return;
  }

  self.content.attachments = @[ attachment ];
}

- (nullable NSString *)extensionForResponse:(nullable NSURLResponse *)response sourceURL:(NSURL *)sourceURL {
  NSDictionary<NSString *, NSString *> *byMimeType = @{
    @"image/jpeg" : @"jpg",
    @"image/jpg" : @"jpg",
    @"image/png" : @"png",
    @"image/gif" : @"gif",
    @"image/webp" : @"webp",
  };

  NSString *mimeType = response.MIMEType.lowercaseString;
  NSString *fromMimeType = mimeType != nil ? byMimeType[mimeType] : nil;
  if (fromMimeType != nil) return fromMimeType;

  // No usable Content-Type: fall back to the extension the URL carries, which is how the
  // Pushbib backend names its image URLs.
  NSString *fromPath = sourceURL.pathExtension.lowercaseString;
  if (fromPath.length > 0 && [byMimeType.allValues containsObject:fromPath]) return fromPath;
  if ([fromPath isEqualToString:@"jpeg"]) return @"jpg";

  return nil;
}

#pragma mark - Delivering

- (void)deliver {
  // Both the download callback and the expiry handler can land here; whichever is second
  // must not call iOS back a second time.
  void (^handler)(UNNotificationContent *) = self.contentHandler;
  if (handler == nil) return;
  self.contentHandler = nil;

  UNMutableNotificationContent *content = self.content;
  if (content == nil) return;

  [self willDeliverContent:content];
  handler(content);
}

@end
