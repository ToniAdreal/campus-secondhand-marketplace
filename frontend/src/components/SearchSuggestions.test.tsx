import { describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import SearchSuggestions from './SearchSuggestions';

describe('SearchSuggestions dropdown (backlog #235)', () => {
  const suggestions = [
    { id: 1, title: 'Bike helmet' },
    { id: 2, title: 'Bike lock' },
  ];

  it('renders one option per suggestion title', () => {
    render(
      <SearchSuggestions suggestions={suggestions} onSelect={() => undefined} onDismiss={() => undefined} />,
    );
    expect(screen.getByRole('listbox', { name: /search suggestions/i })).toBeTruthy();
    expect(screen.getByRole('option', { name: 'Bike helmet' })).toBeTruthy();
    expect(screen.getByRole('option', { name: 'Bike lock' })).toBeTruthy();
    cleanup();
  });

  it('selecting a suggestion reports its title', () => {
    const onSelect = vi.fn();
    render(
      <SearchSuggestions suggestions={suggestions} onSelect={onSelect} onDismiss={() => undefined} />,
    );
    fireEvent.click(screen.getByRole('option', { name: 'Bike lock' }));
    expect(onSelect).toHaveBeenCalledWith('Bike lock');
    cleanup();
  });

  it('Escape on the listbox dismisses the dropdown', () => {
    const onDismiss = vi.fn();
    render(
      <SearchSuggestions suggestions={suggestions} onSelect={() => undefined} onDismiss={onDismiss} />,
    );
    fireEvent.keyDown(screen.getByRole('listbox'), { key: 'Escape' });
    expect(onDismiss).toHaveBeenCalledTimes(1);
    cleanup();
  });

  it('renders nothing for an empty list', () => {
    const { container } = render(
      <SearchSuggestions suggestions={[]} onSelect={() => undefined} onDismiss={() => undefined} />,
    );
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(container).toBeTruthy();
    cleanup();
  });
});
