import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import OrderStatusChip from './OrderStatusChip';
import type { Order } from '../api/orders';

// Every status the backend's OrderStatus enum can produce (mirroring the
// Order union in api/orders.ts), with the semantic hue the chip assigns
// it. The two order lists share this chip (#53/#72), so a status added
// to the union without a chip class would break the Record type — this
// table pins the rendered label and hue for each status in one place.
const CASES: Array<{ status: Order['status']; hue: string }> = [
  { status: 'PENDING', hue: 'bg-amber-100' },
  { status: 'PAID', hue: 'bg-green-100' },
  { status: 'SHIPPED', hue: 'bg-teal-100' },
  { status: 'COMPLETED', hue: 'bg-sky-100' },
  { status: 'CANCELLED', hue: 'bg-neutral-200' },
  { status: 'REFUNDED', hue: 'bg-purple-100' },
  { status: 'DISPUTED', hue: 'bg-red-100' },
];

afterEach(cleanup);

describe('OrderStatusChip (backlog #283)', () => {
  for (const { status, hue } of CASES) {
    it(`renders the ${status} label with its semantic hue`, () => {
      render(<OrderStatusChip status={status} />);

      const chip = screen.getByText(status);
      expect(chip.tagName).toBe('SPAN');
      expect(chip.className).toContain(hue);
    });
  }
});
