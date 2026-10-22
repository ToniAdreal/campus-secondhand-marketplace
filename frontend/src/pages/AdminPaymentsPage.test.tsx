import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { api, setAccessToken } from '../api/client';
import type { AuthUser } from '../api/auth';
import type { SettlementSummary } from '../api/admin';
import { useAuthStore } from '../store/useAuthStore';
import AdminPaymentsPage from './AdminPaymentsPage';

const getSpy = vi.spyOn(api, 'get');

const adminCaller: AuthUser = {
  id: 1,
  username: 'adminboss',
  email: 'admin@example.com',
  roles: ['ADMIN', 'USER'],
};

// Mirrors the backend AdminSettlementTest seed mix: PAID 10000 +
// COMPLETED 20000 + REFUNDED 5000 captured, PENDING/CANCELLED contribute
// nothing to the money totals.
const summaryFixture: SettlementSummary = {
  capturedCents: 35000,
  refundedCents: 5000,
  netCents: 30000,
  totalOrders: 5,
  pendingCount: 1,
  paidCount: 1,
  completedCount: 1,
  cancelledCount: 1,
  refundedCount: 1,
};

function summaryEnvelope(data: SettlementSummary) {
  return { data: { code: 0, message: 'ok', data } } as never;
}

function axiosFailure(status: number, message: string) {
  return {
    isAxiosError: true,
    response: { status, data: { code: status, message, data: null } },
  };
}

function renderAt(initialEntry: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/admin/payments" element={<AdminPaymentsPage />} />
          <Route path="/" element={<div>home page stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AdminPaymentsPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null });
    setAccessToken(null);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders captured / refunded / net totals formatted from cents, plus per-status counts', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(summaryEnvelope(summaryFixture));

    renderAt('/admin/payments');

    await waitFor(() => expect(screen.getByText('¥350.00')).toBeTruthy());
    expect(screen.getByText('¥50.00')).toBeTruthy();
    expect(screen.getByText('¥300.00')).toBeTruthy();
    expect(getSpy).toHaveBeenCalledWith('/admin/payments/settlement');
    // Per-status counts: total + one row per status from the fixture.
    expect(screen.getByText('Total orders')).toBeTruthy();
    expect(screen.getByText('Pending')).toBeTruthy();
    expect(screen.getByText('Paid')).toBeTruthy();
    expect(screen.getByText('Completed')).toBeTruthy();
    expect(screen.getByText('Cancelled')).toBeTruthy();
    // 'Refunded' labels both a totals card and a status row.
    expect(screen.getAllByText('Refunded')).toHaveLength(2);
    // Honest labelling: the figures are mock-PSP derived totals.
    expect(screen.getByText(/Mock-PSP settlement summary/)).toBeTruthy();
    expect(screen.getByText(/no real money has moved/i)).toBeTruthy();
  });

  it('renders an all-zero summary for an empty database instead of an error', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(
      summaryEnvelope({
        capturedCents: 0,
        refundedCents: 0,
        netCents: 0,
        totalOrders: 0,
        pendingCount: 0,
        paidCount: 0,
        completedCount: 0,
        cancelledCount: 0,
        refundedCount: 0,
      }),
    );

    renderAt('/admin/payments');

    await waitFor(() => expect(screen.getAllByText('¥0.00')).toHaveLength(3));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('date inputs fire a windowed request: from at day start, to as the exclusive next-day bound', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(summaryEnvelope(summaryFixture));

    renderAt('/admin/payments');
    await waitFor(() => expect(screen.getByText('¥350.00')).toBeTruthy());
    expect(getSpy).toHaveBeenCalledWith('/admin/payments/settlement');

    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-01-10' } });
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-01-20' } });

    // The To date is inclusive in the UI, so it is sent as 2026-01-21 —
    // the backend window is [from, to) over order createdAt.
    await waitFor(() =>
      expect(getSpy).toHaveBeenCalledWith('/admin/payments/settlement', {
        params: { from: '2026-01-10T00:00:00Z', to: '2026-01-21T00:00:00Z' },
      }),
    );
  });

  it('clearing the dates returns to the plain all-time request', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockResolvedValue(summaryEnvelope(summaryFixture));

    renderAt('/admin/payments');
    await waitFor(() => expect(screen.getByText('¥350.00')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-01-10' } });
    await waitFor(() =>
      expect(getSpy).toHaveBeenCalledWith('/admin/payments/settlement', {
        params: { from: '2026-01-10T00:00:00Z' },
      }),
    );

    fireEvent.click(screen.getByText('Clear dates (all time)'));
    await waitFor(() =>
      expect(getSpy).toHaveBeenLastCalledWith('/admin/payments/settlement'),
    );
  });

  it('non-admin sessions bounce home and fire no request', async () => {
    useAuthStore.getState().login(
      { id: 9, username: 'plainuser', email: 'plain@example.com', roles: ['USER'] },
      'tok',
    );

    renderAt('/admin/payments');
    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('anonymous sessions bounce home and fire no request', async () => {
    renderAt('/admin/payments');
    await waitFor(() => expect(screen.getByText('home page stub')).toBeTruthy());
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('a 403 from the API renders the honest not-authorized state, never fake totals', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockRejectedValue(axiosFailure(403, 'Access Denied'));

    renderAt('/admin/payments');
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toContain('not authorized');
    expect(screen.queryByText('¥350.00')).toBeNull();
  });

  it('an API error renders the envelope message', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    getSpy.mockRejectedValue(axiosFailure(500, 'settlement unavailable'));

    renderAt('/admin/payments');
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toContain('settlement unavailable');
  });

  // Webhook-event viewer (backlog #156): the section lists ledger rows
  // from GET /admin/payments/webhooks under the settlement summary.
  const webhookRowsFixture = [
    {
      id: 2,
      eventId: 'evt_new',
      eventType: 'refund.succeeded',
      pspReference: 'rfd_new',
      outcome: 'MISMATCH',
      createdAt: '2026-01-12T00:00:00Z',
    },
    {
      id: 1,
      eventId: 'evt_old',
      eventType: 'capture.succeeded',
      pspReference: 'cap_old',
      outcome: 'MATCHED',
      createdAt: '2026-01-10T00:00:00Z',
    },
  ];

  function mockSettlementAndWebhooks(webhookData: unknown) {
    getSpy.mockImplementation(((url: string) =>
      url === '/admin/payments/webhooks'
        ? Promise.resolve({ data: { code: 0, message: 'ok', data: webhookData } })
        : Promise.resolve(summaryEnvelope(summaryFixture))) as never);
  }

  it('renders webhook events with outcome chips and PSP references', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    mockSettlementAndWebhooks({
      content: webhookRowsFixture,
      totalElements: 2,
      totalPages: 1,
      number: 0,
    });

    renderAt('/admin/payments');

    await waitFor(() => expect(screen.getByText('evt_new')).toBeTruthy());
    expect(screen.getByText('evt_old')).toBeTruthy();
    expect(screen.getByText('refund.succeeded')).toBeTruthy();
    expect(screen.getByText('rfd_new')).toBeTruthy();
    // Each outcome appears twice: the filter <option> and the row chip.
    expect(screen.getAllByText('MISMATCH')).toHaveLength(2);
    expect(screen.getAllByText('MATCHED')).toHaveLength(2);
    expect(getSpy).toHaveBeenCalledWith('/admin/payments/webhooks', {
      params: { page: 0, size: 20 },
    });
  });

  it('outcome filter fires a filtered webhook request', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    mockSettlementAndWebhooks({
      content: webhookRowsFixture,
      totalElements: 2,
      totalPages: 1,
      number: 0,
    });

    renderAt('/admin/payments');
    await waitFor(() => expect(screen.getByText('evt_new')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('Filter by outcome'), {
      target: { value: 'MISMATCH' },
    });

    await waitFor(() =>
      expect(getSpy).toHaveBeenCalledWith('/admin/payments/webhooks', {
        params: { page: 0, size: 20, outcome: 'MISMATCH' },
      }),
    );
  });

  // Payment-attempt viewer (backlog #211): the section lists ledger
  // rows from GET /admin/payments/attempts under the webhook viewer,
  // defaulting to DECLINED attempts.
  const attemptRowsFixture = [
    {
      id: 7,
      orderId: 42,
      outcome: 'DECLINED',
      idempotencyKey: 'cap_attempt42',
      createdAt: '2026-01-12T00:00:00Z',
    },
    {
      id: 5,
      orderId: 41,
      outcome: 'DECLINED',
      idempotencyKey: 'cap_attempt41',
      createdAt: '2026-01-11T00:00:00Z',
    },
  ];

  function mockAllPayments(attemptData: unknown) {
    getSpy.mockImplementation(((url: string) =>
      url === '/admin/payments/webhooks'
        ? Promise.resolve({
            data: { code: 0, message: 'ok', data: { content: [], totalElements: 0, totalPages: 0, number: 0 } },
          })
        : url === '/admin/payments/attempts'
          ? Promise.resolve({ data: { code: 0, message: 'ok', data: attemptData } })
          : Promise.resolve(summaryEnvelope(summaryFixture))) as never);
  }

  it('renders declined payment attempts by default with order and key', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    mockAllPayments({
      content: attemptRowsFixture,
      totalElements: 2,
      totalPages: 1,
      number: 0,
    });

    renderAt('/admin/payments');

    await waitFor(() => expect(screen.getByText('#42')).toBeTruthy());
    expect(screen.getByText('#41')).toBeTruthy();
    expect(screen.getByText('cap_attempt42')).toBeTruthy();
    // Each DECLINED appears twice: the filter <option> and the row chip.
    expect(screen.getAllByText('DECLINED')).toHaveLength(3);
    // The default view asks the backend for DECLINED attempts only.
    expect(getSpy).toHaveBeenCalledWith('/admin/payments/attempts', {
      params: { page: 0, size: 20, outcome: 'DECLINED' },
    });
  });

  it('attempt outcome filter fires a filtered attempts request', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    mockAllPayments({
      content: attemptRowsFixture,
      totalElements: 2,
      totalPages: 1,
      number: 0,
    });

    renderAt('/admin/payments');
    await waitFor(() => expect(screen.getByText('#42')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('Filter by attempt outcome'), {
      target: { value: 'SUCCEEDED' },
    });

    await waitFor(() =>
      expect(getSpy).toHaveBeenCalledWith('/admin/payments/attempts', {
        params: { page: 0, size: 20, outcome: 'SUCCEEDED' },
      }),
    );
  });

  it('renders the attempts empty state when no declined attempts exist', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    mockAllPayments({ content: [], totalElements: 0, totalPages: 0, number: 0 });

    renderAt('/admin/payments');

    await waitFor(() =>
      expect(screen.getByText('No payment attempts match this filter.')).toBeTruthy(),
    );
  });

  it('renders the webhook empty state when the ledger is empty', async () => {
    useAuthStore.getState().login(adminCaller, 'tok');
    mockSettlementAndWebhooks({ content: [], totalElements: 0, totalPages: 0, number: 0 });

    renderAt('/admin/payments');

    await waitFor(() => expect(screen.getByText('No webhook events yet.')).toBeTruthy());
  });
});
