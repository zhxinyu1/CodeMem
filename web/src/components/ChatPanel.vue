<script setup>
import { nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { postJson, streamChat } from '../api'

const props = defineProps({
  learnerId: { type: String, required: true },
  projectId: { type: String, required: true },
  conversationId: { type: Number, default: null },
  initialMessages: { type: Array, default: () => [] }
})

const emit = defineEmits([
  'update:conversationId',
  'citations',
  'agents',
  'memories',
  'done',
  'refresh',
  'memory-saved'
])

const messages = ref([])
const input = ref('')
const sending = ref(false)
const agents = ref([])
const citations = ref([])
const memories = ref([])
const summary = ref(null)
const listRef = ref(null)
const saveVisible = ref(false)
const saving = ref(false)
const saveForm = ref({ content: '', type: '', topic: '' })

watch(
  () => props.initialMessages,
  (value) => {
    messages.value = (value || []).map((item) => ({ role: item.role, content: item.content }))
    scrollToBottom()
  },
  { immediate: true }
)

async function send() {
  const text = input.value.trim()
  if (!text || sending.value) return

  sending.value = true
  agents.value = []
  citations.value = []
  memories.value = []
  summary.value = null
  emit('agents', [])
  emit('citations', [])
  emit('memories', [])

  messages.value.push({ role: 'USER', content: text })
  const assistant = { role: 'ASSISTANT', content: '' }
  messages.value.push(assistant)
  input.value = ''
  scrollToBottom()

  try {
    await streamChat({
      learnerId: props.learnerId,
      projectId: props.projectId,
      conversationId: props.conversationId,
      message: text,
      onEvent: (name, data) => handleEvent(name, data, assistant)
    })
  } catch (error) {
    assistant.content += `\n[网络错误] ${error.message}`
    ElMessage.error('SSE 连接失败，请确认后端在 8080 运行')
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

function handleEvent(name, data, assistant) {
  if (name === 'meta') {
    if (data?.conversationId && data.conversationId !== props.conversationId) {
      emit('update:conversationId', data.conversationId)
    }
    return
  }
  if (name === 'citation') {
    citations.value = [...citations.value, data]
    emit('citations', citations.value)
    return
  }
  if (name === 'memory') {
    memories.value = [...memories.value, data]
    emit('memories', memories.value)
    return
  }
  if (name === 'agent') {
    agents.value = [...agents.value, data]
    emit('agents', agents.value)
    return
  }
  if (name === 'token') {
    assistant.content += data?.delta ?? ''
    scrollToBottom()
    return
  }
  if (name === 'done') {
    // 后端可能对引用标题做过降级，done 里带回最终答案，覆盖已渲染文本
    if (data && typeof data.answer === 'string' && data.answer) {
      assistant.content = data.answer
    }
    summary.value = data
    emit('done', data)
    emit('refresh')
    return
  }
  if (name === 'error') {
    assistant.content += `\n[${data?.code || 'ERROR'}] ${data?.message || '未知错误'}`
    ElMessage.error(data?.message || '对话出错')
  }
}

function openSaveMemory(message) {
  saveForm.value = {
    content: (message.content || '').trim(),
    type: '',
    topic: ''
  }
  saveVisible.value = true
}

async function submitSaveMemory() {
  const content = saveForm.value.content.trim()
  if (content.length < 3) {
    ElMessage.warning('记忆内容至少 3 个字符')
    return
  }
  saving.value = true
  try {
    const partition = new URLSearchParams({
      learnerId: props.learnerId,
      projectId: props.projectId
    }).toString()
    await postJson(`/api/v1/memory/items?${partition}`, {
      content,
      type: saveForm.value.type || null,
      topic: saveForm.value.topic.trim() || null
    })
    ElMessage.success('已存为长期记忆，可在右侧「记忆」面板查看')
    saveVisible.value = false
    emit('memory-saved')
  } catch (error) {
    ElMessage.error(`写入失败：${error.message}`)
  } finally {
    saving.value = false
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

function onEnter(event) {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault()
    send()
  }
}
</script>

<template>
  <div class="chat-shell">
    <div class="messages" ref="listRef">
      <el-empty v-if="!messages.length" description="输入一个中文问题开始对话，码忆会结合你的笔记和记忆回答" />
      <div v-for="(message, index) in messages" :key="index" class="msg" :class="{ user: message.role === 'USER' }">
        <div class="msg-role">
          <span>{{ message.role === 'USER' ? '我' : '码忆' }}</span>
          <el-button
            v-if="message.content"
            size="small"
            text
            type="primary"
            style="margin-left: 8px"
            @click="openSaveMemory(message)"
          >存为记忆</el-button>
        </div>
        <div class="msg-body">{{ message.content || (sending ? '正在生成…' : '') }}</div>
      </div>
    </div>

    <div v-if="agents.length" class="agent-bar">
      <el-tag v-for="(step, index) in agents" :key="index" size="small" :type="step.role === 'reviewer' ? 'warning' : 'success'">
        {{ step.role }} · {{ step.status }} · {{ (step.digest || '').slice(0, 18) }}
      </el-tag>
    </div>

    <div v-if="summary" class="muted" style="margin-top: 6px">
      trace {{ summary.traceId }} · 引用 {{ summary.citationCount }} 条 · 记忆 {{ summary.memoryCount }} 条 · 延迟 {{ summary.latencyMs }}ms
      <span v-if="summary.mock"> · Mock 模式</span>
    </div>

    <div class="composer">
      <el-input
        v-model="input"
        type="textarea"
        :rows="3"
        resize="none"
        placeholder="输入问题，Ctrl + Enter 发送"
        @keydown="onEnter"
      />
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span class="muted">SSE 流式输出；引用来自 Obsidian 只读索引</span>
        <el-button type="primary" :loading="sending" @click="send">发送</el-button>
      </div>
    </div>

    <el-dialog v-model="saveVisible" title="存为长期记忆" width="440px">
      <el-input
        v-model="saveForm.content"
        type="textarea"
        :rows="4"
        maxlength="500"
        show-word-limit
        placeholder="要记住的内容，可以再改"
      />
      <div style="display: flex; gap: 8px; margin-top: 8px">
        <el-select v-model="saveForm.type" size="small" placeholder="类型（自动）" style="width: 140px">
          <el-option label="自动推断" value="" />
          <el-option label="事实" value="FACT" />
          <el-option label="卡点" value="WEAKNESS" />
          <el-option label="计划" value="PLAN" />
          <el-option label="已掌握" value="MASTERY" />
        </el-select>
        <el-input v-model="saveForm.topic" size="small" placeholder="主题（可选）" style="flex: 1" />
      </div>
      <template #footer>
        <el-button size="small" @click="saveVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="submitSaveMemory">写入</el-button>
      </template>
    </el-dialog>
  </div>
</template>