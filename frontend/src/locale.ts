/**
 * Locale preference (backlog #431): 'en' / 'zh-CN', default 'en'.
 *
 * The contract mirrors `theme.ts`, because the two preferences behave
 * the same way — frontend-only state, persisted in localStorage, no
 * backend resource:
 *
 * 1. `LOCALE_STORAGE_KEY` — the localStorage key the preference is
 *    persisted under.
 * 2. `readStoredLocale` — an unknown or missing stored value falls back
 *    to 'en' (never a crash, never a stuck locale from a corrupted
 *    value).
 * 3. `t` — the translation lookup. Plain (non-React) code such as the
 *    `describe*Error` mappers calls `t(key)` with no locale argument,
 *    which reads the stored locale at call time, so the copy always
 *    follows the current choice. React components subscribe to
 *    `useLocaleStore` and pass their locale explicitly, which is what
 *    makes them re-render when the toggle flips.
 *
 * The dictionary holds only the copy this round localizes: the order
 * `describe*Error` mappers, the step-up mapper, the login page and the
 * order confirmation page. Backend envelope messages are shown verbatim
 * (server copy — the frontend must not reword what the server said),
 * and the rest of the SPA stays English until later rounds; the README
 * scope row says so.
 */

export type Locale = 'en' | 'zh-CN';

export const LOCALE_STORAGE_KEY = 'campus-marketplace-locale';

export const LOCALES: Locale[] = ['en', 'zh-CN'];

function isLocale(value: unknown): value is Locale {
  return value === 'en' || value === 'zh-CN';
}

/** Reads the persisted locale; anything unreadable means 'en'. */
export function readStoredLocale(): Locale {
  try {
    if (typeof window === 'undefined' || !window.localStorage) return 'en';
    const stored = window.localStorage.getItem(LOCALE_STORAGE_KEY);
    return isLocale(stored) ? stored : 'en';
  } catch {
    return 'en';
  }
}

/** Persists a locale; a denied/unavailable storage must not break the toggle. */
export function setStoredLocale(locale: Locale): void {
  try {
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.setItem(LOCALE_STORAGE_KEY, locale);
    }
  } catch {
    // The in-memory choice still applies for this session.
  }
}

/**
 * The localized copy, keyed by a stable message id. The English string
 * in each entry is byte-identical to the copy the page or mapper showed
 * before #431, so the default locale changes nothing on screen.
 */
export const MESSAGES = {
  'order.noLongerAvailable': {
    en: 'Someone just grabbed this item — it is no longer available.',
    'zh-CN': '这件商品刚刚被人买走，现在已不可购买。',
  },
  'order.cannotBuyOwn': {
    en: 'You cannot buy your own listing.',
    'zh-CN': '你不能购买自己发布的商品。',
  },
  'order.loginToBuy': {
    en: 'Please log in to buy this item.',
    'zh-CN': '请先登录，再购买这件商品。',
  },
  'order.listingGone': {
    en: 'This listing no longer exists.',
    'zh-CN': '这件商品已不存在。',
  },
  'order.generic': {
    en: 'Something went wrong. Please try again.',
    'zh-CN': '出了点问题，请重试。',
  },
  'order.payDeclined': {
    en: 'Your payment was declined — the order is still pending, please try a different payment method.',
    'zh-CN': '付款被拒绝，订单仍为待付款状态，请换一种付款方式再试。',
  },
  'order.concurrentAction': {
    en: 'Someone just acted on this order — please refresh and try again.',
    'zh-CN': '刚刚有人操作了这笔订单，请刷新后重试。',
  },
  'order.cannotPayAnymore': {
    en: 'This order can no longer be paid — it was cancelled, completed, or refunded.',
    'zh-CN': '这笔订单已无法付款，可能已被取消、完成或退款。',
  },
  'order.onlyBuyerCanPay': {
    en: 'Only the buyer of this order can pay for it.',
    'zh-CN': '只有这笔订单的买家才能付款。',
  },
  'order.loginToPay': {
    en: 'Please log in to pay for this order.',
    'zh-CN': '请先登录，再为这笔订单付款。',
  },
  'order.notFound': {
    en: 'This order no longer exists.',
    'zh-CN': '这笔订单已不存在。',
  },
  'order.receiptUnavailable': {
    en: 'This receipt is not available.',
    'zh-CN': '这张收据暂不可用。',
  },
  'order.loginToViewReceipt': {
    en: 'Please log in to view this receipt.',
    'zh-CN': '请先登录，再查看这张收据。',
  },
  'order.cannotCancel': {
    en: 'This order can no longer be cancelled — it was already paid, completed, or cancelled.',
    'zh-CN': '这笔订单已无法取消，可能已付款、完成或取消。',
  },
  'order.onlyBuyerCanCancel': {
    en: 'Only the buyer of this order can cancel it.',
    'zh-CN': '只有这笔订单的买家才能取消。',
  },
  'order.loginToCancel': {
    en: 'Please log in to cancel this order.',
    'zh-CN': '请先登录，再取消这笔订单。',
  },
  'order.handoverWrong': {
    en: 'That handover code is not right — ask the buyer for the 6-digit code from their order page and try again.',
    'zh-CN': '交接码不正确，请向买家索取其订单页上的 6 位交接码后重试。',
  },
  'order.alreadyShipped': {
    en: 'This order has already shipped — only the buyer can confirm receipt now.',
    'zh-CN': '这笔订单已发货，现在只有买家能确认收货。',
  },
  'order.alreadyCompleted': {
    en: 'This order was already completed.',
    'zh-CN': '这笔订单已完成。',
  },
  'order.onlyPaidCanComplete': {
    en: 'Only a paid order can be completed — this one is not paid yet, or is already finished.',
    'zh-CN': '只有已付款的订单才能完成，这笔订单尚未付款或已经结束。',
  },
  'order.onlySellerCanComplete': {
    en: 'Only the seller of this listing can complete the order.',
    'zh-CN': '只有这件商品的卖家才能完成这笔订单。',
  },
  'order.loginToComplete': {
    en: 'Please log in to complete this order.',
    'zh-CN': '请先登录，再完成这笔订单。',
  },
  'order.shipFormInvalid': {
    en: 'Enter a carrier and tracking number (tracking number at most 128 characters).',
    'zh-CN': '请填写承运商和运单号（运单号最多 128 个字符）。',
  },
  'order.onlyPaidCanShip': {
    en: 'Only a paid order can be shipped — this one is not paid, or already shipped or completed.',
    'zh-CN': '只有已付款的订单才能发货，这笔订单尚未付款，或已发货、已完成。',
  },
  'order.onlySellerCanShip': {
    en: 'Only the seller of this listing can ship the order.',
    'zh-CN': '只有这件商品的卖家才能发货。',
  },
  'order.loginToShip': {
    en: 'Please log in to ship the order.',
    'zh-CN': '请先登录，再发货。',
  },
  'order.onlyShippedCanConfirm': {
    en: 'Only a shipped order can be confirmed — this one has not shipped yet, or is already finished.',
    'zh-CN': '只有已发货的订单才能确认收货，这笔订单尚未发货或已经结束。',
  },
  'order.onlyBuyerCanConfirm': {
    en: 'Only the buyer of this order can confirm receipt.',
    'zh-CN': '只有这笔订单的买家才能确认收货。',
  },
  'order.loginToConfirm': {
    en: 'Please log in to confirm receipt.',
    'zh-CN': '请先登录，再确认收货。',
  },
  'order.onlyBuyerCanHandover': {
    en: 'Only the buyer of this order can generate a handover code.',
    'zh-CN': '只有这笔订单的买家才能生成交接码。',
  },
  'order.handoverOnlyWhenPaid': {
    en: 'A handover code can only be generated while the order is paid.',
    'zh-CN': '只有订单处于已付款状态时才能生成交接码。',
  },
  'order.loginToHandover': {
    en: 'Please log in to generate a handover code.',
    'zh-CN': '请先登录，再生成交接码。',
  },
  'order.refundExceeds': {
    en: 'That amount is more than what is left to refund on this order.',
    'zh-CN': '退款金额超过了这笔订单剩余可退的金额。',
  },
  'order.refundPositive': {
    en: 'Enter a refund amount greater than zero.',
    'zh-CN': '请输入大于零的退款金额。',
  },
  'order.alreadyRefunded': {
    en: 'This order was already refunded.',
    'zh-CN': '这笔订单已退款。',
  },
  'order.completedNoRefund': {
    en: 'This order was already completed — it can no longer be refunded.',
    'zh-CN': '这笔订单已完成，无法再退款。',
  },
  'order.onlyPaidCanRefund': {
    en: 'Only a paid order can be refunded — this one is not paid yet, or is already finished.',
    'zh-CN': '只有已付款的订单才能退款，这笔订单尚未付款或已经结束。',
  },
  'order.refundReasonRequired': {
    en: 'Enter a reason for the refund.',
    'zh-CN': '请填写退款原因。',
  },
  'order.onlySellerCanRefund': {
    en: 'Only the seller of this listing can refund the order.',
    'zh-CN': '只有这件商品的卖家才能为这笔订单退款。',
  },
  'order.loginToRefund': {
    en: 'Please log in to refund this order.',
    'zh-CN': '请先登录，再为这笔订单退款。',
  },
  'auth.stepUpIncorrect': {
    en: 'Current password or code is incorrect — please check and try again.',
    'zh-CN': '当前密码或验证码不正确，请检查后重试。',
  },
  'auth.stepUpLocked': {
    en: 'This account is temporarily locked after too many failed attempts — please wait a few minutes and try again.',
    'zh-CN': '失败次数过多，该账号已被暂时锁定，请几分钟后再试。',
  },
  'auth.stepUpTooMany': {
    en: 'Too many verification attempts — please wait a moment and try again.',
    'zh-CN': '验证尝试过于频繁，请稍后再试。',
  },
  'login.title': {
    en: 'Login',
    'zh-CN': '登录',
  },
  'login.subtitle': {
    en: 'Sign in to buy and sell on campus',
    'zh-CN': '登录后即可在校园内买卖商品',
  },
  'login.resetDone': {
    en: 'Password reset complete. You have been signed out everywhere — log in with your new password.',
    'zh-CN': '密码重置完成，你已在所有设备上退出登录，请使用新密码登录。',
  },
  'login.identifierLabel': {
    en: 'Username or email',
    'zh-CN': '用户名或邮箱',
  },
  'login.passwordLabel': {
    en: 'Password',
    'zh-CN': '密码',
  },
  'login.submit': {
    en: 'Login',
    'zh-CN': '登录',
  },
  'login.submitting': {
    en: 'Logging in…',
    'zh-CN': '登录中…',
  },
  'login.required': {
    en: 'Username or email and password are required.',
    'zh-CN': '请输入用户名或邮箱和密码。',
  },
  'login.failed': {
    en: 'Login failed',
    'zh-CN': '登录失败',
  },
  'login.networkError': {
    en: 'Network error — is the backend running?',
    'zh-CN': '网络错误，后端服务是否在运行？',
  },
  'login.totpHint': {
    en: 'Enter the 6-digit code from your authenticator app — or one of your recovery codes if you no longer have the app.',
    'zh-CN': '请输入验证器应用中的 6 位代码；如果无法使用该应用，也可使用恢复代码。',
  },
  'login.totpLabel': {
    en: 'Two-factor code',
    'zh-CN': '双重验证码',
  },
  'login.totpRequired': {
    en: 'Enter your two-factor code or a recovery code.',
    'zh-CN': '请输入双重验证码或恢复代码。',
  },
  'login.totpFailed': {
    en: 'Two-factor authentication failed',
    'zh-CN': '双重验证失败',
  },
  'login.usePasskey': {
    en: 'Use a passkey instead',
    'zh-CN': '改用通行密钥',
  },
  'login.waitingPasskey': {
    en: 'Waiting for your passkey…',
    'zh-CN': '等待通行密钥…',
  },
  'login.passkeyCancelled': {
    en: 'The passkey ceremony was cancelled.',
    'zh-CN': '通行密钥验证已取消。',
  },
  'login.passkeyFailed': {
    en: 'Passkey sign-in failed',
    'zh-CN': '通行密钥登录失败',
  },
  'login.trustDevice': {
    en: 'Trust this device — skip the code step on this device for 30 days. Your password is still required at every login, and you can revoke the trust at any time from My sessions.',
    'zh-CN': '信任此设备，30 天内在此设备上跳过验证码步骤。每次登录仍需输入密码，你可以随时在“我的会话”中撤销信任。',
  },
  'login.verifyCode': {
    en: 'Verify code',
    'zh-CN': '验证代码',
  },
  'login.verifying': {
    en: 'Verifying…',
    'zh-CN': '验证中…',
  },
  'login.backToLogin': {
    en: 'Back to login',
    'zh-CN': '返回登录',
  },
  'login.forgotPassword': {
    en: 'Forgot password?',
    'zh-CN': '忘记密码？',
  },
  'login.newHere': {
    en: 'New to Campus Marketplace?',
    'zh-CN': '初次来到校园集市？',
  },
  'login.createAccount': {
    en: 'Create an account',
    'zh-CN': '创建账号',
  },
  'orderPage.loading': {
    en: 'Loading…',
    'zh-CN': '加载中…',
  },
  'orderPage.notFound': {
    en: 'Order not found.',
    'zh-CN': '未找到该订单。',
  },
  'orderPage.title': {
    en: 'Order confirmed',
    'zh-CN': '订单已确认',
  },
  'orderPage.statusLine': {
    en: 'Order #{id} is {status}.',
    'zh-CN': '订单 #{id}，状态：{status}。',
  },
  'orderPage.reservedNote': {
    en: 'The listing is now reserved for you.',
    'zh-CN': '该商品现已为你保留。',
  },
  'orderPage.releasedNote': {
    en: 'The listing has been released for other buyers.',
    'zh-CN': '该商品已重新上架，可供其他买家购买。',
  },
  'orderPage.amount': {
    en: 'Amount:',
    'zh-CN': '金额：',
  },
  'orderPage.status': {
    en: 'Status:',
    'zh-CN': '状态：',
  },
  'orderPage.payNow': {
    en: 'Pay now (demo)',
    'zh-CN': '立即付款（演示）',
  },
  'orderPage.paying': {
    en: 'Paying…',
    'zh-CN': '付款中…',
  },
  'orderPage.payNote': {
    en: 'Portfolio-demo note: this is a mock capture — no real money moves. The button is safe to press twice; the backend returns the already-paid order instead of charging again.',
    'zh-CN': '演示说明：这是一次模拟扣款，不会产生真实资金流动。重复点击是安全的，后端会直接返回已付款的订单，不会重复扣款。',
  },
  'orderPage.paidNote': {
    en: 'Paid (demo capture) — the seller will now confirm the handoff.',
    'zh-CN': '已付款（模拟扣款），卖家接下来会确认交接。',
  },
  'orderPage.handoverCode': {
    en: 'Your handover code:',
    'zh-CN': '你的交接码：',
  },
  'orderPage.handoverHint': {
    en: 'Give this code to the seller when you receive the item — they need it to complete the order. Save it now: for your security it is shown only once and cannot be displayed again.',
    'zh-CN': '收到商品时请把这串代码交给卖家，他们需要它来完成订单。请立即保存：出于安全考虑，交接码只显示一次，无法再次查看。',
  },
  'orderPage.handoverLost': {
    en: 'Your handover code was shown once when you paid. If you no longer have it, generate a new one — the seller needs it to complete the order.',
    'zh-CN': '交接码已在付款时显示过一次。如果你找不到了，请生成一个新的，卖家需要它来完成订单。',
  },
  'orderPage.regenerate': {
    en: 'Generate a new handover code',
    'zh-CN': '生成新的交接码',
  },
  'orderPage.generating': {
    en: 'Generating…',
    'zh-CN': '生成中…',
  },
  'orderPage.regenerateConfirm': {
    en: 'Generate a new handover code? The previous code will stop working.',
    'zh-CN': '生成新的交接码吗？旧的交接码将立即失效。',
  },
  'orderPage.cancel': {
    en: 'Cancel order',
    'zh-CN': '取消订单',
  },
  'orderPage.cancelling': {
    en: 'Cancelling…',
    'zh-CN': '取消中…',
  },
  'orderPage.cancelNote': {
    en: 'Cancelling releases the listing so other buyers can grab it.',
    'zh-CN': '取消后该商品将重新上架，其他买家可以购买。',
  },
  'orderPage.cancelConfirm': {
    en: 'Cancel this order? The listing will become available for other buyers again.',
    'zh-CN': '确定取消这笔订单吗？该商品将重新上架。',
  },
  'orderPage.cancelledNote': {
    en: 'This order was cancelled — the listing is available again.',
    'zh-CN': '这笔订单已取消，商品已重新上架。',
  },
  'orderPage.backToListing': {
    en: 'Back to the listing',
    'zh-CN': '返回商品页',
  },
  'locale.switchLabel': {
    en: 'Switch language to Chinese',
    'zh-CN': '切换语言为英文',
  },
  'locale.switchTitle': {
    en: 'Switch language',
    'zh-CN': '切换语言',
  },
} as const;

export type MessageKey = keyof typeof MESSAGES;

/**
 * Looks up a message in the given locale (default: the stored locale).
 * `vars` fills `{name}` placeholders; an unknown locale falls back to
 * the English string, never to a blank.
 */
export function t(
  key: MessageKey,
  locale: Locale = readStoredLocale(),
  vars?: Record<string, string | number>,
): string {
  const entry = MESSAGES[key];
  let text: string = entry[locale] ?? entry.en;
  if (vars) {
    for (const [name, value] of Object.entries(vars)) {
      // split/join instead of replaceAll: the tsconfig lib predates
      // ES2021, so replaceAll does not type-check here.
      text = text.split(`{${name}}`).join(String(value));
    }
  }
  return text;
}
