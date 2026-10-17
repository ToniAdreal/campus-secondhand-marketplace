import { useState } from 'react';
import { describeReceiptError, useOrderReceipt } from '../api/orders';

/**
 * Printable order receipt (backlog #180). The "Receipt" button fetches
 * GET /api/orders/{id}/receipt lazily — no receipt request fires until
 * the user asks for it — and renders the dedicated ReceiptDto: order,
 * listing, amount, both parties, transition timestamps and the mock
 * PSP references. A PENDING receipt is explicitly marked UNPAID.
 *
 * Printing is the browser's own print-to-PDF (no PDF library): the
 * Print button calls window.print(), and the print stylesheet in
 * index.css hides everything except `.receipt-sheet` while printing.
 * The PSP references are demo references, labelled as such — this is
 * a portfolio-demo document, not a real processor receipt.
 */
export default function OrderReceipt({ orderId }: { orderId: number }) {
  const [open, setOpen] = useState(false);
  const receipt = useOrderReceipt(orderId, open);

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="mt-2 text-sm text-blue-600 hover:underline no-print"
      >
        Receipt
      </button>
    );
  }

  return (
    <div className="mt-3">
      {receipt.isLoading && <p className="text-sm text-neutral-500">Loading receipt…</p>}
      {receipt.isError && (
        <p role="alert" className="text-sm text-red-600">
          {describeReceiptError(receipt.error)}
        </p>
      )}
      {receipt.data && (
        <div className="receipt-sheet rounded border border-neutral-200 p-4 text-sm">
          <div className="flex items-center justify-between gap-3">
            <h2 className="font-semibold">Receipt — order #{receipt.data.orderId}</h2>
            <span>{receipt.data.paid ? 'PAID' : 'UNPAID'}</span>
          </div>
          <dl className="mt-2 space-y-1">
            <div className="flex gap-2">
              <dt className="text-neutral-500">Item:</dt>
              <dd>{receipt.data.itemTitle}</dd>
            </div>
            <div className="flex gap-2">
              <dt className="text-neutral-500">Amount:</dt>
              <dd>{receipt.data.formattedPrice}</dd>
            </div>
            <div className="flex gap-2">
              <dt className="text-neutral-500">Buyer:</dt>
              <dd>{receipt.data.buyerUsername}</dd>
            </div>
            <div className="flex gap-2">
              <dt className="text-neutral-500">Seller:</dt>
              <dd>{receipt.data.sellerUsername}</dd>
            </div>
            <div className="flex gap-2">
              <dt className="text-neutral-500">Status:</dt>
              <dd>{receipt.data.status}</dd>
            </div>
            <div className="flex gap-2">
              <dt className="text-neutral-500">Ordered:</dt>
              <dd>{new Date(receipt.data.createdAt).toLocaleString()}</dd>
            </div>
            {receipt.data.paidAt && (
              <div className="flex gap-2">
                <dt className="text-neutral-500">Paid:</dt>
                <dd>{new Date(receipt.data.paidAt).toLocaleString()}</dd>
              </div>
            )}
            {receipt.data.completedAt && (
              <div className="flex gap-2">
                <dt className="text-neutral-500">Completed:</dt>
                <dd>{new Date(receipt.data.completedAt).toLocaleString()}</dd>
              </div>
            )}
            {receipt.data.captureId && (
              <div className="flex gap-2">
                <dt className="text-neutral-500">Payment reference (demo):</dt>
                <dd>{receipt.data.captureId}</dd>
              </div>
            )}
            {receipt.data.refundId && (
              <div className="flex gap-2">
                <dt className="text-neutral-500">Refund reference (demo):</dt>
                <dd>{receipt.data.refundId}</dd>
              </div>
            )}
          </dl>
          {!receipt.data.paid && (
            <p className="mt-2 text-neutral-500">
              This order has not been paid yet — no payment reference exists.
            </p>
          )}
          <button
            type="button"
            onClick={() => window.print()}
            className="mt-3 rounded bg-neutral-800 px-4 py-1.5 text-sm font-medium text-white hover:bg-neutral-700 no-print"
          >
            Print receipt
          </button>
        </div>
      )}
    </div>
  );
}
