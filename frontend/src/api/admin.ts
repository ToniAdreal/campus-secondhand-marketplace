import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { api } from './client';
import type { ApiResponse, Page } from './items';

/**
 * One row of the ADMIN user list, mirroring the backend's AdminUserDto
 * (GET /api/admin/users, backlog #105). The DTO is a deliberate projection —
 * a password hash never reaches the wire, so none exists here either.
 * `roles` are role names sorted alphabetically (stable wire shape).
 */
export interface AdminUser {
  id: number;
  username: string;
  email: string;
  roles: string[];
  disabled: boolean;
  /** Whether the account has TOTP 2FA on (#209) — a bare flag, never the secret. */
  totpEnabled: boolean;
  createdAt: string;
}

/**
 * Query keys for the ADMIN users subtree. Everything hangs off
 * `['admin', 'users']` so a disable/enable success invalidates every
 * paginated / searched variant of the list with one prefix invalidation.
 */
export const adminUserKeys = {
  all: ['admin', 'users'] as const,
  list: (page: number, q: string) => [...adminUserKeys.all, 'list', page, q] as const,
};

/**
 * GET /api/admin/users — ADMIN-only (the backend's class-level
 * @PreAuthorize is the real gate; the SPA route/nav hiding is cosmetic).
 * Paginated, newest first; `q` is an optional case-insensitive substring
 * over username and email and is omitted entirely when blank, mirroring
 * the items list. Non-admin callers get the JSON 403 envelope and
 * anonymous callers 401 — both surface as Axios errors here.
 */
export function useAdminUsers(page = 0, q = '', enabled = true, size = 20) {
  return useQuery({
    queryKey: adminUserKeys.list(page, q),
    queryFn: () =>
      api
        .get<ApiResponse<Page<AdminUser>>>('/admin/users', {
          params: { page, size, ...(q.trim() !== '' ? { q: q.trim() } : {}) },
        })
        .then((res) => res.data.data),
    enabled,
  });
}

/**
 * POST /api/admin/users/{id}/disable — disables the account: login 401s,
 * live tokens are rejected, and every refresh family is revoked
 * server-side. Guarded server-side (self-disable and ADMIN-on-ADMIN are
 * 403, unknown id 404); the page additionally renders no disable button
 * for those rows, mirroring the guards. On success the users subtree is
 * invalidated so the list refetches the account's new state.
 */
export function useDisableAdminUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api
        .post<ApiResponse<AdminUser>>(`/admin/users/${id}/disable`, {})
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminUserKeys.all });
    },
  });
}

/**
 * POST /api/admin/users/{id}/enable — re-enables a disabled account so it
 * can log in again with fresh tokens (pre-disable tokens stay dead).
 * Enabling has no role guard server-side (it is restorative). On success
 * the users subtree is invalidated, mirroring the disable mutation.
 */
export function useEnableAdminUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api
        .post<ApiResponse<AdminUser>>(`/admin/users/${id}/enable`, {})
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminUserKeys.all });
    },
  });
}

/**
 * POST /api/admin/users/{id}/totp-reset — ADMIN-assisted TOTP reset for
 * a locked-out account (backlog #209): the backend clears the secret
 * and recovery codes, retires every trusted device, and revokes every
 * session, then audits the reset and notifies the holder. Guarded
 * server-side (an ADMIN target is 422, an account without TOTP is 422,
 * unknown id 404); the page additionally renders the action only on
 * non-ADMIN rows whose `totpEnabled` flag is set, mirroring the guards.
 * On success the users subtree is invalidated, mirroring disable/enable.
 */
export function useResetAdminUserTotp() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api
        .post<ApiResponse<AdminUser>>(`/admin/users/${id}/totp-reset`, {})
        .then((res) => res.data.data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminUserKeys.all });
    },
  });
}

/**
 * One row of the ADMIN audit-log read view, mirroring the backend's
 * AuditLogDto (GET /api/admin/audit-log, backlog #109). Enum fields are
 * their constant names — the same allowlisted values the read endpoint's
 * filters accept.
 */
export interface AuditLogEntry {
  id: number;
  actorUserId: number;
  action: string;
  targetType: string;
  targetId: number;
  createdAt: string;
}

/** Allowlisted AuditAction values (backend enum, source of truth). */
export const AUDIT_ACTIONS = [
  'ACCOUNT_CLOSED',
  'PASSWORD_CHANGED',
  'PASSWORD_RESET_COMPLETED',
  'TOTP_ENABLED',
  'TOTP_DISABLED',
  'RECOVERY_CODES_REGENERATED',
  'ORDER_PAID',
  'ORDER_CANCELLED',
  'ORDER_COMPLETED',
  'ORDER_SHIPPED',
  'ORDER_RECEIPT_CONFIRMED',
  'ORDER_REFUNDED',
  'USER_DISABLED',
  'USER_ENABLED',
  'CATEGORY_CREATED',
  'CATEGORY_UPDATED',
  'CATEGORY_DELETED',
  'WEBHOOK_EVENT_REPLAYED',
] as const;

/** Allowlisted AuditTargetType values (backend enum, source of truth). */
export const AUDIT_TARGET_TYPES = ['USER', 'ORDER', 'CATEGORY', 'WEBHOOK_EVENT'] as const;

export interface AuditLogFilters {
  actorUserId?: number | null;
  action?: string;
  targetType?: string;
  targetId?: number | null;
}

/**
 * Query keys for the ADMIN audit-log subtree, sibling to the users
 * subtree under ['admin', …] so invalidating one never refetches the other.
 */
export const adminAuditLogKeys = {
  all: ['admin', 'audit-log'] as const,
  list: (page: number, filters: AuditLogFilters) =>
    [
      ...adminAuditLogKeys.all,
      'list',
      page,
      filters.actorUserId ?? null,
      filters.action ?? '',
      filters.targetType ?? '',
      filters.targetId ?? null,
    ] as const,
};

/**
 * GET /api/admin/audit-log — ADMIN-only (backend @PreAuthorize is the
 * real gate). Paginated, newest first; every filter is optional and is
 * omitted entirely when unset, so the unfiltered request stays
 * byte-identical to a plain paginated list. Callers must only pass
 * allowlisted action/targetType values — anything else is a backend 400.
 */
export function useAdminAuditLog(
  page = 0,
  filters: AuditLogFilters = {},
  enabled = true,
  size = 20,
) {
  return useQuery({
    queryKey: adminAuditLogKeys.list(page, filters),
    queryFn: () =>
      api
        .get<ApiResponse<Page<AuditLogEntry>>>('/admin/audit-log', {
          params: {
            page,
            size,
            ...(filters.actorUserId != null ? { actorUserId: filters.actorUserId } : {}),
            ...(filters.action ? { action: filters.action } : {}),
            ...(filters.targetType ? { targetType: filters.targetType } : {}),
            ...(filters.targetId != null ? { targetId: filters.targetId } : {}),
          },
        })
        .then((res) => res.data.data),
    enabled,
  });
}

/** Maps a failed audit-log call to user-facing copy. */
export function describeAuditLogError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 403) {
    return 'You are not authorized to view the audit log.';
  }
  if (code === 401) {
    return 'Please log in again to view the audit log.';
  }
  if (code === 400) {
    return 'That filter is not valid.';
  }
  return message || 'Something went wrong. Please try again.';
}

/** True when the failure is the backend's 403 for a non-ADMIN caller. */
export function isForbidden(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 403;
}

/**
 * Maps a failed ADMIN users call to user-facing copy. The backend speaks
 * the {code,message,data} envelope; the known guard cases get plain
 * language and anything else passes the server message through.
 */
export function describeAdminUserError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 403) {
    if (/yourself/i.test(message)) {
      return 'You cannot disable your own account.';
    }
    if (/admin/i.test(message)) {
      return 'Admin accounts cannot be disabled here — they are managed out-of-band.';
    }
    return 'You are not authorized to do that.';
  }
  if (code === 401) {
    return 'Please log in again to manage users.';
  }
  if (code === 404) {
    return 'That user no longer exists.';
  }
  if (code === 422) {
    // Backlog #209: the TOTP-reset guards. The admin-target message
    // names an admin account; the not-enabled message names the factor.
    if (/admin/i.test(message)) {
      return 'Admin accounts cannot have two-factor authentication reset here — they are managed out-of-band.';
    }
    if (/two-factor|totp/i.test(message)) {
      return 'That account does not have two-factor authentication enabled.';
    }
    return message || 'That action is not available for this account.';
  }
  return message || 'Something went wrong. Please try again.';
}

/**
 * Mock-PSP settlement summary, mirroring the backend's
 * SettlementSummaryDto (GET /api/admin/payments/settlement, backlog
 * #134). Money is in cents: `capturedCents` sums amountCents over orders
 * carrying a capture id (a REFUNDED order was still captured, so it
 * counts there as well as in `refundedCents`), `refundedCents` sums
 * REFUNDED orders, and `netCents` is the difference. The remaining
 * fields are per-status order counts. All figures are reconciled from
 * the orders table — mock money only, no real processor is involved.
 */
export interface SettlementSummary {
  capturedCents: number;
  refundedCents: number;
  netCents: number;
  totalOrders: number;
  pendingCount: number;
  paidCount: number;
  completedCount: number;
  cancelledCount: number;
  refundedCount: number;
}

/**
 * Query keys for the ADMIN payments subtree, sibling to the users and
 * audit-log subtrees under ['admin', …] so invalidating one never
 * refetches the others. The settlement summary is a single unpaginated
 * aggregate, so its key carries no parameters.
 */
export const adminPaymentKeys = {
  all: ['admin', 'payments'] as const,
  settlement: (window: SettlementWindow = {}) =>
    [...adminPaymentKeys.all, 'settlement', window.from ?? null, window.to ?? null] as const,
  webhooks: (page: number, outcome: string) =>
    [...adminPaymentKeys.all, 'webhooks', page, outcome] as const,
  attempts: (page: number, outcome: string) =>
    [...adminPaymentKeys.all, 'attempts', page, outcome] as const,
};

/** Allowlisted webhook outcomes (backend WebhookEvent.Outcome). */
export const WEBHOOK_OUTCOMES = ['MATCHED', 'MISMATCH'] as const;

/**
 * One row of the ADMIN webhook-event viewer, mirroring the backend's
 * WebhookEventDto (GET /api/admin/payments/webhooks, backlog #156).
 * The ledger carries no order id — `pspReference` (a capture or refund
 * id) is the join key against the orders table — and no raw body or
 * signature is stored, so none exists here either.
 */
export interface WebhookEventEntry {
  id: number;
  eventId: string;
  eventType: string;
  pspReference: string | null;
  outcome: string;
  createdAt: string;
}

/**
 * GET /api/admin/payments/webhooks — ADMIN-only (the backend's
 * @PreAuthorize is the real gate). Paginated, newest first; the
 * outcome filter is omitted entirely when unset, so the unfiltered
 * request stays a plain paginated list. Callers must only pass
 * allowlisted outcome values — anything else is a backend 400.
 */
export function useAdminWebhookEvents(
  page = 0,
  outcome = '',
  enabled = true,
  size = 20,
) {
  return useQuery({
    queryKey: adminPaymentKeys.webhooks(page, outcome),
    queryFn: () =>
      api
        .get<ApiResponse<Page<WebhookEventEntry>>>('/admin/payments/webhooks', {
          params: { page, size, ...(outcome ? { outcome } : {}) },
        })
        .then((res) => res.data.data),
    enabled,
  });
}

/** Maps a failed webhook-events call to user-facing copy. */
export function describeWebhookEventsError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 403) {
    return 'You are not authorized to view webhook events.';
  }
  if (code === 401) {
    return 'Please log in again to view webhook events.';
  }
  if (code === 400) {
    return 'That filter is not valid.';
  }
  return message || 'Something went wrong. Please try again.';
}

/** Allowlisted payment-attempt outcomes (backend PaymentAttempt.Outcome). */
export const PAYMENT_ATTEMPT_OUTCOMES = ['SUCCEEDED', 'DECLINED'] as const;

/**
 * One row of the ADMIN payment-attempt viewer, mirroring the backend's
 * PaymentAttemptDto (GET /api/admin/payments/attempts, backlog #211).
 * The ledger records the order, the outcome and the order-scoped PSP
 * idempotency key the attempt ran under — the buyer's payment token is
 * never stored by the backend, so none exists here either.
 */
export interface PaymentAttemptEntry {
  id: number;
  orderId: number;
  outcome: string;
  idempotencyKey: string | null;
  createdAt: string;
}

/**
 * GET /api/admin/payments/attempts — ADMIN-only (the backend's
 * @PreAuthorize is the real gate). Paginated, newest first; the
 * outcome filter is omitted entirely when unset, so the unfiltered
 * request stays a plain paginated list. Callers must only pass
 * allowlisted outcome values — anything else is a backend 400.
 */
export function useAdminPaymentAttempts(
  page = 0,
  outcome = '',
  enabled = true,
  size = 20,
) {
  return useQuery({
    queryKey: adminPaymentKeys.attempts(page, outcome),
    queryFn: () =>
      api
        .get<ApiResponse<Page<PaymentAttemptEntry>>>('/admin/payments/attempts', {
          params: { page, size, ...(outcome ? { outcome } : {}) },
        })
        .then((res) => res.data.data),
    enabled,
  });
}

/** Maps a failed payment-attempts call to user-facing copy. */
export function describePaymentAttemptsError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 403) {
    return 'You are not authorized to view payment attempts.';
  }
  if (code === 401) {
    return 'Please log in again to view payment attempts.';
  }
  if (code === 400) {
    return 'That filter is not valid.';
  }
  return message || 'Something went wrong. Please try again.';
}

/**
 * Optional date window for the settlement summary (backlog #155).
 * `from`/`to` are ISO-8601 instants bounding order `createdAt` as
 * [from, to) on the backend — the same inclusive/exclusive bounds as the
 * seller summary window. Both omitted means all time.
 */
export interface SettlementWindow {
  from?: string;
  to?: string;
}

/**
 * GET /api/admin/payments/settlement — ADMIN-only (the backend's
 * @PreAuthorize is the real gate; the SPA route/nav hiding is cosmetic).
 * Non-admin callers get the JSON 403 envelope and anonymous callers 401 —
 * both surface as Axios errors here.
 */
export function useAdminPaymentSettlement(enabled = true, window: SettlementWindow = {}) {
  const hasWindow = window.from != null || window.to != null;
  return useQuery({
    queryKey: adminPaymentKeys.settlement(window),
    queryFn: () =>
      (hasWindow
        ? api.get<ApiResponse<SettlementSummary>>('/admin/payments/settlement', {
            params: {
              ...(window.from != null ? { from: window.from } : {}),
              ...(window.to != null ? { to: window.to } : {}),
            },
          })
        : api.get<ApiResponse<SettlementSummary>>('/admin/payments/settlement')
      ).then((res) => res.data.data),
    enabled,
  });
}

/** Maps a failed settlement call to user-facing copy. */
export function describeSettlementError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 403) {
    return 'You are not authorized to view the settlement summary.';
  }
  if (code === 401) {
    return 'Please log in again to view the settlement summary.';
  }
  if (code === 400) {
    return 'That date window is not valid — from must be before to.';
  }
  return message || 'Something went wrong. Please try again.';
}

/** Formats a cents amount the way the order pages do (¥ + two decimals). */
export function formatCents(cents: number): string {
  return `¥${(cents / 100).toFixed(2)}`;
}

/** Allowlisted dashboard windows (backend DashboardWindow, backlog #196). */
export const DASHBOARD_WINDOWS = ['24h', '7d', '30d'] as const;
export type DashboardWindow = (typeof DASHBOARD_WINDOWS)[number];

/**
 * ADMIN operations dashboard, mirroring the backend's DashboardDto
 * (GET /api/admin/dashboard, backlog #196). Counts only — the backend
 * payload carries no row data and no user identifiers. Windowed fields
 * count rows created in the look-back window (`window`/`windowStart`
 * echo it); `listingsAvailableNow`, `openReports` and `openDisputes`
 * are current snapshots the window does not affect. There is
 * deliberately no declines figure: a declined capture leaves no
 * durable row (the only count is the JVM-local payments.declined
 * meter), so the backend cuts it rather than estimating — see the
 * DashboardDto contract.
 */
export interface AdminDashboard {
  window: string;
  windowStart: string;
  usersRegistered: number;
  listingsCreated: number;
  listingsAvailableNow: number;
  listingsExpiredInWindow: number;
  ordersTotalInWindow: number;
  ordersPending: number;
  ordersPaid: number;
  ordersCompleted: number;
  ordersCancelled: number;
  ordersRefunded: number;
  ordersDisputed: number;
  captures: number;
  refunds: number;
  openReports: number;
  openDisputes: number;
  webhooksMatched: number;
  webhooksMismatched: number;
}

/**
 * Query keys for the ADMIN dashboard, sibling to the other admin
 * subtrees under ['admin', …]; the window is part of the key so
 * switching windows refetches under its own entry.
 */
export const adminDashboardKeys = {
  all: ['admin', 'dashboard'] as const,
  view: (window: DashboardWindow) => [...adminDashboardKeys.all, window] as const,
};

/**
 * GET /api/admin/dashboard — ADMIN-only (the backend's @PreAuthorize
 * is the real gate; the SPA route/nav hiding is cosmetic). The window
 * param is always sent explicitly so the request matches the key.
 */
export function useAdminDashboard(window: DashboardWindow, enabled = true) {
  return useQuery({
    queryKey: adminDashboardKeys.view(window),
    queryFn: () =>
      api
        .get<ApiResponse<AdminDashboard>>('/admin/dashboard', { params: { window } })
        .then((res) => res.data.data),
    enabled,
  });
}

/** Maps a failed dashboard call to user-facing copy. */
export function describeDashboardError(error: unknown): string {
  const response = (error as { response?: { data?: { code?: number; message?: string } } })
    .response;
  const code = response?.data?.code;
  const message = response?.data?.message ?? '';
  if (code === 403) {
    return 'You are not authorized to view the dashboard.';
  }
  if (code === 401) {
    return 'Please log in again to view the dashboard.';
  }
  if (code === 400) {
    return 'That window is not valid.';
  }
  return message || 'Something went wrong. Please try again.';
}
