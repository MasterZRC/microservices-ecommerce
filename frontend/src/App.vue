<template>
  <el-config-provider :locale="locale">
    <router-view />
  </el-config-provider>
</template>

<script setup>
import { ref } from 'vue'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'

const locale = ref(zhCn)
</script>

<style>
:root {
  --neo-bg: #f7f3e9;
  --neo-surface: #fffef9;
  --neo-text: #121212;
  --neo-text-soft: #374151;
  --neo-primary: #2563eb;
  --neo-danger: #ef4444;
  --neo-border: #101010;
  --neo-shadow: 6px 6px 0 #101010;
  --neo-radius: 16px;
  --neo-space-1: 8px;
  --neo-space-2: 12px;
  --neo-space-3: 16px;
  --neo-space-4: 24px;
  --neo-space-5: 32px;
  --neo-fs-xs: 12px;
  --neo-fs-s: 13px;
  --neo-fs-sm: 14px;
  --neo-fs-md: 16px;
  --neo-fs-lg: 20px;
  --neo-fs-xl: 28px;
  --neo-fs-xxl: 36px;
  --neo-line-tight: 1.2;
  --neo-line-normal: 1.5;
}

* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Text', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  color: var(--neo-text);
  background: repeating-linear-gradient(
      -45deg,
      #f7f3e9,
      #f7f3e9 20px,
      #f2eddf 20px,
      #f2eddf 40px
    );
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  text-rendering: optimizeLegibility;
  line-height: var(--neo-line-normal);
}

#app {
  min-height: 100vh;
}

h1, h2, h3, h4 {
  color: var(--neo-text);
  letter-spacing: -0.2px;
}

.page-container {
  max-width: 1240px;
  margin: 0 auto;
  padding: 0 24px;
}

.page-title {
  font-size: var(--neo-fs-xl);
  font-weight: 900;
  margin-bottom: var(--neo-space-3);
}

.neo-h1 {
  font-size: var(--neo-fs-xxl);
  font-weight: 900;
  line-height: var(--neo-line-tight);
}

.neo-h2 {
  font-size: var(--neo-fs-xl);
  font-weight: 900;
  line-height: var(--neo-line-tight);
}

.neo-h3 {
  font-size: var(--neo-fs-lg);
  font-weight: 800;
  line-height: var(--neo-line-tight);
}

.neo-body {
  font-size: var(--neo-fs-md);
  color: var(--neo-text);
}

.neo-caption {
  font-size: var(--neo-fs-sm);
  color: var(--neo-text-soft);
}

.neo-panel {
  border: 3px solid var(--neo-border);
  border-radius: var(--neo-radius);
  background: var(--neo-surface);
  box-shadow: var(--neo-shadow);
}

.neo-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 10px;
  border: 2px solid var(--neo-border);
  border-radius: 999px;
  background: #fff;
  font-size: var(--neo-fs-xs);
  font-weight: 700;
}

.el-card {
  border-radius: var(--neo-radius) !important;
  border: 3px solid var(--neo-border) !important;
  background: var(--neo-surface) !important;
  box-shadow: var(--neo-shadow) !important;
}

.el-button {
  border-radius: 12px !important;
  border: 3px solid var(--neo-border) !important;
  box-shadow: 3px 3px 0 var(--neo-border) !important;
  font-weight: 700 !important;
}

.el-button--primary {
  background: var(--neo-primary) !important;
  color: #fff !important;
}

.el-button--danger {
  background: var(--neo-danger) !important;
  color: #fff !important;
}

.el-input__wrapper,
.el-select__wrapper {
  border-radius: 12px !important;
  box-shadow: none !important;
  border: 3px solid var(--neo-border) !important;
  background: #fff !important;
}

.el-button:active {
  transform: translate(2px, 2px);
  box-shadow: 1px 1px 0 var(--neo-border) !important;
}

.el-table {
  border: 3px solid var(--neo-border);
  border-radius: 14px;
  overflow: hidden;
}

.el-table th.el-table__cell {
  background: #ffe08a !important;
  color: var(--neo-text);
  font-weight: 800;
}

.el-table tr {
  background: #fffef9;
}

.el-pagination {
  --el-pagination-bg-color: #fffef9;
  --el-pagination-text-color: #111827;
}

.el-pagination .btn-next,
.el-pagination .btn-prev,
.el-pagination .number {
  border: 2px solid #101010;
  border-radius: 8px;
  margin: 0 3px;
  min-width: 30px;
}

.el-tag {
  border: 2px solid #101010;
  font-weight: 700;
}
</style>