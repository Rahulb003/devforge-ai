import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { useChatStream } from '@/lib/chatStream';

vi.mock('@/lib/axios', () => ({ refreshSession: vi.fn() }));

/** A response whose body yields the given chunks, then stays open like a live stream. */
function streamOf(chunks: string[]) {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
    },
  });
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

describe('useChatStream', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('names each event, as Spring writes them', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        streamOf([
          ':connected\n\n',
          'event:presence\ndata:p1\n\n',
          'event:message\ndata:{"id":"m1"}\n\n',
        ]),
      ),
    );
    const onEvent = vi.fn();

    renderHook(() => useChatStream('o1', 'p1', onEvent));

    await waitFor(() => expect(onEvent).toHaveBeenCalledTimes(2));
    expect(onEvent.mock.calls.map((c) => c[0])).toEqual(['presence', 'message']);
  });
});
