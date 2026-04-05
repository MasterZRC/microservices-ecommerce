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
  /* Neo-Brutalism Design System */
  --neo-bg: #f7f3e9;
  --neo-surface: #fffef9;
  --neo-text: #121212;
  --neo-text-soft: #374151;
  --neo-primary: #2563eb;
  --neo-success: #10b981;
  --neo-danger: #ef4444;
  --neo-warning: #f59e0b;
  --neo-border: #101010;
  --neo-shadow: 6px 6px 0 #101010;
  --neo-shadow-sm: 4px 4px 0 #101010;
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

  /* Admin specific */
  --admin-sidebar-bg: #fffef9;
  --admin-sidebar-width: 260px;
  --admin-header-height: 64px;
}

* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Text', 'PingFang SC', 'Microsoft YaHei', 'Segoe UI', Roboto, sans-serif;
  color: var(--neo-text);
  background: var(--neo-bg);
  background-image: repeating-linear-gradient(
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

/* Neo-Brutalism Typography */
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

/* Neo-Brutalism Card */
.neo-panel {
  border: 3px solid var(--neo-border);
  border-radius: var(--neo-radius);
  background: var(--neo-surface);
  box-shadow: var(--neo-shadow);
  padding: var(--neo-space-4);
}

/* Override Element Plus - Card */
.el-card {
  border-radius: var(--neo-radius) !important;
  border: 3px solid var(--neo-border) !important;
  background: var(--neo-surface) !important;
  box-shadow: var(--neo-shadow) !important;
}

/* Neo-Brutalism Buttons */
.el-button {
  border-radius: 12px !important;
  border: 3px solid var(--neo-border) !important;
  box-shadow: 3px 3px 0 var(--neo-border) !important;
  font-weight: 700 !important;
  transition: all 0.15s ease;
}

.el-button:active {
  transform: translate(2px, 2px);
  box-shadow: 1px 1px 0 var(--neo-border) !important;
}

.el-button--primary {
  background: var(--neo-primary) !important;
  color: #fff !important;
}

.el-button--success {
  background: var(--neo-success) !important;
  color: #fff !important;
}

.el-button--danger {
  background: var(--neo-danger) !important;
  color: #fff !important;
}

.el-button--warning {
  background: var(--neo-warning) !important;
  color: #fff !important;
}

/* Neo-Brutalism Form Inputs */
.el-input__wrapper,
.el-select__wrapper {
  border-radius: 12px !important;
  box-shadow: none !important;
  border: 3px solid var(--neo-border) !important;
  background: #fff !important;
}

.el-input__wrapper:hover,
.el-select__wrapper:hover {
  border-color: var(--neo-primary) !important;
}

.el-input__wrapper.is-focus,
.el-select__wrapper.is-focus {
  border-color: var(--neo-primary) !important;
  box-shadow: none !important;
}

/* Neo-Brutalism Table */
.el-table {
  border: 3px solid var(--neo-border);
  border-radius: 14px;
  overflow: hidden;
}

.el-table th.el-table__cell {
  background: #ffe08a !important;
  color: var(--neo-text);
  font-weight: 800;
  font-size: var(--neo-fs-sm);
}

.el-table tr {
  background: var(--neo-surface);
}

.el-table__body tr:hover > td {
  background: #f0f9ff !important;
}

/* Neo-Brutalism Pagination */
.el-pagination {
  --el-pagination-bg-color: var(--neo-surface);
  --el-pagination-text-color: #111827;
}

.el-pagination .btn-next,
.el-pagination .btn-prev,
.el-pagination .number {
  border: 2px solid var(--neo-border);
  border-radius: 8px;
  margin: 0 3px;
  min-width: 30px;
}

.el-pagination .el-pager li.is-active {
  background: var(--neo-primary) !important;
  color: #fff !important;
  border-color: var(--neo-border) !important;
}

/* Neo-Brutalism Tag */
.el-tag {
  border: 2px solid var(--neo-border);
  font-weight: 700;
  border-radius: 8px !important;
}

.el-tag--success {
  background: #d1fae5 !important;
  border-color: var(--neo-border) !important;
  color: #065f46 !important;
}

.el-tag--warning {
  background: #fef3c7 !important;
  border-color: var(--neo-border) !important;
  color: #92400e !important;
}

.el-tag--danger {
  background: #fee2e2 !important;
  border-color: var(--neo-border) !important;
  color: #991b1b !important;
}

.el-tag--info {
  background: #f3f4f6 !important;
  border-color: var(--neo-border) !important;
  color: #374151 !important;
}

/* Neo-Brutalism Dialog */
.el-dialog {
  border-radius: var(--neo-radius) !important;
  border: 3px solid var(--neo-border) !important;
  box-shadow: 8px 8px 0 var(--neo-border) !important;
}

.el-dialog__header {
  border-bottom: 2px solid var(--neo-border);
  padding: var(--neo-space-3) var(--neo-space-4) !important;
}

.el-dialog__title {
  font-weight: 800;
  font-size: var(--neo-fs-lg);
}

.el-dialog__body {
  padding: var(--neo-space-4) !important;
}

.el-dialog__footer {
  border-top: 2px solid var(--neo-border);
  padding: var(--neo-space-3) var(--neo-space-4) !important;
}

/* Neo-Brutalism Form */
.el-form-item__label {
  font-weight: 800 !important;
  color: var(--neo-text) !important;
  font-size: var(--neo-fs-sm);
}

/* Neo-Brutalism Dropdown */
.el-dropdown-menu {
  border: 3px solid var(--neo-border) !important;
  border-radius: 12px !important;
  box-shadow: 4px 4px 0 var(--neo-border) !important;
  padding: var(--neo-space-2) !important;
}

.el-dropdown-menu__item {
  border-radius: 8px !important;
  font-weight: 600 !important;
}

.el-dropdown-menu__item:hover {
  background: #f0f9ff !important;
  color: var(--neo-primary) !important;
}

/* Neo-Brutalism Select */
.el-select-dropdown {
  border: 3px solid var(--neo-border) !important;
  border-radius: 12px !important;
  box-shadow: 4px 4px 0 var(--neo-border) !important;
}

.el-select-dropdown__item {
  font-weight: 600 !important;
}

.el-select-dropdown__item.is-hovering {
  background: #f0f9ff !important;
}

.el-select-dropdown__item.is-selected {
  background: #dbeafe !important;
  color: var(--neo-primary) !important;
}

/* Neo-Brutalism DatePicker */
.el-date-editor {
  border-radius: 12px !important;
}

/* Custom Scrollbar */
::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

::-webkit-scrollbar-thumb {
  background: #d1d5db;
  border-radius: 4px;
  border: 2px solid transparent;
  background-clip: padding-box;
}

::-webkit-scrollbar-track {
  background: transparent;
}

/* Page Container */
.page-container {
  max-width: 1400px;
  margin: 0 auto;
  padding: var(--neo-space-4);
}
</style>
