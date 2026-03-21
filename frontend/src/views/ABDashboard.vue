<template>
  <div class="ab-dashboard">
    <div class="container">

      <!-- 页面标题 -->
      <div class="page-header">
        <div>
          <h1 class="neo-h1">推荐算法 A/B 实验仪表盘</h1>
          <p class="page-subtitle">实时监控 DeepFM vs ItemCF 在线效果，评估新算法收益</p>
        </div>
        <div class="date-picker">
          <el-date-picker
            v-model="selectedDate"
            type="date"
            placeholder="选择日期"
            format="YYYY-MM-DD"
            value-format="YYYY-MM-DD"
            @change="loadData"
          />
        </div>
      </div>

      <!-- 加载状态 -->
      <div v-if="loading" class="loading-area">
        <el-icon class="loading-spinner" :size="32"><Loading /></el-icon>
        <p>加载中...</p>
      </div>

      <template v-else>

        <!-- 灰度配置信息 -->
        <div class="config-card">
          <div class="config-item">
            <span class="config-label">灰度开关</span>
            <el-tag :type="grayStatus?.enabled ? 'success' : 'info'" size="large">
              {{ grayStatus?.enabled ? '已启用' : '未启用' }}
            </el-tag>
          </div>
          <div class="config-item">
            <span class="config-label">灰度流量比例</span>
            <el-tag type="warning" size="large">{{ grayStatus?.ratio || 10 }}%</el-tag>
          </div>
          <div class="config-item">
            <span class="config-label">灰度组算法</span>
            <el-tag type="danger" size="large">DeepFM（CTR 预估）</el-tag>
          </div>
          <div class="config-item">
            <span class="config-label">对照组算法</span>
            <el-tag type="info" size="large">ItemCF（协同过滤）</el-tag>
          </div>
        </div>

        <!-- 未启用提示 -->
        <div v-if="!grayStatus?.enabled" class="notice-card">
          <div class="notice-icon">⚠️</div>
          <div>
            <h3>灰度发布未启用</h3>
            <p>请在 <code>application.yml</code> 中设置 <code>recommendation.gray.enabled=true</code> 启用灰度发布后，才能查看实时 A/B 数据。</p>
          </div>
        </div>

        <template v-else>

          <!-- 核心指标对比 -->
          <div class="section-title">
            <h2>📊 核心指标对比</h2>
            <p>灰度组（DeepFM） vs 对照组（ItemCF），日期：{{ selectedDate || '今日' }}</p>
          </div>

          <!-- 四宫格指标卡片 -->
          <div class="metrics-grid">
            <!-- CTR -->
            <div class="metric-card">
              <div class="metric-header">
                <span class="metric-title">点击率 CTR</span>
                <span class="metric-desc">click / exposure</span>
              </div>
              <div class="metric-comparison">
                <div class="metric-group deepfm">
                  <span class="group-label">DeepFM</span>
                  <span class="group-value">{{ formatPct(metricsData.gray?.ctr) }}</span>
                </div>
                <div class="metric-divider">vs</div>
                <div class="metric-group control">
                  <span class="group-label">ItemCF</span>
                  <span class="group-value">{{ formatPct(metricsData.control?.ctr) }}</span>
                </div>
              </div>
              <div class="improvement" :class="improvementClass(comparisonData?.ctr?.improvement)">
                <span v-if="comparisonData?.ctr?.improvement !== undefined">
                  {{ improvementText(comparisonData.ctr.improvement) }}
                </span>
                <span v-else>—</span>
              </div>
            </div>

            <!-- 购物车率 -->
            <div class="metric-card">
              <div class="metric-header">
                <span class="metric-title">加购率</span>
                <span class="metric-desc">cart / exposure</span>
              </div>
              <div class="metric-comparison">
                <div class="metric-group deepfm">
                  <span class="group-label">DeepFM</span>
                  <span class="group-value">{{ formatPct(metricsData.gray?.cartRate) }}</span>
                </div>
                <div class="metric-divider">vs</div>
                <div class="metric-group control">
                  <span class="group-label">ItemCF</span>
                  <span class="group-value">{{ formatPct(metricsData.control?.cartRate) }}</span>
                </div>
              </div>
              <div class="improvement" :class="improvementClass(comparisonData?.cartRate?.improvement)">
                <span v-if="comparisonData?.cartRate?.improvement !== undefined">
                  {{ improvementText(comparisonData.cartRate.improvement) }}
                </span>
                <span v-else>—</span>
              </div>
            </div>

            <!-- 下单率 -->
            <div class="metric-card">
              <div class="metric-header">
                <span class="metric-title">下单率</span>
                <span class="metric-desc">order / exposure</span>
              </div>
              <div class="metric-comparison">
                <div class="metric-group deepfm">
                  <span class="group-label">DeepFM</span>
                  <span class="group-value">{{ formatPct(metricsData.gray?.orderRate) }}</span>
                </div>
                <div class="metric-divider">vs</div>
                <div class="metric-group control">
                  <span class="group-label">ItemCF</span>
                  <span class="group-value">{{ formatPct(metricsData.control?.orderRate) }}</span>
                </div>
              </div>
              <div class="improvement" :class="improvementClass(comparisonData?.orderRate?.improvement)">
                <span v-if="comparisonData?.orderRate?.improvement !== undefined">
                  {{ improvementText(comparisonData.orderRate.improvement) }}
                </span>
                <span v-else>—</span>
              </div>
            </div>

            <!-- UV 曝光数 -->
            <div class="metric-card">
              <div class="metric-header">
                <span class="metric-title">独立曝光人数</span>
                <span class="metric-desc">UV（去重）</span>
              </div>
              <div class="metric-comparison">
                <div class="metric-group deepfm">
                  <span class="group-label">DeepFM</span>
                  <span class="group-value">{{ formatNum(metricsData.gray?.exposure) }}</span>
                </div>
                <div class="metric-divider">vs</div>
                <div class="metric-group control">
                  <span class="group-label">ItemCF</span>
                  <span class="group-value">{{ formatNum(metricsData.control?.exposure) }}</span>
                </div>
              </div>
              <div class="improvement neutral">
                <span>流量分配比例固定</span>
              </div>
            </div>
          </div>

          <!-- 详细数据表格 -->
          <div class="section-title">
            <h2>📋 详细数据</h2>
          </div>

          <div class="table-card">
            <el-table :data="tableData" stripe border size="large">
              <el-table-column prop="metric" label="指标" width="160" />
              <el-table-column label="DeepFM（灰度组）" align="center">
                <el-table-column prop="gray_value" label="数值" align="center" />
                <el-table-column prop="gray_raw" label="原始值" align="center" width="120" />
              </el-table-column>
              <el-table-column label="ItemCF（对照组）" align="center">
                <el-table-column prop="control_value" label="数值" align="center" />
                <el-table-column prop="control_raw" label="原始值" align="center" width="120" />
              </el-table-column>
              <el-table-column label="DeepFM 提升" align="center" width="140">
                <template #default="{ row }">
                  <span v-if="row.improvement !== null" :class="improvementClass(row.improvement)">
                    {{ row.improvement > 0 ? '+' : '' }}{{ row.improvement?.toFixed(2) }}%
                  </span>
                  <span v-else>—</span>
                </template>
              </el-table-column>
              <el-table-column label="结论" align="center">
                <template #default="{ row }">
                  <el-tag v-if="row.improvement > 5" type="success" size="small">✅ 显著提升</el-tag>
                  <el-tag v-else-if="row.improvement > 0" type="info" size="small">略有提升</el-tag>
                  <el-tag v-else-if="row.improvement < 0" type="warning" size="small">⚠️ 需观察</el-tag>
                  <el-tag v-else type="info" size="small">持平</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <!-- 统计显著性说明 -->
          <div class="section-title" style="margin-top: 32px;">
            <h2>🔬 统计显著性解读</h2>
          </div>

          <div class="significance-card">
            <div class="sig-item">
              <div class="sig-icon">📈</div>
              <div>
                <h4>CTR（点击率）提升说明</h4>
                <p>CTR 提升 = (灰度CTR - 对照CTR) / 对照CTR × 100%。提升 5% 以上通常被认为有实际收益。DeepFM 通过学习用户-商品交叉特征，能更好地预测点击意愿。</p>
              </div>
            </div>
            <div class="sig-item">
              <div class="sig-icon">🛒</div>
              <div>
                <h4>加购率 / 下单率</h4>
                <p>加购率和下单率是最终业务指标，比 CTR 更重要。如果 CTR 提升但加购率下降，可能是推荐商品吸引点击但不够吸引购买，需要分析原因。</p>
              </div>
            </div>
            <div class="sig-item">
              <div class="sig-icon">⚖️</div>
              <div>
                <h4>流量分配</h4>
                <p>灰度组和对照组各占 10% 和 90% 的流量（可配置）。实验流量越大，统计结果越可靠。建议至少收集 1000 个独立用户的行为后再做结论判断。</p>
              </div>
            </div>
            <div class="sig-item">
              <div class="sig-icon">🚀</div>
              <div>
                <h4>下一步决策</h4>
                <p>如果灰度组在 CTR/加购率/下单率上持续优于对照组（建议观察 7 天），可以考虑扩大灰度比例或全量上线 DeepFM。扩大前记得在生产环境做小流量验证。</p>
              </div>
            </div>
          </div>

        </template>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const selectedDate = ref('')
const loading = ref(false)
const grayStatus = ref(null)
const metricsData = ref({ gray: {}, control: {} })
const comparisonData = ref({})

// 初始化当日日期
const today = new Date().toISOString().slice(0, 10)
selectedDate.value = today

async function loadData() {
  loading.value = true
  try {
    // 并行加载三个接口
    const [statusRes, compareRes] = await Promise.all([
      api.getGrayStatus().catch(() => null),
      api.getGrayCompare(selectedDate.value || undefined).catch(() => null)
    ])

    grayStatus.value = statusRes?.data || {}

    if (compareRes?.data) {
      const data = compareRes.data
      comparisonData.value = data.comparison || {}
      metricsData.value = {
        gray: data.gray || {},
        control: data.control || {}
      }
    }
  } catch (error) {
    console.error('加载 A/B 数据失败:', error)
    ElMessage.error('加载实验数据失败，请检查服务是否正常运行')
  } finally {
    loading.value = false
  }
}

const tableData = computed(() => {
  const c = comparisonData.value
  const g = metricsData.value.gray || {}
  const ctrl = metricsData.value.control || {}

  return [
    {
      metric: '曝光人数 (UV)',
      gray_value: formatNum(g.exposure),
      gray_raw: g.exposure ?? '—',
      control_value: formatNum(ctrl.exposure),
      control_raw: ctrl.exposure ?? '—',
      improvement: null,
    },
    {
      metric: '点击数',
      gray_value: formatNum(g.click),
      gray_raw: g.click ?? '—',
      control_value: formatNum(ctrl.click),
      control_raw: ctrl.click ?? '—',
      improvement: null,
    },
    {
      metric: '加购数',
      gray_value: formatNum(g.cart),
      gray_raw: g.cart ?? '—',
      control_value: formatNum(ctrl.cart),
      control_raw: ctrl.cart ?? '—',
      improvement: null,
    },
    {
      metric: '下单数',
      gray_value: formatNum(g.order),
      gray_raw: g.order ?? '—',
      control_value: formatNum(ctrl.order),
      control_raw: ctrl.order ?? '—',
      improvement: null,
    },
    {
      metric: '点击率 CTR',
      gray_value: formatPct(g.ctr),
      gray_raw: g.ctr ?? '—',
      control_value: formatPct(ctrl.ctr),
      control_raw: ctrl.ctr ?? '—',
      improvement: c.ctr?.improvement ?? null,
    },
    {
      metric: '加购率',
      gray_value: formatPct(g.cartRate),
      gray_raw: g.cartRate ?? '—',
      control_value: formatPct(ctrl.cartRate),
      control_raw: ctrl.cartRate ?? '—',
      improvement: c.cartRate?.improvement ?? null,
    },
    {
      metric: '下单率',
      gray_value: formatPct(g.orderRate),
      gray_raw: g.orderRate ?? '—',
      control_value: formatPct(ctrl.orderRate),
      control_raw: ctrl.orderRate ?? '—',
      improvement: c.orderRate?.improvement ?? null,
    },
  ]
})

function formatPct(val) {
  if (val === undefined || val === null || val === '') return '—'
  return (Number(val) * 100).toFixed(2) + '%'
}

function formatNum(val) {
  if (val === undefined || val === null || val === '') return '—'
  return Number(val).toLocaleString()
}

function improvementClass(val) {
  if (val === undefined || val === null) return ''
  if (val > 5) return 'positive-strong'
  if (val > 0) return 'positive'
  if (val < -5) return 'negative-strong'
  if (val < 0) return 'negative'
  return 'neutral'
}

function improvementText(val) {
  if (val === undefined || val === null) return '—'
  const sign = val > 0 ? '+' : ''
  return `${sign}${val.toFixed(2)}%`
}

onMounted(loadData)
</script>

<style scoped>
.ab-dashboard {
  padding: 0 0 40px;
}

.container {
  max-width: 1240px;
  margin: 0 auto;
  padding: 0 24px;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 28px;
  padding-bottom: 20px;
  border-bottom: 3px solid #101010;
}

.page-subtitle {
  color: #64748b;
  font-size: 14px;
  margin-top: 4px;
}

/* 配置卡片 */
.config-card {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 24px;
}

.config-item {
  background: #fffef7;
  border: 2px solid #101010;
  border-radius: 12px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.config-label {
  font-size: 12px;
  color: #64748b;
  font-weight: 600;
}

/* 未启用提示 */
.notice-card {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  background: #fff3cd;
  border: 2px solid #101010;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 24px;
}

.notice-icon {
  font-size: 28px;
  flex-shrink: 0;
}

.notice-card h3 {
  margin: 0 0 6px;
  font-size: 16px;
}

.notice-card p {
  margin: 0;
  color: #6b4226;
  font-size: 14px;
}

.notice-card code {
  background: rgba(0,0,0,0.08);
  padding: 1px 6px;
  border-radius: 4px;
  font-family: monospace;
}

/* 加载状态 */
.loading-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 60px;
  color: #64748b;
}

.loading-spinner {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 标题 */
.section-title {
  margin-bottom: 14px;
}

.section-title h2 {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 900;
  letter-spacing: -0.3px;
}

.section-title p {
  margin: 0;
  color: #64748b;
  font-size: 13px;
}

/* 指标卡片 */
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 28px;
}

.metric-card {
  background: #fff;
  border: 3px solid #101010;
  border-radius: 14px;
  padding: 18px;
  box-shadow: 4px 4px 0 #101010;
}

.metric-header {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 14px;
  padding-bottom: 10px;
  border-bottom: 2px dashed #e5e7eb;
}

.metric-title {
  font-size: 14px;
  font-weight: 700;
  color: #1f2937;
}

.metric-desc {
  font-size: 11px;
  color: #9ca3af;
}

.metric-comparison {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.metric-group {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex: 1;
}

.metric-group.deepfm .group-value {
  color: #dc2626;
  font-size: 18px;
  font-weight: 900;
}

.metric-group.control .group-value {
  color: #64748b;
  font-size: 18px;
  font-weight: 900;
}

.group-label {
  font-size: 11px;
  color: #9ca3af;
  font-weight: 600;
  margin-bottom: 2px;
}

.metric-divider {
  font-size: 11px;
  color: #d1d5db;
  font-weight: 700;
}

.improvement {
  text-align: center;
  font-size: 13px;
  font-weight: 700;
  padding: 4px 10px;
  border-radius: 6px;
}

.improvement.positive-strong {
  background: #dcfce7;
  color: #15803d;
  border: 2px solid #15803d;
}

.improvement.positive {
  background: #f0fdf4;
  color: #166534;
  border: 2px solid #86efac;
}

.improvement.negative-strong {
  background: #fee2e2;
  color: #dc2626;
  border: 2px solid #dc2626;
}

.improvement.negative {
  background: #fef2f2;
  color: #991b1b;
  border: 2px solid #fca5a5;
}

.improvement.neutral {
  background: #f3f4f6;
  color: #6b7280;
  border: 2px solid #d1d5db;
}

/* 表格 */
.table-card {
  margin-bottom: 24px;
}

/* 显著性说明 */
.significance-card {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.sig-item {
  display: flex;
  gap: 12px;
  background: #f8fafc;
  border: 2px solid #e5e7eb;
  border-radius: 10px;
  padding: 16px;
}

.sig-icon {
  font-size: 24px;
  flex-shrink: 0;
}

.sig-item h4 {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 700;
}

.sig-item p {
  margin: 0;
  font-size: 13px;
  color: #4b5563;
  line-height: 1.6;
}

@media (max-width: 1100px) {
  .config-card,
  .metrics-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .significance-card {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .config-card,
  .metrics-grid {
    grid-template-columns: 1fr;
  }
}
</style>
