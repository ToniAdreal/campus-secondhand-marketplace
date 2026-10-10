import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import OrderPspReferences from './OrderPspReferences';

describe('OrderPspReferences (backlog #119)', () => {
  afterEach(() => {
    cleanup(); // vitest runs with globals off, so RTL's auto-cleanup is not registered
  });

  it('a PAID order renders its capture id, labelled as a demo reference', () => {
    render(
      <OrderPspReferences order={{ captureId: 'cap_mock_0123456789abcdef', refundId: null }} />,
    );
    expect(screen.getByText('Payment reference (demo): cap_mock_0123456789abcdef')).toBeTruthy();
    expect(screen.queryByText(/Refund reference/)).toBeNull();
  });

  it('a REFUNDED order renders both ids', () => {
    render(
      <OrderPspReferences
        order={{
          captureId: 'cap_mock_0123456789abcdef',
          refundId: 'rfd_mock_0123456789abcdef',
        }}
      />,
    );
    expect(screen.getByText('Payment reference (demo): cap_mock_0123456789abcdef')).toBeTruthy();
    expect(screen.getByText('Refund reference (demo): rfd_mock_0123456789abcdef')).toBeTruthy();
  });

  it('a PENDING order (both ids null) renders nothing', () => {
    const { container } = render(<OrderPspReferences order={{ captureId: null, refundId: null }} />);
    expect(container.firstChild).toBeNull();
    expect(screen.queryByText(/reference \(demo\)/)).toBeNull();
  });
});
