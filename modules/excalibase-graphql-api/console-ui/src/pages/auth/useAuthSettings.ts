import { ApiError, getAuth, type AuthSettings } from '../../api';
import { useRequest, type Request } from '../../session';

/**
 * The engine's token settings, or null when this engine does not offer the Auth page (the sub-flag is off, so the
 * call is a 404). Any other failure is the request's error, never a page that pretends the settings are empty.
 */
export function useAuthSettings(): Request<AuthSettings | null> {
  return useRequest(async () => {
    try {
      return await getAuth();
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 404) {
        return null;
      }
      throw failure;
    }
  }, []);
}
