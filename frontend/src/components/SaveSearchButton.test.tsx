import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import SaveSearchButton from './SaveSearchButton';
import type { SavedSearchInput } from '../api/savedSearches';

// Controllable create mutation, in the mocking style of the page tests:
// the button derives its saved/error state from this hook's result, so
// the tests pin the wiring without needing a QueryClient.
let mockIsLoading = false;
let mockIsError = false;
const mutateSpy = vi.fn();

vi.mock('../api/savedSearches', () => ({
  useCreateSavedSearch: () => ({
    mutate: mutateSpy,
    isLoading: mockIsLoading,
    isError: mockIsError,
  }),
}));

const filters: SavedSearchInput = {
  keyword: 'bike',
  categoryId: 3,
  minPriceCents: 1000,
  maxPriceCents: 5000,
  sort: 'price-asc',
};

function renderButton(input: SavedSearchInput = filters) {
  return render(<SaveSearchButton filters={input} />);
}

describe('SaveSearchButton (backlog #283)', () => {
  beforeEach(() => {
    mockIsLoading = false;
    mockIsError = false;
    // Default behaviour: the mutation succeeds, so the component's
    // onSuccess flips it into the saved state.
    mutateSpy.mockImplementation((_input, options) => {
      options?.onSuccess?.({}, _input, undefined);
    });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('shows no saved confirmation before the click (unsaved state)', () => {
    renderButton();

    expect(screen.getByRole('button', { name: 'Save this search' })).toBeTruthy();
    expect(screen.queryByText(/find it under Saved searches/)).toBeNull();
    expect(mutateSpy).not.toHaveBeenCalled();
  });

  it('calls the saved-search mutation with exactly the current filters', () => {
    renderButton();

    fireEvent.click(screen.getByRole('button', { name: 'Save this search' }));

    expect(mutateSpy).toHaveBeenCalledTimes(1);
    expect(mutateSpy.mock.calls[0][0]).toEqual(filters);
  });

  it('passes a sparse filter set through verbatim', () => {
    renderButton({ keyword: 'chair' });

    fireEvent.click(screen.getByRole('button', { name: 'Save this search' }));

    expect(mutateSpy.mock.calls[0][0]).toEqual({ keyword: 'chair' });
  });

  it('reflects the saved state with the in-place confirmation on success', () => {
    renderButton();

    fireEvent.click(screen.getByRole('button', { name: 'Save this search' }));

    expect(
      screen.getByText('Saved — find it under Saved searches on your favorites page.'),
    ).toBeTruthy();
  });

  it('shows the limit alert and no saved confirmation on error', () => {
    mockIsError = true;
    mutateSpy.mockImplementation(() => undefined);
    renderButton();

    fireEvent.click(screen.getByRole('button', { name: 'Save this search' }));

    expect(screen.getByRole('alert')).toBeTruthy();
    expect(screen.getByRole('alert').textContent).toMatch(/limit of 20 saved searches/);
    expect(screen.queryByText(/find it under Saved searches/)).toBeNull();
  });

  it('disables the button while the mutation is in flight', () => {
    mockIsLoading = true;
    renderButton();

    expect(
      (screen.getByRole('button', { name: 'Save this search' }) as HTMLButtonElement).disabled,
    ).toBe(true);
  });
});
