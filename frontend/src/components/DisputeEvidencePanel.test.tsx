import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import DisputeEvidencePanel, { OrderDisputeEvidence } from './DisputeEvidencePanel';
import { useAuthStore } from '../store/useAuthStore';
import type { AuthUser } from '../api/auth';
import type { DisputeEvidence, OrderDispute } from '../api/disputes';

// Controllable evidence list + mutation spies, in the mocking style of
// FavoriteButton.test: the panel derives everything from the hooks.
let mockEvidence: DisputeEvidence[] = [];
let mockDispute: OrderDispute | undefined;
const addMutate = vi.fn((_input: unknown, opts?: { onError?: (e: unknown) => void }) => {
  void opts;
});
const removeMutate = vi.fn();

vi.mock('../api/disputes', () => ({
  useDisputeEvidence: () => ({ data: mockEvidence, isError: false, error: null }),
  useOrderDispute: () => ({ data: mockDispute }),
  useAddDisputeEvidence: () => ({ mutate: addMutate, isLoading: false }),
  useRemoveDisputeEvidence: () => ({ mutate: removeMutate, isLoading: false }),
  describeDisputeEvidenceError: () => 'Something went wrong. Please try again.',
}));

const buyer: AuthUser = {
  id: 5,
  username: 'buyer',
  email: 'buyer@example.com',
  roles: ['USER'],
};

const photo = (id: number, uploaderId: number): DisputeEvidence => ({
  id,
  disputeId: 3,
  uploaderId,
  url: `/uploads/photo-${id}.png`,
  createdAt: '2026-10-20T08:00:00Z',
});

describe('DisputeEvidencePanel (backlog #200)', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: buyer });
    mockEvidence = [];
    mockDispute = undefined;
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    useAuthStore.setState({ user: null });
  });

  it('renders thumbnails and a remove button only on the caller\u2019s own photos', () => {
    mockEvidence = [photo(11, 5), photo(12, 3)];
    render(<DisputeEvidencePanel disputeId={3} disputeStatus="OPEN" />);

    const images = screen.getAllByRole('img');
    expect(images).toHaveLength(2);
    expect(images[0].getAttribute('src')).toBe('/uploads/photo-11.png');
    // Only the buyer's own upload (uploader 5) is removable for a non-admin.
    expect(screen.getAllByRole('button', { name: 'Remove' })).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'Remove' }));
    expect(removeMutate).toHaveBeenCalledWith(
      { disputeId: 3, evidenceId: 11 },
      expect.anything(),
    );
  });

  it('attaching a file fires the add mutation with the dispute id and file', () => {
    render(<DisputeEvidencePanel disputeId={3} disputeStatus="OPEN" />);

    expect(screen.getByText('No evidence photos yet.')).toBeTruthy();
    const input = screen.getByLabelText('Attach evidence photo') as HTMLInputElement;
    const file = new File(['png-bytes'], 'damage.png', { type: 'image/png' });
    fireEvent.change(input, { target: { files: [file] } });

    expect(addMutate).toHaveBeenCalledWith(
      { disputeId: 3, file },
      expect.anything(),
    );
  });

  it('hides the attach control once the dispute is resolved', () => {
    mockEvidence = [photo(11, 5)];
    render(<DisputeEvidencePanel disputeId={3} disputeStatus="RESOLVED_REFUND" />);

    expect(screen.queryByLabelText('Attach evidence photo')).toBeNull();
    expect(screen.getAllByRole('img')).toHaveLength(1);
  });

  it('the order wrapper shows the resolution note on a resolved dispute, never on an open one', () => {
    mockDispute = {
      id: 3,
      orderId: 9,
      buyerId: 5,
      sellerId: 3,
      reason: 'the parcel never arrived',
      status: 'RESOLVED_REFUND',
      previousStatus: 'PAID',
      listingRestored: true,
      createdAt: '2026-10-14T08:00:00Z',
      resolvedAt: '2026-10-15T08:00:00Z',
      resolutionNote: 'Carrier confirms non-delivery.',
    };
    render(<OrderDisputeEvidence orderId={9} />);
    expect(screen.getByText('Carrier confirms non-delivery.')).toBeTruthy();

    cleanup();
    mockDispute = { ...mockDispute, status: 'OPEN', resolvedAt: null, resolutionNote: null };
    render(<OrderDisputeEvidence orderId={9} />);
    expect(screen.queryByText(/resolution note/i)).toBeNull();
  });
});
