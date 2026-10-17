package com.toni.marketplace.order;

/**
 * Body of {@code POST /api/orders/{id}/complete} — optional since backlog
 * #179. A SELLER caller must present the buyer's 6-digit
 * {@code handoverCode} (minted at pay time, shown to the buyer exactly
 * once); a missing or wrong code is the identical 400. An ADMIN caller
 * may omit it: the completion is then an explicit override, audited as
 * {@code ORDER_COMPLETED_ADMIN_OVERRIDE}. The whole body may be omitted
 * (treated as a missing code), mirroring the optional pay body (#92).
 */
public record CompleteOrderRequest(String handoverCode) {
}
