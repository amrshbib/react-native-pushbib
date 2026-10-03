import { log } from './Logger';

const TIMEOUT_MS = 15000;

export class ApiError extends Error {
  constructor(status, message, body) {
    super(message);
    this.name = 'PushbibApiError';
    this.status = status;
    this.body = body;
  }
}

export class ApiClient {
  configure({ baseUrl, appId, appKey, bundleId }) {
    this.baseUrl = baseUrl.replace(/\/+$/, '') + '/api/mobile/v1';
    this.headers = {
      'Content-Type': 'application/json',
      'X-Project-Id': appId,
      Authorization: `Bearer ${appKey}`,
      'X-Bundle-Id': bundleId,
    };
  }

  async request(method, path, body) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
    let res;
    try {
      res = await fetch(`${this.baseUrl}${path}`, {
        method,
        headers: this.headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
      });
    } catch (e) {
      throw new ApiError(0, `Network error calling ${path}: ${e.message}`, null);
    } finally {
      clearTimeout(timer);
    }

    let json = null;
    try {
      json = await res.json();
    } catch (e) {
    }

    if (!res.ok) {
      throw new ApiError(res.status, json?.message || `HTTP ${res.status} on ${path}`, json);
    }
    log.verbose(`${method} ${path} → ${res.status}`);
    return json;
  }

  get(path) {
    return this.request('GET', path);
  }
  post(path, body) {
    return this.request('POST', path, body);
  }
  delete(path, body) {
    return this.request('DELETE', path, body);
  }

  getConfig() {
    return this.get('/config');
  }
  registerDevice(payload) {
    return this.post('/devices/register', payload);
  }
  unregisterDevice(payload) {
    return this.post('/devices/unregister', payload);
  }
  pingSession(payload) {
    return this.post('/devices/session', payload);
  }
  setProfile(payload) {
    return this.post('/devices/profile', payload);
  }
  setLocation(payload) {
    return this.post('/devices/location', payload);
  }
  clearLocation(payload) {
    return this.delete('/devices/location', payload);
  }
  link(payload) {
    return this.post('/auth/link', payload);
  }
  unlink(payload) {
    return this.post('/auth/unlink', payload);
  }
  me(payload) {
    return this.post('/me', payload);
  }
  addTags(payload) {
    return this.post('/tags', payload);
  }
  removeTags(payload) {
    return this.delete('/tags', payload);
  }

  registerLiveActivity(payload) {
    return this.post('/live-activity/register', payload);
  }
  endLiveActivity(payload) {
    return this.post('/live-activity/end', payload);
  }
  removePushToStart(payload) {
    return this.delete('/live-activity/push-to-start', payload);
  }
  liveActivitySessions(subscriptionId) {
    return this.get(`/live-activity/sessions?subscription_id=${encodeURIComponent(subscriptionId)}`);
  }
}
