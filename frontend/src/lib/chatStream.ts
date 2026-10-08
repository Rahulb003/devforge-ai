import { useEffect, useState } from 'react';

import { useAuthStore } from '@/stores/authStore';

export type StreamStatus = 'connecting' | 'live' | 'reconnecting';

/**
 * Subscribes to a project's chat stream and calls `onEvent` for each message pushed.
 *
 * Uses `fetch` rather than `EventSource`, because `EventSource` cannot send an Authorization
 * header, and the only alternative — the token in the URL — leaks it into access logs.
 *
 * The server closes the stream when the access token would expire, so this reconnects with
 * backoff, picking up the current token each time. That is also what re-checks that the user still
 * belongs to the project.
 */
export function useChatStream(
  organizationId: string,
  projectId: string,
  onEvent: () => void,
): StreamStatus {
  const [status, setStatus] = useState<StreamStatus>('connecting');
  const token = useAuthStore((s) => s.accessToken);

  useEffect(() => {
    if (!organizationId || !projectId) return;
    const abort = new AbortController();
    let attempt = 0;

    async function connect() {
      while (!abort.signal.aborted) {
        try {
          const response = await fetch(
            `/api/v1/organizations/${organizationId}/projects/${projectId}/chat/messages/stream`,
            {
              headers: {
                Accept: 'text/event-stream',
                ...(token ? { Authorization: `Bearer ${token}` } : {}),
              },
              signal: abort.signal,
            },
          );
          if (!response.ok || !response.body) throw new Error(`stream ${response.status}`);

          setStatus('live');
          attempt = 0;
          const reader = response.body.getReader();
          const decoder = new TextDecoder();
          let buffer = '';
          for (;;) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            // Events are separated by a blank line; keep any partial event for the next chunk.
            const events = buffer.split('\n\n');
            buffer = events.pop() ?? '';
            if (events.some((e) => e.includes('data:'))) onEvent();
          }
        } catch {
          if (abort.signal.aborted) return;
        }
        setStatus('reconnecting');
        attempt += 1;
        await new Promise((r) => setTimeout(r, Math.min(30_000, 1_000 * 2 ** attempt)));
      }
    }

    void connect();
    return () => abort.abort();
    // onEvent is intentionally excluded: a new function each render would reconnect every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [organizationId, projectId, token]);

  return status;
}
