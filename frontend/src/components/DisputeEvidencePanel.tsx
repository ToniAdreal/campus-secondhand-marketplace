import { useRef, useState } from 'react';
import {
  describeDisputeEvidenceError,
  useAddDisputeEvidence,
  useDisputeEvidence,
  useOrderDispute,
  useRemoveDisputeEvidence,
} from '../api/disputes';
import { useAuthStore } from '../store/useAuthStore';

/**
 * The evidence panel keyed by order (backlog #200): buyer and seller
 * order lists know the order, not the dispute, so this wrapper first
 * resolves the order's dispute via GET /api/orders/{id}/dispute —
 * which 404s for anyone who is not a party — and renders the panel
 * only when a dispute actually came back.
 */
export function OrderDisputeEvidence({ orderId }: { orderId: number }) {
  const dispute = useOrderDispute(orderId);
  if (!dispute.data) {
    return null;
  }
  return (
    <DisputeEvidencePanel disputeId={dispute.data.id} disputeStatus={dispute.data.status} />
  );
}

/**
 * Evidence photos for one dispute (backlog #200): thumbnails of what
 * the buyer, seller or an ADMIN attached, an attach control while the
 * dispute is OPEN, and a remove button on the caller's own photos
 * (an ADMIN may remove anyone's — the backend is the real gate; this
 * component only mirrors its rule). The list itself is
 * dispute-scoped server-side: a caller who is not a party gets a 404
 * and this panel renders its error copy, never a stranger's photos.
 */
export default function DisputeEvidencePanel({
  disputeId,
  disputeStatus,
}: {
  disputeId: number;
  disputeStatus: 'OPEN' | 'RESOLVED_REFUND' | 'RESOLVED_RELEASE';
}) {
  const user = useAuthStore((s) => s.user);
  const isAdmin = user !== null && user.roles.includes('ADMIN');
  const evidence = useDisputeEvidence(disputeId, user !== null);
  const addEvidence = useAddDisputeEvidence();
  const removeEvidence = useRemoveDisputeEvidence();
  const fileInput = useRef<HTMLInputElement>(null);
  const [error, setError] = useState<string | null>(null);

  const attach = (file: File | undefined) => {
    if (!file) {
      return;
    }
    setError(null);
    addEvidence.mutate(
      { disputeId, file },
      {
        onError: (err) => setError(describeDisputeEvidenceError(err)),
        onSettled: () => {
          if (fileInput.current) {
            fileInput.current.value = '';
          }
        },
      },
    );
  };

  const remove = (evidenceId: number) => {
    setError(null);
    removeEvidence.mutate(
      { disputeId, evidenceId },
      { onError: (err) => setError(describeDisputeEvidenceError(err)) },
    );
  };

  const photos = evidence.data ?? [];

  return (
    <div className="mt-2">
      <p className="text-sm font-medium">Evidence photos</p>
      {evidence.isError ? (
        <p role="alert" className="mt-1 text-sm text-red-600">
          {describeDisputeEvidenceError(evidence.error)}
        </p>
      ) : photos.length === 0 ? (
        <p className="mt-1 text-sm text-neutral-600">No evidence photos yet.</p>
      ) : (
        <ul className="mt-1 flex flex-wrap gap-2">
          {photos.map((photo) => (
            <li key={photo.id} className="text-center">
              <img
                src={photo.url}
                alt={`Evidence photo ${photo.id} uploaded by user ${photo.uploaderId}`}
                className="h-20 w-20 rounded object-cover"
              />
              {(isAdmin || photo.uploaderId === user?.id) && (
                <button
                  type="button"
                  onClick={() => remove(photo.id)}
                  disabled={removeEvidence.isLoading}
                  className="mt-1 text-xs text-red-600 hover:underline disabled:opacity-50"
                >
                  Remove
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {error && (
        <p role="alert" className="mt-1 text-sm text-red-600">
          {error}
        </p>
      )}
      {disputeStatus === 'OPEN' && (
        <div className="mt-2">
          <label
            htmlFor={`dispute-evidence-file-${disputeId}`}
            className="text-sm text-blue-600 hover:underline"
          >
            Attach evidence photo
          </label>
          <input
            ref={fileInput}
            id={`dispute-evidence-file-${disputeId}`}
            type="file"
            accept="image/png,image/jpeg,image/webp,image/gif"
            className="sr-only"
            onChange={(e) => attach(e.target.files?.[0])}
          />
          {addEvidence.isLoading && (
            <span className="ml-2 text-sm text-neutral-600">Uploading…</span>
          )}
        </div>
      )}
    </div>
  );
}
