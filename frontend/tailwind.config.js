/** @type {import('tailwindcss').Config} */
// Palette and type follow the team's design theme: near-black primary, teal accent, pastel badges.
module.exports = {
  content: ['./src/**/*.{html,ts}'],
  theme: {
    extend: {
      colors: {
        navy: {
          50: '#f0f4f8',
          100: '#d9e2ec',
          200: '#bcccdc',
          300: '#9fb3c8',
          400: '#829ab1',
          500: '#627d98',
          600: '#486581',
          700: '#334e68',
          800: '#243b53',
          900: '#111827',
        },
        primary: '#111827',
        accent: '#00897b',
        destructive: '#DC2626',
        surface: '#ffffff',
        background: '#f9fafb',
        success: '#059669',
        warning: '#D97706',
        critical: '#7C3AED',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
};
