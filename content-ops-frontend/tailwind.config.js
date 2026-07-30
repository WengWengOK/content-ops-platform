/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './index.html',
    './src/**/*.{js,ts,jsx,tsx}',
  ],
  theme: {
    extend: {
      colors: {
        primary: {
          50: '#FFF0F5',
          100: '#FFD6E7',
          200: '#FFADC8',
          300: '#FF84A9',
          400: '#FF5C8A',
          500: '#FF2D5E',
          600: '#E8164A',
          700: '#C40E3A',
          800: '#9F0B2F',
        },
        success: {
          50: '#E8F8F0',
          500: '#00B42A',
          600: '#009A24',
        },
        warning: {
          50: '#FFF7E8',
          500: '#FF7D00',
          600: '#E56E00',
        },
        error: {
          50: '#FFECE8',
          500: '#F53F3F',
          600: '#D63131',
        },
        info: {
          50: '#E8F3FF',
          500: '#165DFF',
          600: '#0E42D2',
        },
        neutral: {
          50: '#F7F8FA',
          100: '#F2F3F5',
          200: '#E5E6EB',
          300: '#C9CDD4',
          400: '#86909C',
          500: '#4E5969',
          600: '#1D2129',
          700: '#17171A',
          800: '#0E0E10',
        },
        chart: {
          1: '#165DFF',
          2: '#F77234',
          3: '#7B61FF',
          4: '#F7BA1E',
          5: '#14C9C9',
          6: '#9FDB1D',
        },
      },
      borderRadius: {
        sm: '4px',
        md: '8px',
        lg: '12px',
        xl: '16px',
      },
      fontFamily: {
        display: ['Inter', 'PingFang SC', 'Microsoft YaHei', 'system-ui', 'sans-serif'],
        body: ['Inter', 'PingFang SC', 'Microsoft YaHei', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'Fira Code', 'monospace'],
      },
    },
  },
  plugins: [],
}
