import { useFavorites, useToggleFavorite } from '../api/favorites';
import { useAuthStore } from '../store/useAuthStore';

/**
 * Heart toggle for one listing (backlog #143). Signed-in only: for
 * anonymous visitors it renders nothing at all (the backend would 401
 * the writes anyway). Saved state derives from the shared favorites
 * list query, so every heart on the page — browse cards, the detail
 * page, the favorites page — agrees, and a toggle invalidates that
 * one query instead of editing local state per card.
 */
export default function FavoriteButton({ itemId }: { itemId: number }) {
  const user = useAuthStore((s) => s.user);
  const favorites = useFavorites(user !== null);
  const toggle = useToggleFavorite();

  if (!user) {
    return null;
  }

  const saved = (favorites.data ?? []).some((item) => item.id === itemId);

  return (
    <button
      type="button"
      aria-label={saved ? 'Remove from favorites' : 'Save to favorites'}
      aria-pressed={saved}
      disabled={toggle.isLoading}
      onClick={() => toggle.mutate({ itemId, saved })}
      className="mt-2 inline-block rounded bg-neutral-100 px-2 py-0.5 text-sm hover:bg-neutral-200 disabled:opacity-50"
    >
      {saved ? '♥ Saved' : '♡ Save'}
    </button>
  );
}
