/** @type {import('tailwindcss').Config} */
export default {
  content: ['./src/renderer/index.html', './src/renderer/src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // Kept in sync with web/tailwind.config.js — same brand identity
        // across web, Android (colors.xml) and desktop (docs/PLAN.md §9).
        brand: {
          50: '#f0fdf4',
          100: '#dcfce7',
          500: '#22c55e',
          600: '#16a34a',
          700: '#15803d',
        },
        dark: {
          800: '#181b20',
          850: '#131519',
          900: '#0c0e12',
          950: '#060709',
        },
        state: {
          connected: '#22c55e',
          connecting: '#f59e0b',
          reconnecting: '#f59e0b',
          error: '#ef4444',
          blocked: '#ef4444',
          disconnected: '#9ca3af',
        },
      },
    },
  },
  plugins: [],
};
