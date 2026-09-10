import tokens from '../design-tokens/tokens.mjs';

/** @type {import('tailwindcss').Config} */
export default {
  content: ['./src/renderer/index.html', './src/renderer/src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // Same brand identity across web, Android (colors.xml) and desktop
        // (docs/PLAN.md §9) — values live in design-tokens/tokens.mjs now.
        brand: tokens.brand,
        dark: tokens.dark,
        state: tokens.state,
      },
    },
  },
  plugins: [],
};
