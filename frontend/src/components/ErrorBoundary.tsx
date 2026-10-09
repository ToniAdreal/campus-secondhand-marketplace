import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
}

/**
 * Route-level error boundary around the app's <Outlet /> (wired in App.tsx).
 *
 * Without a boundary, a render error in any route component unmounts the
 * whole React tree and leaves a blank page. This boundary catches the error,
 * logs it (console.error — visible in dev tools; there is no remote error
 * reporting in this project), and renders a friendly fallback inside the app
 * shell instead, so the header/nav stay usable.
 *
 * The "Back to home" link resets the boundary's error state on click:
 * without the reset the boundary would keep showing the fallback even after
 * navigating to a healthy route, because it stays mounted around the outlet
 * for the lifetime of the shell.
 *
 * Honest scope: this catches render/lifecycle errors in the route subtree
 * only. Errors in event handlers, async code, and the shell itself (header)
 * are not caught by any React error boundary.
 */
export default class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false };

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('ErrorBoundary caught an error:', error, info);
  }

  private handleReset = (): void => {
    this.setState({ hasError: false });
  };

  render(): ReactNode {
    if (this.state.hasError) {
      return (
        <div className="mx-auto max-w-md py-16 text-center">
          <h1 className="text-3xl font-bold">Something went wrong</h1>
          <p className="mt-3 text-neutral-600">
            This page hit an unexpected error. The rest of the marketplace is
            still available — heading back home should recover.
          </p>
          <Link
            to="/"
            onClick={this.handleReset}
            className="mt-6 inline-block text-blue-600 hover:underline"
          >
            Back to home
          </Link>
        </div>
      );
    }

    return this.props.children;
  }
}
