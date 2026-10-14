import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from '../api/client';
import type { ItemPhoto } from '../api/items';
import GalleryOrderEditor from './GalleryOrderEditor';

const putSpy = vi.spyOn(api, 'put');

const photos: ItemPhoto[] = [
  { id: 11, url: '/uploads/a.png', position: 0 },
  { id: 22, url: '/uploads/b.png', position: 1 },
  { id: 33, url: '/uploads/c.png', position: 2 },
];

function envelope(data: unknown) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function axiosFailure(status: number, message: string) {
  return {
    isAxiosError: true,
    response: { status, data: { code: status, message, data: null } },
  };
}

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

function renderEditor() {
  return render(<GalleryOrderEditor itemId={7} photos={photos} />, { wrapper });
}

/** The alt-free thumbnails are identified by their row's "Photo N" label. */
function rowOrder(container: HTMLElement): string[] {
  return [...container.querySelectorAll('li')].map(
    (li) => li.querySelector('img')?.getAttribute('src') ?? '',
  );
}

describe('GalleryOrderEditor (backlog #162)', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders the gallery in position order with the first row flagged Primary', () => {
    const { container } = renderEditor();

    expect(rowOrder(container)).toEqual(['/uploads/a.png', '/uploads/b.png', '/uploads/c.png']);
    expect(screen.getByText('Primary')).toBeTruthy();
    // Save is disabled until the order actually changes.
    expect(
      (screen.getByRole('button', { name: 'Save photo order' }) as HTMLButtonElement).disabled,
    ).toBe(true);
  });

  it('disables Move up on the first row and Move down on the last row', () => {
    renderEditor();

    expect(
      (screen.getByRole('button', { name: 'Move photo 1 up' }) as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(
      (screen.getByRole('button', { name: 'Move photo 1 down' }) as HTMLButtonElement).disabled,
    ).toBe(false);
    expect(
      (screen.getByRole('button', { name: 'Move photo 3 down' }) as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(
      (screen.getByRole('button', { name: 'Move photo 3 up' }) as HTMLButtonElement).disabled,
    ).toBe(false);
  });

  it('moving a photo down swaps the rows and the Primary flag follows the new first photo', () => {
    const { container } = renderEditor();

    fireEvent.click(screen.getByRole('button', { name: 'Move photo 1 down' }));

    expect(rowOrder(container)).toEqual(['/uploads/b.png', '/uploads/a.png', '/uploads/c.png']);
    const firstRow = container.querySelectorAll('li')[0];
    expect(firstRow.textContent).toContain('Primary');
    expect(
      (screen.getByRole('button', { name: 'Save photo order' }) as HTMLButtonElement).disabled,
    ).toBe(false);
  });

  it('save PUTs the complete ordered photo-id list to the reorder endpoint', async () => {
    putSpy.mockResolvedValue(envelope({ id: 7, photos }));
    renderEditor();

    // Move photo 3 up twice: [a,b,c] -> [a,c,b] -> [c,a,b].
    fireEvent.click(screen.getByRole('button', { name: 'Move photo 3 up' }));
    fireEvent.click(screen.getByRole('button', { name: 'Move photo 2 up' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save photo order' }));

    await waitFor(() => expect(putSpy).toHaveBeenCalledTimes(1));
    expect(putSpy).toHaveBeenCalledWith('/items/7/photos/order', {
      photoIds: [33, 11, 22],
    });
    await waitFor(() => expect(screen.getByText('Photo order saved.')).toBeTruthy());
  });

  it('moving a photo and moving it back leaves Save disabled (no no-op PUT)', () => {
    putSpy.mockResolvedValue(envelope({ id: 7, photos }));
    renderEditor();

    fireEvent.click(screen.getByRole('button', { name: 'Move photo 2 up' }));
    fireEvent.click(screen.getByRole('button', { name: 'Move photo 1 down' }));

    expect(
      (screen.getByRole('button', { name: 'Save photo order' }) as HTMLButtonElement).disabled,
    ).toBe(true);
    expect(putSpy).not.toHaveBeenCalled();
  });

  it('a 400 (the gallery changed under the editor) maps to friendly copy, never the raw envelope', async () => {
    putSpy.mockRejectedValue(
      axiosFailure(400, 'photoIds must list every photo in the gallery exactly once'),
    );
    renderEditor();

    fireEvent.click(screen.getByRole('button', { name: 'Move photo 1 down' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save photo order' }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toBe(
      'The gallery changed since this page loaded — reload and try again.',
    );
    expect(screen.queryByText(/exactly once/)).toBeNull();
  });

  it('renders nothing for a single-photo gallery (there is no order to change)', () => {
    const { container } = render(
      <GalleryOrderEditor itemId={7} photos={[photos[0]]} />,
      { wrapper },
    );
    expect(container.querySelector('section')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Save photo order' })).toBeNull();
  });
});
