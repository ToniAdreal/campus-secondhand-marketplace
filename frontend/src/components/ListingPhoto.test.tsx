import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import ListingPhoto from './ListingPhoto';

const IMG_CLASS = 'mb-3 h-40 w-full rounded object-cover';
const PLACEHOLDER_CLASS =
  'mb-3 flex h-40 items-center justify-center rounded bg-neutral-100 text-sm text-neutral-500';

describe('ListingPhoto', () => {
  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
  });

  it('renders the image untouched while it loads fine', () => {
    const { container } = render(
      <ListingPhoto
        src="/uploads/a.png"
        alt="Used bike"
        imgClassName={IMG_CLASS}
        placeholderClassName={PLACEHOLDER_CLASS}
      />,
    );

    const img = screen.getByRole('img', { name: 'Used bike' }) as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/uploads/a.png');
    expect(img.getAttribute('class')).toBe(IMG_CLASS);
    expect(screen.queryByTestId('no-photo-placeholder')).toBeNull();
    expect(container.querySelector('img')).not.toBeNull();
  });

  it('swaps a failed image for the placeholder block, sized like the image slot', () => {
    render(
      <ListingPhoto
        src="/uploads/dead.png"
        alt="Used bike"
        imgClassName={IMG_CLASS}
        placeholderClassName={PLACEHOLDER_CLASS}
      />,
    );

    const img = screen.getByRole('img', { name: 'Used bike' });
    fireEvent.error(img);

    expect(screen.queryByRole('img')).toBeNull();
    const placeholder = screen.getByTestId('no-photo-placeholder');
    expect(placeholder.textContent).toBe('No photo');
    expect(placeholder.getAttribute('class')).toBe(PLACEHOLDER_CLASS);
  });

  it('recovers when src changes after a failure', () => {
    const { rerender } = render(
      <ListingPhoto
        src="/uploads/dead.png"
        alt="Used bike"
        imgClassName={IMG_CLASS}
        placeholderClassName={PLACEHOLDER_CLASS}
      />,
    );

    fireEvent.error(screen.getByRole('img', { name: 'Used bike' }));
    expect(screen.getByTestId('no-photo-placeholder')).toBeTruthy();

    rerender(
      <ListingPhoto
        src="/uploads/b.png"
        alt="Used bike"
        imgClassName={IMG_CLASS}
        placeholderClassName={PLACEHOLDER_CLASS}
      />,
    );

    expect(screen.queryByTestId('no-photo-placeholder')).toBeNull();
    const img = screen.getByRole('img', { name: 'Used bike' }) as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/uploads/b.png');
  });
});
