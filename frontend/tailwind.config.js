/** @type {import('tailwindcss').Config} */
export default {
  // Dark mode is class-based (backlog #227): the `dark` class on
  // <html> — set pre-paint by the index.html init script and on boot
  // by main.tsx — switches every `dark:` variant. The default
  // media-query strategy would fight the stored light/dark choice.
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      // Apple design language (HIG): SF Pro on Apple platforms via the
      // system stack, Inter off-platform. Weights in use: 400/600/700
      // only — 500 is not part of the ladder.
      fontFamily: {
        sans: [
          '-apple-system',
          'BlinkMacSystemFont',
          '"SF Pro Text"',
          '"SF Pro Display"',
          'Inter',
          '"Helvetica Neue"',
          '"PingFang SC"',
          '"Microsoft YaHei"',
          'Arial',
          'sans-serif',
        ],
      },
      colors: {
        // The blue scale is re-anchored on Apple's action blues so every
        // existing blue-* utility speaks the single-accent language:
        // 600 = Action Blue #0066cc (the only interactive color),
        // 500 = #0071e3 (focus/selected), 400 = #2997ff (on dark),
        // 700 = #0071e3 (hover brightens, as on apple.com).
        blue: {
          50: '#f0f6fe',
          100: '#dcebfb',
          400: '#2997ff',
          500: '#0071e3',
          600: '#0066cc',
          700: '#0071e3',
        },
        // Neutral anchors: parchment page canvas, hairline borders, ink.
        neutral: {
          100: '#f5f5f7',
          200: '#e0e0e0',
          900: '#1d1d1f',
        },
        // Semantic Apple tokens (from the HIG token set).
        apple: {
          blue: '#0066cc',
          focus: '#0071e3',
          sky: '#2997ff',
          ink: '#1d1d1f',
          softink: '#333333',
          muted: '#6e6e73',
          parchment: '#f5f5f7',
          pearl: '#fafafc',
          hairline: '#e0e0e0',
          soft: '#f0f0f0',
          danger: '#ff3b30',
          success: '#34c759',
        },
      },
    },
  },
  plugins: [],
};
