import tokens from '../design-tokens/tokens.mjs';

/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        brand: tokens.brand,
        dark: tokens.dark,
      }
    },
  },
  plugins: [],
};
