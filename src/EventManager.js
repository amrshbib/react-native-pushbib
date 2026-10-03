import { log } from './Logger';

const VALID = [
  'click', 'received', 'foregroundWillDisplay', 'permissionChange', 'pushSubscriptionChange',
  'liveUpdate',
];
const BUFFERED = new Set(['click', 'received']);
const BUFFER_CAP = 20;

export class EventManager {
  constructor() {
    this.listeners = new Map(VALID.map((n) => [n, new Set()]));
    this.pending = new Map([...BUFFERED].map((n) => [n, []]));
  }

  add(event, handler) {
    const set = this.listeners.get(event);
    if (!set) {
      log.warn(`Unknown event "${event}" — valid events: ${VALID.join(', ')}`);
      return;
    }
    set.add(handler);
    const queue = this.pending.get(event);
    if (queue && queue.length > 0) {
      const drained = queue.splice(0);
      for (const ev of drained) this.emit(event, ev);
    }
  }

  remove(event, handler) {
    this.listeners.get(event)?.delete(handler);
  }

  count(event) {
    return this.listeners.get(event)?.size || 0;
  }

  emit(event, payload) {
    const set = this.listeners.get(event);
    if (BUFFERED.has(event) && (!set || set.size === 0)) {
      const queue = this.pending.get(event);
      if (queue.length >= BUFFER_CAP) queue.shift();
      queue.push(payload);
      return false;
    }
    for (const handler of set || []) {
      try {
        handler(payload);
      } catch (e) {
        log.error(`"${event}" listener threw:`, e);
      }
    }
    return (set?.size || 0) > 0;
  }
}
