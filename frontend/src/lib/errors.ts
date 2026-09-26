import { AxiosError } from 'axios';

interface ApiErrorBody {
  message?: string;
  details?: string[];
  error?: string;
  traceId?: string;
  success?: boolean;
}

/**
 * Turns a thrown request error into something worth showing a user.
 *
 * Priority is the server's own message, then its validation details, then a
 * status-specific fallback. A raw "Request failed with status code 409" tells
 * the user nothing about what to do differently.
 *
 * Note this deliberately never surfaces a traceId to the user for a 5xx: the
 * server logs the detail against it, and the id alone is not actionable in the
 * UI. It is returned here so a caller can log it if it wants.
 */
export function describeApiError(error: unknown): string {
  if (error instanceof AxiosError) {
    const body = error.response?.data as ApiErrorBody | undefined;

    if (body?.details?.length) {
      return body.details.join('. ');
    }
    if (body?.message) {
      return body.message;
    }

    switch (error.response?.status) {
      case 400:
        return 'Some of the details are not valid. Check the fields and try again.';
      case 401:
        return 'Invalid credentials.';
      case 403:
        return 'You do not have permission to do that.';
      case 404:
        return 'Not found.';
      case 409:
        return 'That already exists.';
      case 429:
        return 'Too many attempts. Wait a few minutes and try again.';
      case undefined:
        // No response at all: the request never reached a server.
        return 'Cannot reach the server. Is the backend running?';
      default:
        return 'Something went wrong on our side. Please try again.';
    }
  }

  if (error instanceof Error) {
    return error.message;
  }
  return 'An unexpected error occurred.';
}

/** The correlation id from a failed request, for logging alongside a report. */
export function apiTraceId(error: unknown): string | null {
  if (error instanceof AxiosError) {
    return (error.response?.data as ApiErrorBody | undefined)?.traceId ?? null;
  }
  return null;
}
