import { useQuery } from '@tanstack/react-query';
import { api } from './client';

export interface Item {
  id: number;
  title: string;
  description: string | null;
  priceCents: number;
  status: 'AVAILABLE' | 'RESERVED' | 'SOLD';
  sellerId: number;
  categoryId: number | null;
  categoryName: string | null;
  photoUrl: string | null;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export const itemKeys = {
  all: ['items'] as const,
  list: (page: number, q?: string) => [...itemKeys.all, 'list', page, q ?? ''] as const,
  detail: (id: number) => [...itemKeys.all, 'detail', id] as const,
};

export function useItems(page = 0, q = '') {
  return useQuery({
    queryKey: itemKeys.list(page, q),
    queryFn: () =>
      api
        .get<ApiResponse<Page<Item>>>(
          '/items',
          { params: { page, size: 20, ...(q.trim() !== '' ? { q: q.trim() } : {}) } },
        )
        .then((res) => res.data.data),
  });
}

export function useItem(id: number) {
  return useQuery({
    queryKey: itemKeys.detail(id),
    queryFn: () =>
      api.get<ApiResponse<Item>>(`/items/${id}`).then((res) => res.data.data),
  });
}
