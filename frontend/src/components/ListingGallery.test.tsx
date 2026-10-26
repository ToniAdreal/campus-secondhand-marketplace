import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import ListingGallery from './ListingGallery';

describe('ListingGallery', () => {
  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
  });

  it('shows the primary photo first and switches the main image on thumbnail click', () => {
    render(
      <ListingGallery
        title="Used bike"
        photoUrls={['/uploads/a.png', '/uploads/b.png', '/uploads/c.png']}
      />,
    );

    const main = screen.getByRole('img', { name: 'Used bike' }) as HTMLImageElement;
    expect(main.getAttribute('src')).toBe('/uploads/a.png');

    fireEvent.click(screen.getByRole('button', { name: 'Show photo 3 of 3' }));
    expect(main.getAttribute('src')).toBe('/uploads/c.png');
    expect(
      screen.getByRole('button', { name: 'Show photo 3 of 3' }).getAttribute('aria-pressed'),
    ).toBe('true');

    fireEvent.click(screen.getByRole('button', { name: 'Show photo 1 of 3' }));
    expect(main.getAttribute('src')).toBe('/uploads/a.png');
  });

  it('renders no thumbnails for a single photo', () => {
    render(<ListingGallery title="Used bike" photoUrls={['/uploads/a.png']} />);

    expect(screen.getByRole('img', { name: 'Used bike' })).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('renders the placeholder and no img when the listing has no photo', () => {
    const { container } = render(<ListingGallery title="Used bike" photoUrls={[]} />);

    expect(screen.getByTestId('no-photo-placeholder').textContent).toBe('No photo');
    expect(container.querySelector('img')).toBeNull();
  });

  it('swaps a failed main image for the placeholder while thumbnails stay', () => {
    render(
      <ListingGallery
        title="Used bike"
        photoUrls={['/uploads/a.png', '/uploads/b.png']}
      />,
    );

    fireEvent.error(screen.getByRole('img', { name: 'Used bike' }));

    expect(screen.queryByRole('img', { name: 'Used bike' })).toBeNull();
    expect(screen.getByTestId('no-photo-placeholder').textContent).toBe('No photo');
    // The thumbnails are separate images and are untouched by the main failure.
    expect(screen.getByRole('button', { name: 'Show photo 1 of 2' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Show photo 2 of 2' })).toBeTruthy();
  });

  it('swaps a failed thumbnail for its placeholder and leaves the main image alone', () => {
    render(
      <ListingGallery
        title="Used bike"
        photoUrls={['/uploads/a.png', '/uploads/b.png']}
      />,
    );

    const thumbButton = screen.getByRole('button', { name: 'Show photo 2 of 2' });
    const thumbImg = thumbButton.querySelector('img');
    expect(thumbImg).not.toBeNull();
    fireEvent.error(thumbImg!);

    expect(screen.getByRole('img', { name: 'Used bike' })).toBeTruthy();
    expect(thumbButton.querySelector('img')).toBeNull();
    expect(thumbButton.querySelector('[data-testid="no-photo-placeholder"]')).not.toBeNull();
  });
});
