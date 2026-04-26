<template>
  <!-- 悬浮按钮 -->
  <button v-if="!open" class="agent-fab" @click="toggle" title="AI 购物助手">
    <span class="agent-fab-icon">🤖</span>
    <span class="agent-fab-label">AI 助手</span>
  </button>

  <!-- 抽屉式聊天窗 -->
  <transition name="agent-drawer">
    <section v-if="open" class="agent-drawer">
      <header class="agent-header">
        <div class="agent-title">
          <span class="agent-avatar">🛒</span>
          <div>
            <div class="agent-title-text">AI 购物助手</div>
            <div class="agent-status">{{ status }}</div>
          </div>
        </div>
        <div class="agent-actions">
          <button class="agent-icon-btn" @click="resetConversation" title="清空对话">↻</button>
          <button class="agent-icon-btn" @click="toggle" title="收起">×</button>
        </div>
      </header>

      <div ref="scrollEl" class="agent-messages">
        <div v-for="(m, i) in messages" :key="i" :class="['agent-msg', `agent-msg-${m.role}`]">
          <div v-if="m.role === 'assistant' && m.steps && m.steps.length" class="agent-tools">
            <details>
              <summary>查询了 {{ m.steps.length }} 步真实数据</summary>
              <div v-for="(s, si) in m.steps" :key="si" class="agent-tool-step">
                <div class="agent-tool-name">⚙ {{ s.name }} <span v-if="s.ms != null">({{ s.ms }}ms)</span></div>
                <pre class="agent-tool-args">{{ pretty(s.args) }}</pre>
                <pre class="agent-tool-result">{{ pretty(s.result) }}</pre>
              </div>
            </details>
          </div>

          <div v-if="m.content" class="agent-msg-bubble" v-html="renderMarkdown(m.content)"></div>

          <!-- ActionCard 渲染 -->
          <div v-for="(c, ci) in (m.cards || [])" :key="ci" class="agent-card">
            <!-- 订单预览卡片 -->
            <template v-if="c.type === 'order_preview'">
              <div class="agent-card-title">{{ c.title || '订单预览' }}</div>
              <div class="agent-order-items">
                <div v-for="it in c.preview.items" :key="it.product_id" class="agent-order-item">
                  <img v-if="it.product_image" :src="it.product_image" alt="" />
                  <div class="agent-order-meta">
                    <div class="agent-order-name">{{ it.product_name }}</div>
                    <div class="agent-order-price">¥{{ it.price }} × {{ it.quantity }} = ¥{{ it.subtotal }}</div>
                  </div>
                </div>
              </div>
              <div class="agent-order-total">合计 <strong>¥{{ c.preview.total_amount }}</strong></div>
              <div class="agent-order-addr">
                <div>{{ c.preview.receiver_name }} · {{ c.preview.receiver_phone }}</div>
                <div>{{ c.preview.receiver_address }}</div>
              </div>
              <div class="agent-card-actions">
                <button class="agent-btn-primary" :disabled="c._submitting || c._submitted"
                  @click="confirmOrder(c, m)">
                  {{ c._submitted ? '已下单 ✓' : (c._submitting ? '下单中...' : '确认下单') }}
                </button>
              </div>
            </template>
          </div>
        </div>

        <div v-if="loading" class="agent-typing">
          <span></span><span></span><span></span>
        </div>
      </div>

      <footer class="agent-input-row">
        <textarea
          ref="inputEl"
          v-model="draft"
          rows="2"
          placeholder="问我推荐什么、帮你下单..."
          @keydown.enter.exact.prevent="send"
        />
        <button class="agent-btn-primary" :disabled="!canSend" @click="send">发送</button>
      </footer>
    </section>
  </transition>
</template>

<script setup>
import { ref, nextTick, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { streamUserChat, fetchGreeting } from '../api/agent'
import { useUserStore } from '../store/user'
import { useCartStore } from '../store/cart'

const userStore = useUserStore()
const cartStore = useCartStore()

const open = ref(false)
const messages = ref([])  // {role, content, steps?, cards?}
const draft = ref('')
const loading = ref(false)
const status = ref('在线')
const scrollEl = ref(null)
const inputEl = ref(null)
let abortController = null

const canSend = computed(() => !loading.value && draft.value.trim().length > 0)

onMounted(async () => {
  if (messages.value.length === 0) {
    try {
      const g = await fetchGreeting()
      messages.value.push({ role: 'assistant', content: g.user || '你好！我是 AI 购物助手' })
    } catch (e) {
      messages.value.push({ role: 'assistant', content: '你好！我是 AI 购物助手' })
    }
  }
})

function toggle() {
  if (!userStore.token) {
    ElMessage.warning('请先登录后再使用 AI 助手')
    return
  }
  open.value = !open.value
  if (open.value) nextTick(() => inputEl.value?.focus())
}

function pretty(o) {
  try { return JSON.stringify(o, null, 2) } catch (e) { return String(o) }
}

/**
 * 极简 Markdown -> HTML 渲染器（避免引入额外依赖）。
 * 支持：换行、粗体、斜体、行内 code、代码块、无序列表、有序列表、链接。
 */
function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
function renderMarkdown(text) {
  if (!text) return ''
  let html = escapeHtml(text)
  // code block
  html = html.replace(/```([\s\S]*?)```/g, (_, code) => `<pre class="agent-code">${code}</pre>`)
  // inline code
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>')
  // bold/italic
  html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>')
  // links
  html = html.replace(/\[([^\]]+)\]\((https?:\/\/[^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>')
  // lists：以行起首的 - 或 数字.
  const lines = html.split('\n')
  const out = []
  let inUl = false, inOl = false
  for (const ln of lines) {
    if (/^- /.test(ln)) {
      if (!inUl) { out.push('<ul>'); inUl = true }
      out.push(`<li>${ln.replace(/^- /, '')}</li>`)
    } else if (/^\d+\.\s/.test(ln)) {
      if (!inOl) { out.push('<ol>'); inOl = true }
      out.push(`<li>${ln.replace(/^\d+\.\s/, '')}</li>`)
    } else {
      if (inUl) { out.push('</ul>'); inUl = false }
      if (inOl) { out.push('</ol>'); inOl = false }
      out.push(ln)
    }
  }
  if (inUl) out.push('</ul>')
  if (inOl) out.push('</ol>')
  return out.join('<br/>')
}

async function send() {
  if (!canSend.value) return
  const text = draft.value.trim()
  draft.value = ''

  const userMsg = { role: 'user', content: text }
  messages.value.push(userMsg)
  // 占位的 assistant 消息，后续 token/tool/card 都往里追加
  const asstMsg = { role: 'assistant', content: '', steps: [], cards: [] }
  messages.value.push(asstMsg)
  loading.value = true
  status.value = '思考中...'
  scrollToBottom()

  // 历史消息提交给 LLM（去掉占位的 assistant，去掉本次 userMsg 因为单独通过 user_message 字段传）
  const history = messages.value
    .slice(0, -2)
    .filter(m => m.role === 'user' || (m.role === 'assistant' && m.content))
    .map(m => ({ role: m.role, content: m.content }))

  abortController = new AbortController()
  try {
    await streamUserChat({
      messages: history,
      userMessage: text,
      signal: abortController.signal,
      onEvent: ({ event, payload }) => {
        if (event === 'token') {
          asstMsg.content += payload.text || ''
          status.value = '生成中...'
          scrollToBottom()
        } else if (event === 'tool_call') {
          asstMsg.steps.push({ id: payload.id, name: payload.name, args: payload.args })
          status.value = `调用工具 ${payload.name}...`
          scrollToBottom()
        } else if (event === 'tool_result') {
          const step = asstMsg.steps.find(s => s.id === payload.id)
          if (step) {
            step.result = payload.result
            step.ms = payload.ms
          } else {
            asstMsg.steps.push({ name: payload.name, result: payload.result, ms: payload.ms })
          }
          status.value = '继续生成...'
          scrollToBottom()
        } else if (event === 'action_card') {
          asstMsg.cards.push({ ...payload, _submitting: false, _submitted: false })
          scrollToBottom()
        } else if (event === 'error') {
          asstMsg.content += `\n\n⚠ ${payload.message || '出错了'}`
          status.value = '出错'
          scrollToBottom()
        } else if (event === 'done') {
          status.value = '在线'
        }
      }
    })
  } catch (e) {
    asstMsg.content += `\n\n⚠ ${e.message || '请求失败'}`
    status.value = '出错'
  } finally {
    loading.value = false
    abortController = null
    if (status.value !== '出错') status.value = '在线'
  }
}

async function confirmOrder(card, msg) {
  if (card._submitting || card._submitted) return
  card._submitting = true
  try {
    const submit = card.submit || {}
    const body = submit.body || {}
    await ensureCartItems(card, body)
    const submitPath = normalizeApiPath(submit.path || '/order/create')
    const resp = api._raw
      ? await api._raw.post(submitPath, body)
      : await axiosFallbackPost(submitPath, body)
    // axios 默认会通过响应拦截器，这里再尝试 api 的 createOrder 路径
    const order = (resp && resp.data) || resp
    card._submitted = true
    ElMessage.success(`下单成功！订单号 ${order.orderNo || order.id}`)
    // 刷新购物车数量（订单创建可能清掉购物车里相关项 — 后端逻辑无关，刷新只是避免数字过期）
    if (userStore.userInfo?.id) cartStore.fetchCartCount(userStore.userInfo.id)
  } catch (e) {
    const m = (e?.response?.data?.message) || e.message || '下单失败'
    ElMessage.error(m)
  } finally {
    card._submitting = false
  }
}

// 由于 frontend/src/api/index.js 默认导出的是 wrapper，这里直接复用 axios 实例做 POST
import axios from 'axios'
function normalizeApiPath(path) {
  const value = String(path || '/order/create').trim() || '/order/create'
  return value.replace(/^\/api(?=\/)/, '')
}
async function ensureCartItems(card, orderBody) {
  const userId = orderBody?.userId || userStore.userInfo?.id
  const items = Array.isArray(orderBody?.items) ? orderBody.items : []
  if (!userId || items.length === 0) return

  const cartResp = await api.getCart(userId)
  const cartItems = Array.isArray(cartResp?.data) ? cartResp.data : []
  const cartQuantityByProduct = new Map(
    cartItems.map(it => [Number(it.productId), Number(it.quantity || 0)])
  )

  for (const item of items) {
    const productId = Number(item.productId)
    const desiredQty = Number(item.quantity || 1)
    const currentQty = cartQuantityByProduct.get(productId) || 0
    const missingQty = Math.max(0, desiredQty - currentQty)
    if (missingQty === 0) continue

    const previewItem = (card?.preview?.items || []).find(it => Number(it.product_id) === productId) || {}
    await api.addToCart({
      userId,
      productId,
      productName: previewItem.product_name || '',
      productImage: previewItem.product_image || '',
      quantity: missingQty
    })
    cartQuantityByProduct.set(productId, currentQty + missingQty)
  }
}
async function axiosFallbackPost(path, body) {
  const baseURL = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')
  const token = localStorage.getItem('token') || ''
  const submitPath = normalizeApiPath(path)
  return await axios.post(`${baseURL}${submitPath}`, body, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    timeout: 10000
  })
}

function resetConversation() {
  if (loading.value && abortController) abortController.abort()
  messages.value = []
  loading.value = false
  status.value = '在线'
  ;(async () => {
    const g = await fetchGreeting().catch(() => ({}))
    messages.value.push({ role: 'assistant', content: g.user || '你好！我是 AI 购物助手' })
  })()
}

function scrollToBottom() {
  nextTick(() => {
    if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight
  })
}
</script>

<style scoped>
.agent-fab {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 999;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 18px;
  font-size: 14px;
  font-weight: 700;
  color: #101010;
  background: #76e4f7;
  border: 2.5px solid #101010;
  border-radius: 999px;
  cursor: pointer;
  box-shadow: 4px 4px 0 #101010;
  transition: all 0.15s ease;
}
.agent-fab:hover {
  transform: translate(-2px, -2px);
  box-shadow: 6px 6px 0 #101010;
}
.agent-fab-icon { font-size: 18px; }

.agent-drawer {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 1000;
  width: 420px;
  height: 640px;
  max-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 2.5px solid #101010;
  border-radius: 14px;
  box-shadow: 8px 8px 0 #101010;
  overflow: hidden;
}

.agent-drawer-enter-active,
.agent-drawer-leave-active { transition: all 0.25s ease; }
.agent-drawer-enter-from,
.agent-drawer-leave-to { opacity: 0; transform: translateY(20px) scale(0.95); }

.agent-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: #ffd84d;
  border-bottom: 2.5px solid #101010;
}
.agent-title { display: flex; align-items: center; gap: 10px; }
.agent-avatar {
  width: 36px; height: 36px;
  display: flex; align-items: center; justify-content: center;
  background: #fff; border: 2px solid #101010; border-radius: 50%;
  font-size: 18px;
}
.agent-title-text { font-weight: 800; font-size: 14px; color: #101010; }
.agent-status { font-size: 11px; color: #555; }
.agent-actions { display: flex; gap: 6px; }
.agent-icon-btn {
  width: 28px; height: 28px;
  border: 2px solid #101010;
  background: #fff;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 700;
}
.agent-icon-btn:hover { background: #fff7d6; }

.agent-messages {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  background: #fffef7;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.agent-msg { max-width: 100%; }
.agent-msg-user { align-self: flex-end; }
.agent-msg-user .agent-msg-bubble {
  background: #76e4f7;
  border: 2px solid #101010;
  border-radius: 14px 14px 4px 14px;
  padding: 10px 14px;
  margin-left: 40px;
  word-break: break-word;
}
.agent-msg-assistant .agent-msg-bubble {
  background: #fff;
  border: 2px solid #101010;
  border-radius: 14px 14px 14px 4px;
  padding: 10px 14px;
  margin-right: 40px;
  word-break: break-word;
}
.agent-msg-bubble :deep(ul),
.agent-msg-bubble :deep(ol) { margin: 6px 0 6px 18px; }
.agent-msg-bubble :deep(code) {
  background: #f1f5f9;
  padding: 1px 5px;
  border-radius: 4px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
}
.agent-msg-bubble :deep(.agent-code) {
  display: block;
  background: #f1f5f9;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  padding: 8px;
  margin: 6px 0;
  white-space: pre-wrap;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
}

.agent-tools {
  margin-bottom: 6px;
}
.agent-tools details {
  background: #fefce8;
  border: 1px dashed #a16207;
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 11px;
  color: #92400e;
}
.agent-tools summary { cursor: pointer; font-weight: 700; }
.agent-tool-step { margin-top: 6px; }
.agent-tool-name { font-weight: 700; }
.agent-tool-args, .agent-tool-result {
  margin: 2px 0;
  padding: 4px 6px;
  background: #fffef7;
  border: 1px solid #e5d5b3;
  border-radius: 4px;
  font-size: 10px;
  white-space: pre-wrap;
  max-height: 120px;
  overflow-y: auto;
  word-break: break-all;
}

.agent-card {
  margin-top: 10px;
  background: #fff;
  border: 2px solid #101010;
  border-radius: 12px;
  padding: 12px;
  box-shadow: 3px 3px 0 #101010;
}
.agent-card-title {
  font-weight: 800;
  font-size: 14px;
  color: #101010;
  margin-bottom: 8px;
  border-bottom: 1.5px solid #101010;
  padding-bottom: 6px;
}
.agent-order-items {
  display: flex; flex-direction: column; gap: 6px;
  max-height: 180px; overflow-y: auto;
}
.agent-order-item {
  display: flex; gap: 8px; align-items: center;
  padding: 4px;
  background: #fffef7;
  border-radius: 6px;
}
.agent-order-item img {
  width: 36px; height: 36px; object-fit: cover;
  border: 1px solid #101010; border-radius: 4px;
}
.agent-order-meta { flex: 1; min-width: 0; }
.agent-order-name {
  font-size: 12px; font-weight: 600;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.agent-order-price { font-size: 11px; color: #d92f2f; font-weight: 700; }
.agent-order-total {
  margin-top: 6px;
  text-align: right;
  font-size: 13px;
}
.agent-order-total strong { color: #d92f2f; font-size: 16px; }
.agent-order-addr {
  margin-top: 6px;
  padding: 6px 8px;
  background: #f0f9ff;
  border-radius: 6px;
  font-size: 11px;
  color: #1e3a8a;
}
.agent-card-actions {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
}

.agent-input-row {
  display: flex;
  gap: 8px;
  padding: 10px;
  border-top: 2.5px solid #101010;
  background: #fff;
}
.agent-input-row textarea {
  flex: 1;
  padding: 8px;
  border: 2px solid #101010;
  border-radius: 8px;
  font-size: 13px;
  font-family: inherit;
  resize: none;
  outline: none;
}
.agent-input-row textarea:focus { border-color: #3b82f6; }

.agent-btn-primary {
  padding: 8px 18px;
  font-size: 13px;
  font-weight: 700;
  color: #fff;
  background: #3b82f6;
  border: 2px solid #101010;
  border-radius: 8px;
  cursor: pointer;
  box-shadow: 2px 2px 0 #101010;
  transition: all 0.15s ease;
}
.agent-btn-primary:hover:not(:disabled) {
  transform: translate(-1px, -1px);
  box-shadow: 3px 3px 0 #101010;
  background: #2563eb;
}
.agent-btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.agent-typing {
  display: inline-flex;
  gap: 4px;
  padding: 8px 12px;
  background: #fff;
  border: 2px solid #101010;
  border-radius: 14px;
  align-self: flex-start;
}
.agent-typing span {
  width: 6px; height: 6px;
  background: #101010;
  border-radius: 50%;
  animation: agent-bounce 1.2s infinite ease-in-out;
}
.agent-typing span:nth-child(2) { animation-delay: 0.2s; }
.agent-typing span:nth-child(3) { animation-delay: 0.4s; }
@keyframes agent-bounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.5; }
  40% { transform: scale(1); opacity: 1; }
}

@media (max-width: 600px) {
  .agent-drawer {
    right: 12px; bottom: 12px;
    width: calc(100vw - 24px);
    height: calc(100vh - 100px);
  }
}
</style>
