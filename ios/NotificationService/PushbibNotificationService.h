#import <UserNotifications/UserNotifications.h>

NS_ASSUME_NONNULL_BEGIN

/// The whole iOS half of notification images, ready to use.
///
/// iOS only puts a picture in a notification if the app ships a Notification Service
/// Extension that downloads the URL and attaches it — there is no server-side way to do it.
/// This class IS that extension: point the target's Info.plist at it and write no code.
///
///     <key>NSExtension</key>
///     <dict>
///       <key>NSExtensionPointIdentifier</key>
///       <string>com.apple.usernotifications.service</string>
///       <key>NSExtensionPrincipalClass</key>
///       <string>PushbibNotificationService</string>
///     </dict>
///
/// The URL is read from whichever key the send went out on: `imageUrl` for a direct APNs
/// push, `fcm_options.image` for one routed through FCM.
///
/// Subclass it only to change something — override `willDeliverContent:` to edit the
/// notification just before it is shown, or `imageURLKeys` to read your own key — and then
/// name the subclass as the principal class instead.
@interface PushbibNotificationService : UNNotificationServiceExtension

/// Where to look for the picture, in order. Override to add your own key.
+ (NSArray<NSString *> *)imageURLKeys;

/// Called once the content is final, just before it is handed to iOS. Override to add your
/// own changes; the default does nothing.
- (void)willDeliverContent:(UNMutableNotificationContent *)content;

@end

NS_ASSUME_NONNULL_END
