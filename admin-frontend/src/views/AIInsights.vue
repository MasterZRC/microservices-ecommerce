<template>
  <div class="ai-page">
    <div class="ai-layout">
      <!-- 左侧：聊天主体 -->
      <main class="ai-chat">
        <header class="ai-chat-header">
          <span class="ai-chat-title">📊 AI 经营分析助手</span>
          <span class="ai-chat-status">{{ status }}</span>
          <button class="ai-mini-btn" @click="reset" title="清空对话">↻ 重置</button>
        </header>

        <div ref="scrollEl" class="ai-msgs">
          <div v-for="(m, i) in messages" :key="i" :class="['ai-msg', `ai-msg-${m.role}`]">
            <!-- 工具调用过程（折叠）-->
            <div v-if="m.role === 'assistant' && m.steps && m.steps.length" class="ai-tools">
              <details>
                <summary>查询了 {{ m.steps.length }} 步真实数据</summary>
                <div v-for="(s, si) in m.steps" :key="si" class="ai-tool-step">
                  <div class="ai-tool-name">⚙ {{ s.name }}<span v-if="s.ms != null" class="ai-tool-ms">({{ s.ms }}ms)</span></div>
                  <pre class="ai-tool-args">{{ pretty(s.args) }}</pre>
                  <pre class="ai-tool-result">{{ pretty(s.result) }}</pre>
                </div>
              </details>
            </div>

            <!-- 文本内容（含 markdown）-->
            <div v-if="m.content" class="ai-bubble" v-html="renderMarkdown(m.content)"></div>

            <!-- 图表 -->
            <div v-for="(ch, ci) in (m.charts || [])" :key="`c-${ci}`" class="ai-chart">
              <div class="ai-chart-title">{{ ch.title }}</div>
              <div :ref="el => bindChartEl(el, ch)" class="ai-chart-body"></div>
            </div>
          </div>

          <div v-if="loading" class="ai-typing"><span></span><span></span><span></span></div>
        </div>

        <footer class="ai-input-row">
          <textarea
            v-model="draft"
            rows="2"
            placeholder="问我：最近 7 天哪个类目销量最高？或者写一段 SQL..."
            @keydown.enter.exact.prevent="send"
          />
          <el-button type="primary" :disabled="!canSend" @click="send">发送</el-button>
        </footer>
      </main>

      <!-- 右侧：快捷追问 -->
      <aside class="ai-side">
        <div class="ai-side-section">
          <div class="ai-side-title">💡 快捷追问</div>
          <button v-for="q in quickQuestions" :key="q" class="ai-quick-btn" @click="quickAsk(q)">
            {{ q }}
          </button>
        </div>

        <div class="ai-side-section">
          <div class="ai-side-title">📋 数据库表</div>
          <ul class="ai-tables">
            <li><code>order_info</code> 订单（status: 0待付/1已付/2发/3完/4取消）</li>
            <li><code>order_item</code> 订单项</li>
            <li><code>product</code> 商品</li>
            <li><code>category</code> 类目</li>
            <li><code>user_behavior</code> 用户行为（view/click/cart/buy）</li>
            <li><code>product_exposure</code> 推荐曝光</li>
            <li><code>seckill_product</code> 秒杀商品</li>
          </ul>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import mermaid from 'mermaid'
import { streamAdminChat, fetchAgentGreeting } from '../api/agent'

mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' })

const messages = ref([])
const draft = ref('')
const loading = ref(false)
const status = ref('在线')
const scrollEl = ref(null)
let abortController = null

const quickQuestions = [
  '今天的销售概览',
  '最近 7 天哪个类目销量最高',
  '上周订单取消率',
  '最近 7 天曝光最多但点击最少的商品',
  '最近 30 天销售趋势',
  '所有秒杀商品的库存与售出情况'
]

const canSend = computed(() => !loading.value && draft.value.trim().length > 0)

onMounted(async () => {
  const g = await fetchAgentGreeting()
  messages.value.push({ role: 'assistant', content: g.admin || '你好！我是经营分析助手 📊', steps: [], charts: [] })
})

function pretty(o) {
  try { return JSON.stringify(o, null, 2) } catch (e) { return String(o) }
}

function escapeHtml(s) {
  return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
/**
 * 极简 Markdown：换行/粗体/code/list/table。
 * 表格需要识别 `| col | col |` + `|---|---|`。
 */
function renderMarkdown(text) {
  if (!text) return ''
  let s = escapeHtml(text)
  // 代码块（含 mermaid）：作为前置图表已经被 chart 事件处理；这里仅渲染一般代码块
  s = s.replace(/```(\w+)?\n([\s\S]*?)```/g, (_, lang, code) =>
    `<pre class="ai-code"><code>${code}</code></pre>`)
  // 行内 code
  s = s.replace(/`([^`]+)`/g, '<code class="ai-inline-code">$1</code>')
  // 加粗
  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  // 表格：识别连续 |-line
  const lines = s.split('\n')
  const out = []
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    // 表格判定
    if (/^\s*\|.*\|\s*$/.test(line) && i + 1 < lines.length && /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1])) {
      const headers = line.split('|').slice(1, -1).map(c => c.trim())
      const rows = []
      let k = i + 2
      while (k < lines.length && /^\s*\|.*\|\s*$/.test(lines[k])) {
        rows.push(lines[k].split('|').slice(1, -1).map(c => c.trim()))
        k++
      }
      out.push('<table class="ai-table"><thead><tr>' +
        headers.map(h => `<th>${h}</th>`).join('') + '</tr></thead><tbody>' +
        rows.map(r => '<tr>' + r.map(c => `<td>${c}</td>`).join('') + '</tr>').join('') +
        '</tbody></table>')
      i = k
      continue
    }
    // 列表
    if (/^- /.test(line)) {
      const items = []
      while (i < lines.length && /^- /.test(lines[i])) {
        items.push(`<li>${lines[i].replace(/^- /, '')}</li>`)
        i++
      }
      out.push(`<ul>${items.join('')}</ul>`)
      continue
    }
    if (/^\d+\.\s/.test(line)) {
      const items = []
      while (i < lines.length && /^\d+\.\s/.test(lines[i])) {
        items.push(`<li>${lines[i].replace(/^\d+\.\s/, '')}</li>`)
        i++
      }
      out.push(`<ol>${items.join('')}</ol>`)
      continue
    }
    out.push(line)
    i++
  }
  return out.join('<br/>')
}

let chartSeq = 0
function bindChartEl(el, chart) {
  if (!el || chart._rendered) return
  chart._rendered = true
  chart._id = `mermaid-${++chartSeq}-${Date.now()}`
  try {
    mermaid.render(chart._id, chart.mermaid).then(({ svg }) => {
      el.innerHTML = svg
    }).catch(err => {
      el.innerHTML = `<pre style="color:#b91c1c">图表渲染失败: ${escapeHtml(err.message || err)}</pre>` +
        `<pre>${escapeHtml(chart.mermaid)}</pre>`
    })
  } catch (e) {
    el.innerHTML = `<pre>${escapeHtml(chart.mermaid)}</pre>`
  }
}

async function send() {
  if (!canSend.value) return
  const text = draft.value.trim()
  draft.value = ''

  messages.value.push({ role: 'user', content: text })
  const asst = { role: 'assistant', content: '', steps: [], charts: [] }
  messages.value.push(asst)
  loading.value = true
  status.value = '思考中...'
  scrollToBottom()

  const history = messages.value
    .slice(0, -2)
    .filter(m => m.role === 'user' || (m.role === 'assistant' && m.content))
    .map(m => ({ role: m.role, content: m.content }))

  abortController = new AbortController()
  try {
    await streamAdminChat({
      messages: history,
      userMessage: text,
      signal: abortController.signal,
      onEvent: ({ event, payload }) => {
        if (event === 'token') {
          asst.content += payload.text || ''
          status.value = '生成中...'
          scrollToBottom()
        } else if (event === 'tool_call') {
          asst.steps.push({ id: payload.id, name: payload.name, args: payload.args })
          status.value = `调用工具 ${payload.name}...`
          scrollToBottom()
        } else if (event === 'tool_result') {
          const step = asst.steps.find(s => s.id === payload.id)
          if (step) {
            step.result = payload.result
            step.ms = payload.ms
          } else {
            asst.steps.push({ name: payload.name, result: payload.result, ms: payload.ms })
          }
          status.value = '继续生成...'
          scrollToBottom()
        } else if (event === 'chart') {
          asst.charts.push({ title: payload.title, mermaid: payload.mermaid })
          scrollToBottom()
        } else if (event === 'error') {
          asst.content += `\n\n⚠ ${payload.message || '出错了'}`
          status.value = '出错'
          scrollToBottom()
        } else if (event === 'done') {
          status.value = '在线'
        }
      }
    })
  } catch (e) {
    asst.content += `\n\n⚠ ${e.message || '请求失败'}`
    status.value = '出错'
    ElMessage.error(e.message || '请求失败')
  } finally {
    loading.value = false
    abortController = null
    if (status.value !== '出错') status.value = '在线'
  }
}

function quickAsk(q) {
  draft.value = q
  send()
}

function reset() {
  if (loading.value && abortController) abortController.abort()
  messages.value = []
  loading.value = false
  status.value = '在线'
  ;(async () => {
    const g = await fetchAgentGreeting()
    messages.value.push({ role: 'assistant', content: g.admin || '你好！我是经营分析助手 📊', steps: [], charts: [] })
  })()
}

function scrollToBottom() {
  nextTick(() => { if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight })
}
</script>

<style scoped>
.ai-page {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.ai-layout {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 18px;
  padding: 12px;
  min-height: 0;
}

.ai-chat {
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 2px solid #101010;
  border-radius: 12px;
  box-shadow: 4px 4px 0 #101010;
  overflow: hidden;
  min-height: 0;
}

.ai-chat-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: #ffd84d;
  border-bottom: 2px solid #101010;
  font-weight: 700;
}
.ai-chat-title { font-size: 15px; flex: 0 0 auto; }
.ai-chat-status { font-size: 12px; color: #555; flex: 1; }
.ai-mini-btn {
  padding: 6px 12px;
  font-size: 12px;
  border: 1.5px solid #101010;
  background: #fff;
  border-radius: 6px;
  cursor: pointer;
  font-weight: 700;
}

.ai-msgs {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: #fffef7;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.ai-msg-user .ai-bubble {
  background: #76e4f7;
  border: 2px solid #101010;
  border-radius: 12px 12px 4px 12px;
  padding: 10px 14px;
  margin-left: 80px;
  word-break: break-word;
  align-self: flex-end;
}
.ai-msg-assistant .ai-bubble {
  background: #fff;
  border: 2px solid #101010;
  border-radius: 12px 12px 12px 4px;
  padding: 12px 16px;
  margin-right: 60px;
  word-break: break-word;
  line-height: 1.7;
}

.ai-bubble :deep(table.ai-table) {
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 13px;
  width: 100%;
}
.ai-bubble :deep(table.ai-table th),
.ai-bubble :deep(table.ai-table td) {
  border: 1px solid #cbd5e1;
  padding: 6px 10px;
  text-align: left;
}
.ai-bubble :deep(table.ai-table th) {
  background: #f1f5f9;
  font-weight: 700;
}
.ai-bubble :deep(.ai-code) {
  display: block;
  background: #f1f5f9;
  padding: 8px;
  border-radius: 6px;
  margin: 8px 0;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  white-space: pre-wrap;
  border: 1px solid #cbd5e1;
}
.ai-bubble :deep(.ai-inline-code) {
  background: #f1f5f9;
  padding: 1px 5px;
  border-radius: 4px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
}
.ai-bubble :deep(ul),
.ai-bubble :deep(ol) { margin: 6px 0 6px 22px; }

.ai-tools {
  margin-bottom: 6px;
}
.ai-tools details {
  background: #fefce8;
  border: 1px dashed #a16207;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 12px;
  color: #92400e;
}
.ai-tools summary { cursor: pointer; font-weight: 700; }
.ai-tool-step { margin-top: 8px; }
.ai-tool-name { font-weight: 700; font-size: 12px; }
.ai-tool-ms { color: #555; font-weight: 500; margin-left: 4px; font-size: 11px; }
.ai-tool-args, .ai-tool-result {
  margin: 3px 0;
  padding: 6px 8px;
  background: #fffef7;
  border: 1px solid #e5d5b3;
  border-radius: 4px;
  font-size: 11px;
  white-space: pre-wrap;
  max-height: 160px;
  overflow-y: auto;
  word-break: break-all;
}

.ai-chart {
  margin-top: 10px;
  border: 2px solid #101010;
  border-radius: 10px;
  background: #fff;
  padding: 12px;
  box-shadow: 3px 3px 0 #101010;
}
.ai-chart-title { font-weight: 700; font-size: 13px; margin-bottom: 6px; color: #101010; }
.ai-chart-body { overflow-x: auto; }
.ai-chart-body :deep(svg) { max-width: 100%; height: auto; }

.ai-input-row {
  display: flex;
  gap: 10px;
  padding: 12px;
  border-top: 2px solid #101010;
  background: #fff;
}
.ai-input-row textarea {
  flex: 1;
  padding: 8px;
  border: 2px solid #101010;
  border-radius: 8px;
  font-family: inherit;
  font-size: 13px;
  resize: none;
  outline: none;
}
.ai-input-row textarea:focus { border-color: #3b82f6; }

.ai-typing {
  display: inline-flex;
  gap: 4px;
  padding: 8px 12px;
  background: #fff;
  border: 2px solid #101010;
  border-radius: 14px;
  align-self: flex-start;
}
.ai-typing span {
  width: 6px; height: 6px;
  background: #101010;
  border-radius: 50%;
  animation: ai-bounce 1.2s infinite ease-in-out;
}
.ai-typing span:nth-child(2) { animation-delay: 0.2s; }
.ai-typing span:nth-child(3) { animation-delay: 0.4s; }
@keyframes ai-bounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.5; }
  40% { transform: scale(1); opacity: 1; }
}

.ai-side {
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
}
.ai-side-section {
  background: #fff;
  border: 2px solid #101010;
  border-radius: 12px;
  padding: 12px;
  box-shadow: 3px 3px 0 #101010;
}
.ai-side-title { font-weight: 700; font-size: 13px; margin-bottom: 10px; }
.ai-quick-btn {
  display: block;
  width: 100%;
  padding: 8px 10px;
  font-size: 12px;
  margin-bottom: 6px;
  text-align: left;
  background: #fffef7;
  border: 1.5px solid #101010;
  border-radius: 6px;
  cursor: pointer;
  font-weight: 600;
  transition: all 0.15s ease;
}
.ai-quick-btn:hover {
  background: #fff7d6;
  transform: translate(-1px, -1px);
  box-shadow: 2px 2px 0 #101010;
}
.ai-tables {
  margin: 0;
  padding-left: 16px;
  font-size: 12px;
  color: #475569;
  line-height: 1.8;
}
.ai-tables code {
  background: #f1f5f9;
  padding: 1px 5px;
  border-radius: 3px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  color: #0f172a;
}

@media (max-width: 1100px) {
  .ai-layout {
    grid-template-columns: 1fr;
  }
  .ai-side { order: -1; max-height: 220px; }
}
</style>
