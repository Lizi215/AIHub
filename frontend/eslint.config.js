import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import prettier from 'eslint-config-prettier'

// ESLint 扁平配置。prettier 放最后，关掉与 Prettier 冲突的格式规则，ESLint 只管代码质量。

export default [
  {
    ignores: ['dist/**', 'node_modules/**', 'coverage/**']
  },

  js.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  prettier,

  // 浏览器侧代码
  {
    files: ['src/**/*.{js,vue}'],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
      globals: {
        window: 'readonly',
        document: 'readonly',
        navigator: 'readonly',
        localStorage: 'readonly',
        sessionStorage: 'readonly',
        crypto: 'readonly',
        AbortController: 'readonly',
        TextDecoder: 'readonly',
        console: 'readonly',
        fetch: 'readonly',
        FormData: 'readonly',
        Blob: 'readonly',
        File: 'readonly',
        URL: 'readonly',
        atob: 'readonly',
        btoa: 'readonly',
        setTimeout: 'readonly',
        clearTimeout: 'readonly',
        setInterval: 'readonly',
        clearInterval: 'readonly'
      }
    },
    rules: {
      'no-unused-vars': ['error', { argsIgnorePattern: '^_' }]
    }
  },

  // 构建配置运行在 Node 环境
  {
    files: ['vite.config.js'],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
      globals: {
        process: 'readonly',
        console: 'readonly'
      }
    }
  }
]
