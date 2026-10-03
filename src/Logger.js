const LEVELS = { none: 0, error: 1, warn: 2, info: 3, verbose: 4 };

let current = LEVELS.warn;

// OneSignal's LogLevel enum (None 0 … Verbose 6) mapped onto our five levels, so
// Debug.setLogLevel(LogLevel.Verbose) and setLogLevel('verbose') both work.
const NUMERIC_LEVELS = [LEVELS.none, LEVELS.error, LEVELS.error, LEVELS.warn, LEVELS.info, LEVELS.verbose, LEVELS.verbose];

export function setLogLevel(level) {
  if (typeof level === 'number') {
    const mapped = NUMERIC_LEVELS[level];
    if (mapped === undefined) {
      console.warn(`[Pushbib] Unknown log level ${level} — use 0..${NUMERIC_LEVELS.length - 1} or ${Object.keys(LEVELS).join('/')}`);
      return;
    }
    current = mapped;
    return;
  }
  if (LEVELS[level] === undefined) {
    console.warn(`[Pushbib] Unknown log level "${level}" — use ${Object.keys(LEVELS).join('/')}`);
    return;
  }
  current = LEVELS[level];
}

export const log = {
  error: (...args) => current >= LEVELS.error && console.error('[Pushbib]', ...args),
  warn: (...args) => current >= LEVELS.warn && console.warn('[Pushbib]', ...args),
  info: (...args) => current >= LEVELS.info && console.log('[Pushbib]', ...args),
  verbose: (...args) => current >= LEVELS.verbose && console.log('[Pushbib]', ...args),
};
