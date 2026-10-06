import axios from 'axios';
import { type FormEvent, type ReactNode, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useCategories } from '../api/categories';
import { uploadItemPhoto, useCreateListing } from '../api/items';
import { useAuthStore } from '../store/useAuthStore';

/**
 * Create-listing form. Mirrors the backend validation on POST /api/items
 * client-side (title required + ≤200 chars, description ≤5000, price > 0)
 * so mistakes surface instantly; anything the server still rejects comes back
 * in the {code,message,data} envelope and is mapped back onto the offending
 * field when the message carries one ("priceCents: must be greater than 0").
 */

const MAX_TITLE = 200;
const MAX_DESCRIPTION = 5000;
const MAX_PHOTO_BYTES = 5 * 1024 * 1024;
const PHOTO_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'];

/** Backend validation messages arrive as "field: human message". */
const FIELD_MAP: Record<string, string> = {
  title: 'title',
  description: 'description',
  priceCents: 'price',
  categoryId: 'category',
};

function envelopeMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const message = (err.response?.data as { message?: string } | undefined)?.message;
    if (message) return message;
    if (!err.response) return 'Network error — is the backend running?';
  }
  return 'Something went wrong';
}

/** Splits an envelope message into a field error + the general remainder. */
function splitFieldError(message: string): { field: string | null; general: string | null } {
  const m = /^([a-zA-Z]+):\s*(.+)$/.exec(message);
  if (m && FIELD_MAP[m[1]]) {
    return { field: FIELD_MAP[m[1]], general: m[2] };
  }
  return { field: null, general: message };
}

type FieldErrors = Record<string, string>;

export default function CreateListingPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const user = useAuthStore((s) => s.user);
  const createListing = useCreateListing();
  const categoriesQuery = useCategories();

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [price, setPrice] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [photo, setPhoto] = useState<File | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [generalError, setGeneralError] = useState<ReactNode>(null);
  const [pending, setPending] = useState(false);

  if (!user) {
    // Selling requires a session; LoginPage bounces back here afterwards.
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  function validate(): FieldErrors {
    const errors: FieldErrors = {};
    if (title.trim() === '') {
      errors.title = 'Title is required.';
    } else if (title.trim().length > MAX_TITLE) {
      errors.title = `Title must be at most ${MAX_TITLE} characters.`;
    }
    if (description.length > MAX_DESCRIPTION) {
      errors.description = `Description must be at most ${MAX_DESCRIPTION} characters.`;
    }
    const dollars = Number(price.trim());
    if (price.trim() === '' || !Number.isFinite(dollars)) {
      errors.price = 'Enter a price in USD, e.g. 12.50.';
    } else if (dollars <= 0) {
      errors.price = 'Price must be greater than 0.';
    }
    if (photo) {
      if (!PHOTO_TYPES.includes(photo.type)) {
        errors.photo = 'Photo must be a PNG, JPEG, WebP or GIF image.';
      } else if (photo.size > MAX_PHOTO_BYTES) {
        errors.photo = 'Photo must be at most 5 MB.';
      }
    }
    return errors;
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    const errors = validate();
    setFieldErrors(errors);
    setGeneralError(null);
    if (Object.keys(errors).length > 0) return;

    setPending(true);
    try {
      const item = await createListing.mutateAsync({
        title: title.trim(),
        description: description.trim() === '' ? undefined : description.trim(),
        priceCents: Math.round(Number(price.trim()) * 100),
        categoryId: categoryId === '' ? null : Number(categoryId),
      });

      if (photo) {
        try {
          await uploadItemPhoto(item.id, photo);
        } catch (photoErr) {
          // The listing exists — the photo is the only thing that failed.
          // Stay on the page so the seller sees what happened.
          setGeneralError(
            <>
              Your listing was created, but the photo upload failed:{' '}
              {envelopeMessage(photoErr)}{' '}
              <Link to={`/items/${item.id}`} className="underline">
                View your listing
              </Link>
            </>,
          );
          return;
        }
      }
      navigate(`/items/${item.id}`);
    } catch (err) {
      const message = envelopeMessage(err);
      const { field, general } = splitFieldError(message);
      if (field) {
        setFieldErrors({ [field]: general ?? message });
      } else {
        setGeneralError(general);
      }
    } finally {
      setPending(false);
    }
  }

  const inputClass = (field: string) =>
    `w-full rounded border px-3 py-2 text-sm ${
      fieldErrors[field] ? 'border-red-400' : 'border-neutral-300'
    }`;

  return (
    <div className="mx-auto max-w-xl rounded-lg bg-white p-6 shadow-sm">
      <h1 className="mb-4 text-xl font-bold">Sell an item</h1>
      {generalError && (
        <p role="alert" className="mb-4 rounded bg-red-50 px-3 py-2 text-sm text-red-700">
          {generalError}
        </p>
      )}
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <div>
          <label htmlFor="listing-title" className="mb-1 block text-sm font-medium">
            Title
          </label>
          <input
            id="listing-title"
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            className={inputClass('title')}
            placeholder="e.g. Used road bike"
          />
          {fieldErrors.title && (
            <p className="mt-1 text-sm text-red-600">{fieldErrors.title}</p>
          )}
        </div>
        <div>
          <label htmlFor="listing-description" className="mb-1 block text-sm font-medium">
            Description <span className="font-normal text-neutral-500">(optional)</span>
          </label>
          <textarea
            id="listing-description"
            rows={4}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className={inputClass('description')}
            placeholder="Condition, pickup location, …"
          />
          {fieldErrors.description && (
            <p className="mt-1 text-sm text-red-600">{fieldErrors.description}</p>
          )}
        </div>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label htmlFor="listing-price" className="mb-1 block text-sm font-medium">
              Price (USD)
            </label>
            <input
              id="listing-price"
              type="text"
              inputMode="decimal"
              value={price}
              onChange={(e) => setPrice(e.target.value)}
              className={inputClass('price')}
              placeholder="12.50"
            />
            {fieldErrors.price && (
              <p className="mt-1 text-sm text-red-600">{fieldErrors.price}</p>
            )}
          </div>
          <div>
            <label htmlFor="listing-category" className="mb-1 block text-sm font-medium">
              Category <span className="font-normal text-neutral-500">(optional)</span>
            </label>
            <select
              id="listing-category"
              value={categoryId}
              onChange={(e) => setCategoryId(e.target.value)}
              disabled={categoriesQuery.isLoading || categoriesQuery.isError}
              className={inputClass('category')}
            >
              <option value="">No category</option>
              {(categoriesQuery.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
            {categoriesQuery.isError && (
              <p className="mt-1 text-sm text-neutral-500">
                Couldn&apos;t load categories — you can still list without one.
              </p>
            )}
            {fieldErrors.category && (
              <p className="mt-1 text-sm text-red-600">{fieldErrors.category}</p>
            )}
          </div>
        </div>
        <div>
          <label htmlFor="listing-photo" className="mb-1 block text-sm font-medium">
            Photo <span className="font-normal text-neutral-500">(optional, ≤ 5 MB)</span>
          </label>
          <input
            id="listing-photo"
            type="file"
            accept="image/png,image/jpeg,image/webp,image/gif"
            onChange={(e) => setPhoto(e.target.files?.[0] ?? null)}
            className="w-full text-sm"
          />
          {fieldErrors.photo && (
            <p className="mt-1 text-sm text-red-600">{fieldErrors.photo}</p>
          )}
        </div>
        <button
          type="submit"
          disabled={pending}
          className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {pending ? 'Publishing…' : 'Publish listing'}
        </button>
      </form>
    </div>
  );
}
