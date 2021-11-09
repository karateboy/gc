module.exports = {
  root: true,

  'extends': [
    'plugin:vue/essential',
    '@vue/standard',
    'eslint:recommended',
    '@vue/prettier'
  ],

  rules: {
    'generator-star-spacing': 'off',
    'no-debugger': process.env.NODE_ENV === 'production' ? 'warn' : 'off',
    'vue/no-parsing-error': [
      2,
      {
        'x-invalid-end-tag': false
      }
    ],
    'no-undef': 'off',
    camelcase: 'off',
    'space-before-function-paren': 'off',
    'new-cap': 'off',
    semi: 'off',
    quotes: 'off',
    'comma-dangle': 'off',
    'no-console': process.env.NODE_ENV === 'production' ? 'warn' : 'off'
  },

  parserOptions: {
    parser: 'babel-eslint'
  },

  env: {
    node: true
  }
}
