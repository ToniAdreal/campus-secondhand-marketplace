/** @type {import('tailwindcss').Config} */
export default {
  // Dark mode is class-based (backlog #227): the `dark` class on
  // <html> — set pre-paint by the index.html init script and on boot
  // by main.tsx — switches every `dark:` variant. The default
  // media-query strategy would fight the stored light/dark choice.
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: { extend: {} },
  plugins: [],
};
