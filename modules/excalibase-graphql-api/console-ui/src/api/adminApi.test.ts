import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { ApiError, adminApi } from './adminApi';

const KEY = 'excalibase.console.session';
const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  window.sessionStorage.clear();
  adminApi.reset();
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

describe('the admin api', () => {
  test('a secret is kept only once the engine accepts it, and travels only in the header', async () => {
    fetchMock.mockResolvedValueOnce(reply(200, { replicas: [] }));

    await adminApi.signIn('s3cret');

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit & { headers: Record<string, string> }];
    expect(url).toBe('/_admin/v1/replicas');
    expect(init.headers['X-Excalibase-Admin-Secret']).toBe('s3cret');
    expect(init.credentials).toBe('omit');
    expect(window.sessionStorage.getItem(KEY)).toBe('s3cret');
    expect(adminApi.signedIn()).toBe(true);
  });

  test('a refused secret is forgotten and the failure carries its code', async () => {
    fetchMock.mockResolvedValueOnce(reply(401, { error: 'unauthorized' }));

    await expect(adminApi.signIn('nope')).rejects.toMatchObject({ status: 401, code: 'unauthorized' });

    expect(window.sessionStorage.getItem(KEY)).toBeNull();
    expect(adminApi.signedIn()).toBe(false);
  });

  test('a 401 on any later call ends the session', async () => {
    fetchMock.mockResolvedValueOnce(reply(200, {}));
    await adminApi.signIn('s');
    fetchMock.mockResolvedValueOnce(reply(401, { error: 'unauthorized' }));

    await expect(adminApi.schema()).rejects.toBeInstanceOf(ApiError);

    expect(adminApi.signedIn()).toBe(false);
  });

  test('a save is plain text with the version it started from quoted in If-Match', async () => {
    fetchMock.mockResolvedValueOnce(reply(200, { version: 'v2' }));

    await adminApi.putMetadata('format: 1\n', 'v1');

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit & { headers: Record<string, string> }];
    expect(url).toBe('/_admin/v1/metadata');
    expect(init.method).toBe('PUT');
    expect(init.headers['If-Match']).toBe('"v1"');
    expect(init.headers['Content-Type']).toBe('text/plain');
    expect(init.body).toBe('format: 1\n');
  });

  test('metadata comes back with the document as text', async () => {
    fetchMock.mockResolvedValueOnce(reply(200, { document: 'x', sourceVersion: 's', writable: true }));

    expect(await adminApi.getMetadata()).toMatchObject({ text: 'x', sourceVersion: 's' });
  });

  test('a history id is encoded into the path, never joined raw', async () => {
    fetchMock.mockResolvedValueOnce(reply(200, {}));

    await adminApi.historyEntry('1/../x');

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/_admin/v1/history/1%2F..%2Fx');
  });

  test('an answer that is not JSON still fails with a status code', async () => {
    fetchMock.mockResolvedValueOnce(new Response('oops', { status: 502 }));

    await expect(adminApi.replicas()).rejects.toMatchObject({ status: 502, code: 'error_502' });
  });
});
