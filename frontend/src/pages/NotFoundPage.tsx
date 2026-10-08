import { Link } from 'react-router-dom';

/**
 * Catch-all route for paths the router doesn't know. It renders inside the
 * app shell (App's <Outlet />), so the header/footer stay put and only the
 * content area shows the 404.
 */
export default function NotFoundPage() {
  return (
    <div className="mx-auto max-w-md py-16 text-center">
      <h1 className="text-3xl font-bold">This page doesn&apos;t exist</h1>
      <p className="mt-3 text-neutral-600">
        The address you followed doesn&apos;t match anything in this marketplace.
      </p>
      <Link to="/" className="mt-6 inline-block text-blue-600 hover:underline">
        Back to home
      </Link>
    </div>
  );
}
