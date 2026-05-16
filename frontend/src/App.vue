<script setup lang="ts">
import { NMessageProvider, NConfigProvider } from 'naive-ui'
import type { GlobalThemeOverrides } from 'naive-ui'

const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#c27a3e',
    primaryColorHover: '#d4956a',
    primaryColorPressed: '#9c6231',
    primaryColorSuppl: '#c27a3e',
    borderRadius: '10px',
    borderRadiusSmall: '8px',
    fontFamily: "'Crimson Pro', serif",
    fontFamilyMono: "'DM Mono', monospace",
    bodyColor: '#f5f3ee',
    cardColor: '#ffffff',
    modalColor: '#ffffff',
    popoverColor: '#ffffff',
    inputColor: '#ffffff',
    inputColorDisabled: '#f5f3ee',
    borderColor: '#e8e5de',
    dividerColor: '#e8e5de',
    textColor1: '#1a1917',
    textColor2: '#4a4740',
    textColor3: '#9a9590',
    placeholderColor: '#9a9590',
    successColor: '#3a8a5c',
    warningColor: '#b89a3a',
    errorColor: '#b84a4a',
    infoColor: '#5a7ab8',
    fontWeightStrong: '600',
  },
  Switch: {
    railColorActive: '#c27a3e',
    buttonColor: '#ffffff',
    railColor: '#e8e5de',
  },
  Button: {
    color: '#1a1917',
    colorHover: '#1a1917',
    colorPressed: '#1a1917',
    colorFocus: '#1a1917',
    textColor: '#f5f3ee',
    textColorHover: '#f5f3ee',
    textColorPressed: '#f5f3ee',
    border: 'none',
    borderHover: 'none',
    borderPressed: 'none',
    borderFocus: 'none',
    fontWeight: '600',
  },
  Tag: {
    borderRadius: '20px',
    fontWeight: '500',
  },
  Drawer: {
    color: '#ffffff',
    borderRadius: '0',
  },
  Card: {
    color: '#ffffff',
    borderColor: '#e8e5de',
    borderRadius: '14px',
    paddingMedium: '24px',
  },
  Input: {
    border: '1px solid #e8e5de',
    borderHover: '1px solid #c27a3e',
    borderFocus: '1px solid #c27a3e',
    boxShadowFocus: '0 0 0 4px rgba(194, 122, 62, 0.08)',
    caretColor: '#c27a3e',
  },
  Checkbox: {
    color: '#ffffff',
    border: '2px solid #e8e5de',
    borderRadius: '4px',
    checkMarkColor: '#ffffff',
    colorChecked: '#c27a3e',
    borderChecked: '2px solid #c27a3e',
  },
}
</script>

<template>
  <n-config-provider :theme-overrides="themeOverrides">
    <n-message-provider>
      <router-view />
    </n-message-provider>
  </n-config-provider>
</template>

<style lang="scss">
@use '@/styles/global';
</style>