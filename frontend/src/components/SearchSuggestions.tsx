import type { ItemSuggestion } from '../api/items';

/**
 * Search-suggestion dropdown (backlog #235): the plain listbox rendered
 * under the browse search box. It displays titles only — selecting one
 * navigates to the existing `?q=` results, so the component never needs
 * the listing id for navigation (the id is only the React key). Dismissal
 * is keyboard-driven: Escape on the search input (or on the listbox
 * itself) closes the dropdown, and selecting a suggestion closes it too;
 * the parent owns that open state, this component only renders. An empty
 * list renders nothing, so the box never shows an empty dropdown frame.
 */
export default function SearchSuggestions({
  suggestions,
  onSelect,
  onDismiss,
}: {
  suggestions: ItemSuggestion[];
  onSelect: (title: string) => void;
  onDismiss: () => void;
}) {
  if (suggestions.length === 0) {
    return null;
  }
  return (
    <ul
      role="listbox"
      aria-label="Search suggestions"
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          onDismiss();
        }
      }}
      className="absolute left-0 right-0 top-full z-10 mt-1 max-h-64 overflow-auto rounded-lg border
                 border-neutral-300 bg-white py-1 shadow-lg"
    >
      {suggestions.map((suggestion) => (
        <li key={suggestion.id}>
          <button
            type="button"
            role="option"
            aria-selected={false}
            onClick={() => onSelect(suggestion.title)}
            className="block w-full truncate px-4 py-2 text-left hover:bg-neutral-100
                       focus:bg-neutral-100 focus:outline-none"
          >
            {suggestion.title}
          </button>
        </li>
      ))}
    </ul>
  );
}
