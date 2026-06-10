<template>
  <div class="seckill-demo">
    <div class="page-head">
      <h1 class="neo-h2">秒杀压测</h1>
      <p class="neo-caption">Redis Lua 原子扣减 / Redis Stream 异步下单 / 库存一致性</p>
    </div>

    <div class="control-panel neo-panel">
      <el-form :model="form" label-width="96px" class="control-form">
        <el-form-item label="秒杀商品">
          <el-select
            v-model="form.seckillProductId"
            placeholder="选择商品"
            filterable
            :loading="productsLoading"
            @change="onProductChange"
          >
            <el-option
              v-for="product in products"
              :key="product.id"
              :label="productLabel(product)"
              :value="product.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="总请求数">
          <el-input-number v-model="form.totalRequests" :min="1" :max="20000" :step="100" />
        </el-form-item>

        <el-form-item label="并发数">
          <el-input-number v-model="form.concurrency" :min="1" :max="500" :step="10" />
        </el-form-item>

        <el-form-item label="库存">
          <el-input-number v-model="form.stock" :min="0" :max="100000" :step="50" />
        </el-form-item>

        <el-form-item label="用户起点">
          <el-input-number v-model="form.userIdBase" :min="1" :step="1000" />
        </el-form-item>
      </el-form>

      <div class="actions">
        <el-button type="primary" :disabled="!canStart" :loading="starting" @click="startJob">
          <el-icon><VideoPlay /></el-icon>
          启动压测
        </el-button>
        <el-button type="warning" :disabled="!form.seckillProductId || isRunning" :loading="resetting" @click="resetDemo">
          <el-icon><Refresh /></el-icon>
          回滚库存
        </el-button>
        <el-button type="danger" :disabled="!isRunning" :loading="canceling" @click="cancelJob">
          <el-icon><Close /></el-icon>
          取消任务
        </el-button>
      </div>
    </div>

    <div v-if="currentJob" class="run-head">
      <div>
        <h2 class="neo-h3">{{ currentJob.productName || '秒杀任务' }}</h2>
        <p class="neo-caption">Job {{ currentJob.jobId }}</p>
      </div>
      <el-tag :type="statusType(currentJob.status)" size="large">{{ statusText(currentJob.status) }}</el-tag>
    </div>

    <el-progress
      v-if="currentJob"
      :percentage="progressPercent"
      :stroke-width="18"
      striped
      striped-flow
      class="progress"
    />

    <div v-if="currentJob" class="metrics-grid">
      <div v-for="item in metricCards" :key="item.label" class="metric-card" :style="{ '--metric-color': item.color }">
        <div class="metric-label">{{ item.label }}</div>
        <div class="metric-value">{{ item.value }}</div>
      </div>
    </div>

    <div v-if="currentJob" class="checks-row">
      <div v-for="check in checks" :key="check.label" class="check-pill" :class="{ ok: check.ok }">
        <el-icon><component :is="check.ok ? CircleCheck : WarningFilled" /></el-icon>
        <span>{{ check.label }}</span>
      </div>
    </div>

    <div v-if="currentJob" class="charts-row">
      <div class="chart-panel neo-panel">
        <div class="panel-title">吞吐趋势</div>
        <div ref="throughputChartRef" class="chart"></div>
      </div>
      <div class="chart-panel neo-panel">
        <div class="panel-title">延迟趋势</div>
        <div ref="latencyChartRef" class="chart"></div>
      </div>
    </div>

    <div v-if="currentJob" class="details-row">
      <div class="neo-panel detail-panel">
        <div class="panel-title">失败原因</div>
        <el-table :data="failReasonRows" empty-text="暂无失败">
          <el-table-column prop="reason" label="原因" />
          <el-table-column prop="count" label="次数" width="120" />
        </el-table>
      </div>
      <div class="neo-panel detail-panel">
        <div class="panel-title">库存与队列</div>
        <div class="kv-list">
          <div><span>压测前库存</span><strong>{{ valueOrDash(currentJob.stockBefore) }}</strong></div>
          <div><span>压测后库存</span><strong>{{ valueOrDash(currentJob.stockAfter) }}</strong></div>
          <div><span>消耗库存</span><strong>{{ currentJob.consumedStock ?? 0 }}</strong></div>
          <div><span>DLQ 增量</span><strong>{{ dlqDelta(currentJob) }}</strong></div>
          <div><span>完成标记增量</span><strong>{{ currentJob.doneMarkersDelta ?? 0 }}</strong></div>
          <div><span>重试消息</span><strong>{{ currentJob.retryingMessagesAfter ?? 0 }}</strong></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CircleCheck, Close, Refresh, VideoPlay, WarningFilled } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import api from '../../api'

const productsLoading = ref(false)
const starting = ref(false)
const resetting = ref(false)
const canceling = ref(false)
const products = ref([])
const currentJob = ref(null)
const throughputChartRef = ref(null)
const latencyChartRef = ref(null)
let throughputChart = null
let latencyChart = null
let pollTimer = null

const form = reactive({
  seckillProductId: null,
  totalRequests: 1000,
  concurrency: 100,
  stock: 200,
  userIdBase: 900000000
})

const terminalStatuses = new Set(['SUCCEEDED', 'CANCELED', 'FAILED'])
const isRunning = computed(() => currentJob.value && !terminalStatuses.has(currentJob.value.status))
const canStart = computed(() => Boolean(form.seckillProductId) && !isRunning.value)

const progressPercent = computed(() => {
  const job = currentJob.value
  if (!job || !job.totalRequests) return 0
  return Math.min(100, Number(((job.completed / job.totalRequests) * 100).toFixed(1)))
})

const metricCards = computed(() => {
  const job = currentJob.value || {}
  return [
    { label: '总请求', value: `${job.completed || 0}/${job.totalRequests || 0}`, color: '#2563eb' },
    { label: '成功', value: job.success || 0, color: '#059669' },
    { label: '失败', value: job.fail || 0, color: '#dc2626' },
    { label: '请求 RPS', value: formatNumber(job.requestRps), color: '#7c3aed' },
    { label: '成功 QPS', value: formatNumber(job.successQps), color: '#0891b2' },
    { label: 'P99 延迟', value: `${formatNumber(job.p99Ms)} ms`, color: '#ea580c' },
    { label: '超卖数量', value: job.oversold || 0, color: (job.oversold || 0) > 0 ? '#dc2626' : '#059669' },
    { label: 'DLQ 增量', value: dlqDelta(job), color: dlqDelta(job) > 0 ? '#dc2626' : '#059669' }
  ]
})

const checks = computed(() => {
  const job = currentJob.value || {}
  return [
    { label: '无超卖', ok: Boolean(job.noOversell) },
    { label: '库存匹配', ok: Boolean(job.stockMatch) },
    { label: '无新增死信', ok: Boolean(job.noNewDlq) }
  ]
})

const failReasonRows = computed(() => {
  const reasons = currentJob.value?.failReasons || {}
  return Object.entries(reasons).map(([reason, count]) => ({ reason, count }))
})

function productLabel(product) {
  return `#${product.id} ${product.productName || '秒杀商品'} / 库存 ${product.availableStock ?? '-'}`
}

function dlqDelta(job) {
  return Number(job?.dlqDelta ?? job?.deadLetterDelta ?? 0)
}

function onProductChange(id) {
  const product = products.value.find(item => item.id === id)
  if (product) {
    form.stock = Number(product.availableStock ?? product.totalStock ?? form.stock)
  }
}

async function loadProducts() {
  productsLoading.value = true
  try {
    const res = await api.get('/seckill/demo/products')
    products.value = res.data || []
    if (!form.seckillProductId && products.value.length) {
      form.seckillProductId = products.value[0].id
      onProductChange(form.seckillProductId)
    }
  } catch (error) {
    ElMessage.error(error.message || '加载秒杀商品失败')
  } finally {
    productsLoading.value = false
  }
}

function payload() {
  return {
    seckillProductId: form.seckillProductId,
    totalRequests: form.totalRequests,
    concurrency: form.concurrency,
    stock: form.stock,
    quantity: 1,
    userIdBase: form.userIdBase
  }
}

async function resetDemo() {
  resetting.value = true
  try {
    const res = await api.post('/seckill/demo/reset', payload())
    ElMessage.success(`已回滚库存到 ${res.data.stock}`)
    await loadProducts()
  } catch (error) {
    ElMessage.error(error.message || '回滚失败')
  } finally {
    resetting.value = false
  }
}

async function startJob() {
  starting.value = true
  try {
    const res = await api.post('/seckill/demo/jobs', payload())
    currentJob.value = res.data
    ElMessage.success('压测任务已启动')
    startPolling()
    await nextTick()
    renderCharts()
  } catch (error) {
    ElMessage.error(error.message || '启动压测失败')
  } finally {
    starting.value = false
  }
}

async function pollJob() {
  if (!currentJob.value?.jobId) return
  try {
    const res = await api.get(`/seckill/demo/jobs/${currentJob.value.jobId}`)
    currentJob.value = res.data
    await nextTick()
    renderCharts()
    if (terminalStatuses.has(currentJob.value.status)) {
      stopPolling()
      await loadProducts()
    }
  } catch (error) {
    stopPolling()
    ElMessage.error(error.message || '查询压测状态失败')
  }
}

async function cancelJob() {
  if (!currentJob.value?.jobId) return
  canceling.value = true
  try {
    const res = await api.post(`/seckill/demo/jobs/${currentJob.value.jobId}/cancel`)
    currentJob.value = res.data
    ElMessage.warning('已请求取消任务')
  } catch (error) {
    ElMessage.error(error.message || '取消失败')
  } finally {
    canceling.value = false
  }
}

function startPolling() {
  stopPolling()
  pollTimer = window.setInterval(pollJob, 900)
}

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

function statusText(status) {
  const map = {
    PENDING: '等待中',
    PREPARING: '准备中',
    RUNNING: '压测中',
    FINISHING: '收尾中',
    CANCELING: '取消中',
    CANCELED: '已取消',
    SUCCEEDED: '已完成',
    FAILED: '失败'
  }
  return map[status] || status || '-'
}

function statusType(status) {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'CANCELED') return 'warning'
  return 'primary'
}

function valueOrDash(value) {
  return value === null || value === undefined ? '-' : value
}

function formatNumber(value) {
  const number = Number(value || 0)
  return number.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

function renderCharts() {
  if (!currentJob.value) return
  const timeline = currentJob.value.timeline || []
  const labels = timeline.map(point => `${(point.elapsedMs / 1000).toFixed(1)}s`)

  if (throughputChartRef.value && !throughputChart) {
    throughputChart = echarts.init(throughputChartRef.value)
  }
  if (latencyChartRef.value && !latencyChart) {
    latencyChart = echarts.init(latencyChartRef.value)
  }

  throughputChart?.setOption({
    grid: { left: 40, right: 16, top: 24, bottom: 28 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: labels },
    yAxis: { type: 'value' },
    series: [
      { name: '请求 RPS', type: 'line', smooth: true, data: timeline.map(point => point.requestRps), color: '#2563eb' },
      { name: '成功 QPS', type: 'line', smooth: true, data: timeline.map(point => point.successQps), color: '#059669' }
    ]
  })

  latencyChart?.setOption({
    grid: { left: 40, right: 16, top: 24, bottom: 28 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: labels },
    yAxis: { type: 'value' },
    series: [
      { name: 'P95', type: 'line', smooth: true, data: timeline.map(point => point.p95Ms), color: '#ea580c' },
      { name: 'P99', type: 'line', smooth: true, data: timeline.map(point => point.p99Ms), color: '#dc2626' }
    ]
  })
}

function resizeCharts() {
  throughputChart?.resize()
  latencyChart?.resize()
}

watch(currentJob, () => nextTick(renderCharts), { deep: true })

onMounted(() => {
  loadProducts()
  window.addEventListener('resize', resizeCharts)
})

onBeforeUnmount(() => {
  stopPolling()
  window.removeEventListener('resize', resizeCharts)
  throughputChart?.dispose()
  latencyChart?.dispose()
})
</script>

<style scoped>
.seckill-demo {
  max-width: 1400px;
}

.page-head {
  margin-bottom: 20px;
}

.page-head .neo-h2 {
  margin-bottom: 4px;
}

.control-panel {
  margin-bottom: 22px;
}

.control-form {
  display: grid;
  grid-template-columns: minmax(280px, 1.8fr) repeat(4, minmax(160px, 1fr));
  gap: 12px;
  align-items: start;
}

.control-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.control-form :deep(.el-select),
.control-form :deep(.el-input-number) {
  width: 100%;
}

.actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 18px;
}

.run-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 14px;
}

.progress {
  margin-bottom: 18px;
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr));
  gap: 16px;
  margin-bottom: 18px;
}

.metric-card {
  background: var(--neo-surface);
  border: 3px solid var(--neo-border);
  border-radius: 12px;
  box-shadow: var(--neo-shadow-sm);
  padding: 16px;
  min-height: 104px;
}

.metric-label {
  color: var(--neo-text-soft);
  font-size: 13px;
  font-weight: 700;
}

.metric-value {
  color: var(--metric-color);
  font-size: 28px;
  font-weight: 900;
  line-height: 1.25;
  margin-top: 8px;
  word-break: break-word;
}

.checks-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.check-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: 3px solid var(--neo-border);
  border-radius: 12px;
  padding: 8px 12px;
  font-weight: 800;
  background: #fee2e2;
  color: #991b1b;
  box-shadow: 3px 3px 0 var(--neo-border);
}

.check-pill.ok {
  background: #d1fae5;
  color: #065f46;
}

.charts-row,
.details-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
  margin-bottom: 18px;
}

.chart-panel,
.detail-panel {
  min-width: 0;
}

.panel-title {
  font-size: 16px;
  font-weight: 900;
  margin-bottom: 14px;
}

.chart {
  height: 280px;
  width: 100%;
}

.kv-list {
  display: grid;
  gap: 10px;
}

.kv-list div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 2px solid #e5e7eb;
  padding-bottom: 8px;
  gap: 12px;
}

.kv-list span {
  color: var(--neo-text-soft);
  font-weight: 700;
}

.kv-list strong {
  color: var(--neo-text);
  font-size: 18px;
}

@media (max-width: 1100px) {
  .control-form,
  .charts-row,
  .details-row {
    grid-template-columns: 1fr;
  }

  .metrics-grid {
    grid-template-columns: repeat(2, minmax(140px, 1fr));
  }
}

@media (max-width: 640px) {
  .metrics-grid {
    grid-template-columns: 1fr;
  }

  .run-head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
