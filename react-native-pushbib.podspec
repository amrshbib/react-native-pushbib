require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "react-native-pushbib"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = { "Amr Shbib" => "amr.shbib@mythings.app" }
  s.platforms    = { :ios => "13.0" }
  s.source       = { :git => package["repository"]["url"], :tag => "v#{s.version}" }

  # The extension is NOT a pod. Its target compiles ios/NotificationService straight out of
  # node_modules and links only UserNotifications, so an app adds the target and nothing
  # else — no Podfile entry, no pod install, no file of its own to keep in sync.
  s.source_files = "ios/*.{h,m,mm}"
  s.frameworks   = "UserNotifications", "Security"

  s.dependency "React-Core"
end
