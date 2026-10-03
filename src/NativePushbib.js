import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

const LINKING_ERROR =
  `@amrshbib/react-native-pushbib: the native module is not linked.\n` +
  Platform.select({
    ios: '- Run "cd ios && pod install"\n',
    android: '- Rebuild the app (autolinking picks the module up at build time)\n',
    default: '- This platform is not supported (iOS and Android only)\n',
  }) +
  '- Restart Metro and rebuild the app after installing the package.';

const native = NativeModules.Pushbib;

export const PushbibNative =
  native ||
  new Proxy(
    {},
    {
      get() {
        throw new Error(LINKING_ERROR);
      },
    },
  );

export const isNativeAvailable = !!native;

export const nativeEmitter = native ? new NativeEventEmitter(native) : null;

export const NATIVE_EVENTS = {
  pushToken: 'Pushbib#pushToken',
  click: 'Pushbib#click',
  received: 'Pushbib#received',
  foregroundWillDisplay: 'Pushbib#foregroundWillDisplay',
  liveUpdate: 'Pushbib#liveUpdate',
  liveActivityToken: 'Pushbib#liveActivityToken',
};
