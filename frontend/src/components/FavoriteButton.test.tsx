import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import FavoriteButton from './FavoriteButton';
import { useAuthStore } from '../store/useAuthStore';
import type { AuthUser } from '../api/auth';

// Controllable favorites list + toggle spy, in the mocking style of the
// page tests: the button derives its state from the shared list query.
let mockFavorites: Array<{ id: number }> = [];
const toggleMutate = vi.fn();

vi.mock('../api/favorites', () => ({
  useFavorites: () => ({ data: mockFavorites, isLoading: false }),
  useToggleFavorite: () => ({ mutate: toggleMutate, isLoading: false }),
}));

const user: AuthUser = { id: 5, username: 'toni', email: 'toni@example.com', roles: ['USER'] };

describe('FavoriteButton accessible toggle (backlog #167)', () => {
  beforeEach(() => {
    useAuthStore.setState({ user });
    mockFavorites = [];
  });

  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
    vi.clearAllMocks();
    useAuthStore.setState({ user: null });
  });

  it('exposes an accessible name and unpressed state for an unsaved listing', () => {
    render(<FavoriteButton itemId={42} />);

    const button = screen.getByRole('button', { name: 'Save to favorites' });
    expect(button.getAttribute('aria-pressed')).toBe('false');

    fireEvent.click(button);
    expect(toggleMutate).toHaveBeenCalledWith({ itemId: 42, saved: false });
  });

  it('exposes the remove name and pressed state for a saved listing', () => {
    mockFavorites = [{ id: 42 }];
    render(<FavoriteButton itemId={42} />);

    const button = screen.getByRole('button', { name: 'Remove from favorites' });
    expect(button.getAttribute('aria-pressed')).toBe('true');
  });

  it('renders nothing for anonymous visitors', () => {
    useAuthStore.setState({ user: null });
    render(<FavoriteButton itemId={42} />);

    expect(screen.queryByRole('button')).toBeNull();
  });
});
