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
});
