<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteJson, getJson, postJson } from '../api'

const props = defineProps({
  learnerId: { type: String, required: true },
  projectId: { type: String, required: true },
  citations: { type: Array, default: () => [] }
})

const activeTab = ref('citations')
const knowledge = ref({})
const sources = ref([])
const activeSource = ref(null)
const sourceForm = ref({ name: '', type: 'obsidian', rootPath: '' })
const sourceStatus = ref({ text: '', kind: '' })
const adding = ref(false)
const picking = ref(false)
const reindexingAll = ref(false)
const rebuildingId = ref('')
const clearing = ref(false)
const reindexElapsed = ref(0)
const lastIndexResult = ref(null)
const lastAllResult = ref(null)
let reindexTimer = null

function setSourceStatus(text, kind = '') {
  sourceStatus.value = { text, kind }
}

function startReindexTimer() {
  reindexElapsed.value = 0
  reindexTimer = setInterval(() => {
    reindexElapsed.value += 1
  }, 1000)
}

function stopReindexTimer() {
  if (reindexTimer) {
    clearInterval(reindexTimer)
    reindexTimer = null
  }
}

/** 显示单个知识源的重建结果。 */
function showSourceIndexResult(name, result) {
  const scanned = result.scannedNotes ?? 0
  const indexed = result.indexedNotes ?? 0
  const skipped = result.skippedNotes ?? 0
  const failed = result.failedNotes ?? 0
  const chunks = result.chunks ?? 0
  if (scanned === 0) {
    ElMessage.warning(result.failureReason || '没有扫描到笔记，请检查知识源目录')
  } else if (failed > 0) {
    ElMessage({
      type: indexed > 0 ? 'warning' : 'error',
      message: `「${name}」扫描 ${scanned} 篇，成功 ${indexed} 篇，失败 ${failed} 篇：${result.failureReason || '未知原因'}`,
      duration: 8000,
      showClose: true
    })
  } else {
    ElMessage.success(`「${name}」扫描 ${scanned} 篇：成功 ${indexed} 篇，跳过 ${skipped} 篇，新增 ${chunks} 块`)
  }
}

async function loadKnowledge() {
  try {
    knowledge.value = await getJson('/api/v1/knowledge/status')
  } catch (error) {
    knowledge.value = {}
  }
}

async function loadSources() {
  try {
    const data = await getJson('/api/v1/knowledge/sources')
    sources.value = data.sources || []
    activeSource.value = data.active || null
  } catch (error) {
    setSourceStatus(`读取知识源失败：${error.message}`, 'err')
  }
}

async function pickDirectory() {
  picking.value = true
  setSourceStatus('已打开系统文件夹选择窗口，请在弹窗里选好目录…', 'warn')
  try {
    const data = await postJson('/api/v1/knowledge/pick-directory', {
      initialPath: sourceForm.value.rootPath.trim()
    })
    if (data.cancelled || !data.path) {
      setSourceStatus('已取消选择。', 'warn')
      return
    }
    sourceForm.value.rootPath = data.path
    setSourceStatus(`已选择：${data.path}`, 'ok')
  } catch (error) {
    setSourceStatus(`选择失败：${error.message}（也可以直接手动填写路径）`, 'err')
  } finally {
    picking.value = false
  }
}

async function addSource() {
  const rootPath = sourceForm.value.rootPath.trim()
  if (!rootPath) {
    setSourceStatus('请填写目录路径。', 'warn')
    return
  }
  adding.value = true
  setSourceStatus('正在添加并重建索引，请稍候…', 'warn')
  try {
    const data = await postJson('/api/v1/knowledge/sources', {
      name: sourceForm.value.name.trim() || null,
      type: sourceForm.value.type,
      rootPath,
      activate: true,
      reindex: true
    })
    const index = data.index
    if (index && index.failureReason && !index.indexedNotes) {
      setSourceStatus(`已登记知识源，但索引未完成：${index.failureReason}`, 'err')
    } else {
      setSourceStatus(index
        ? `已切换到新库，索引 ${index.indexedNotes} 篇 / ${index.chunks} 块`
        : '已添加知识源', 'ok')
    }
    sourceForm.value = { name: '', type: sourceForm.value.type, rootPath: '' }
    await Promise.all([loadSources(), loadKnowledge()])
  } catch (error) {
    setSourceStatus(`添加失败：${error.message}`, 'err')
  } finally {
    adding.value = false
  }
}

async function activateSource(source) {
  setSourceStatus(`正在切换到「${source.name}」并重建索引…`, 'warn')
  try {
    const data = await postJson(`/api/v1/knowledge/sources/${source.id}/activate?reindex=true`, {})
    const index = data.index
    if (index && index.failureReason && !index.indexedNotes) {
      setSourceStatus(`已切换，但索引未完成：${index.failureReason}`, 'err')
    } else {
      setSourceStatus(index
        ? `已切换到「${source.name}」，索引 ${index.indexedNotes} 篇 / ${index.chunks} 块`
        : `已切换到「${source.name}」`, 'ok')
    }
    await Promise.all([loadSources(), loadKnowledge()])
  } catch (error) {
    setSourceStatus(`切换失败：${error.message}`, 'err')
  }
}

async function removeSource(source) {
  try {
    await ElMessageBox.confirm(
      `只是从列表移除「${source.name}」，不会删除磁盘上的笔记。`,
      '移除知识源？',
      { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' }
    )
  } catch (ignored) {
    return
  }
  try {
    await deleteJson(`/api/v1/knowledge/sources/${source.id}`)
    setSourceStatus(`已移除「${source.name}」`, 'ok')
    await Promise.all([loadSources(), loadKnowledge()])
  } catch (error) {
    setSourceStatus(`移除失败：${error.message}`, 'err')
  }
}

/** 顶部「重建索引」：重建下面所有知识源，与单个知识源卡片里的重建不同。 */
async function reindexAllSources() {
  reindexingAll.value = true
  startReindexTimer()
  try {
    const summary = await postJson('/api/v1/knowledge/reindex', {})
    lastAllResult.value = summary
    lastIndexResult.value = null
    const count = summary.sources ?? 0
    const failedSources = summary.failedSources ?? 0
    const scanned = summary.scannedNotes ?? 0
    const indexed = summary.indexedNotes ?? 0
    const skipped = summary.skippedNotes ?? 0
    const chunks = summary.chunks ?? 0
    if (count === 0) {
      ElMessage.warning(summary.failureReason || '还没有登记知识源，请先在下面添加一个笔记目录')
    } else if (failedSources > 0) {
      ElMessage({
        type: indexed > 0 ? 'warning' : 'error',
        message: `重建 ${count} 个知识源：扫描 ${scanned} 篇，成功 ${indexed} 篇，${failedSources} 个源有问题：${summary.failureReason || '原因未返回'}`,
        duration: 9000,
        showClose: true
      })
    } else {
      ElMessage.success(`重建 ${count} 个知识源：扫描 ${scanned} 篇，成功 ${indexed} 篇，跳过 ${skipped} 篇，新增 ${chunks} 块`)
    }
    await Promise.all([loadKnowledge(), loadSources()])
  } catch (error) {
    ElMessage.error(`重建失败：${error.message}`)
  } finally {
    stopReindexTimer()
    reindexingAll.value = false
  }
}

/** 知识源卡片里的「重建索引」：只重建这一个源，其他源的索引不动。 */
async function reindexSource(source) {
  rebuildingId.value = source.id
  lastIndexResult.value = null
  lastAllResult.value = null
  try {
    const result = await postJson(`/api/v1/knowledge/sources/${source.id}/reindex`, {})
    lastIndexResult.value = result
    showSourceIndexResult(source.name, result)
    await Promise.all([loadKnowledge(), loadSources()])
  } catch (error) {
    ElMessage.error(`重建失败：${error.message}`)
  } finally {
    rebuildingId.value = ''
  }
}

/** 顶部「清空索引」：清空全部知识源，知识库回到 0 篇 / 0 块，重启也不会自动回填。 */
async function clearIndex() {
  try {
    await ElMessageBox.confirm(
      '清空后索引和已登记的知识源都会移除，知识库回到“未配置”的空状态；磁盘上的笔记不受影响，重新添加目录即可恢复。',
      '清空知识库？',
      {
        type: 'warning',
        confirmButtonText: '清空索引',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch (ignored) {
    return
  }
  clearing.value = true
  try {
    const result = await postJson('/api/v1/knowledge/clear', {})
    lastIndexResult.value = null
    lastAllResult.value = null
    setSourceStatus(
      `已清空知识库：删除 ${result.deletedDocuments ?? 0} 篇文档，移除 ${result.clearedSources ?? 0} 个知识源`,
      'ok'
    )
    await Promise.all([loadKnowledge(), loadSources()])
    ElMessage.success('知识库已清空，现在是未配置状态')
  } catch (error) {
    ElMessage.error(`清空失败：${error.message}`)
  } finally {
    clearing.value = false
  }
}

const noteDrawer = ref(false)
const noteLoading = ref(false)
const noteDetail = ref(null)
const noteError = ref('')

/** 点引用 → 打开该笔记正文；后端会校验路径只在知识源根目录内。 */
async function openNote(citation) {
  noteDrawer.value = true
  noteDetail.value = null
  noteError.value = ''
  noteLoading.value = true
  try {
    noteDetail.value = await getJson(
      `/api/v1/knowledge/note?path=${encodeURIComponent(citation.path)}`
    )
  } catch (error) {
    noteError.value = error.message
  } finally {
    noteLoading.value = false
  }
}

onMounted(() => {
  loadKnowledge()
  loadSources()
})
</script>

<template>
  <el-tabs v-model="activeTab" stretch>
    <el-tab-pane label="引用" name="citations">
      <div v-if="!citations.length" class="muted">发送问题后，这里显示本轮引用的笔记片段</div>
      <div v-for="(citation, index) in citations" :key="index" class="citation-item">
        <div class="citation-path">
          <el-button link type="primary" @click="openNote(citation)">{{ citation.path }}</el-button>
        </div>
        <div class="citation-heading">{{ citation.heading }}</div>
        <div class="citation-snippet">{{ citation.snippet }}</div>
        <div class="citation-actions">
          <el-tag size="small" type="info">score {{ Number(citation.score || 0).toFixed(3) }}</el-tag>
          <el-button size="small" text type="primary" @click="openNote(citation)">查看笔记</el-button>
        </div>
      </div>
    </el-tab-pane>

    <el-tab-pane label="知识库" name="knowledge">
      <div class="kb-top">
        <el-button
          size="small"
          type="primary"
          :loading="reindexingAll"
          :disabled="clearing || !!rebuildingId"
          title="重建下面所有知识源的索引"
          @click="reindexAllSources"
        >
          {{ reindexingAll ? `重建中… ${reindexElapsed}s` : '重建索引' }}
        </el-button>
        <el-button
          size="small"
          type="danger"
          :loading="clearing"
          :disabled="reindexingAll || !!rebuildingId"
          title="清空索引与知识源登记，知识库回到未配置状态"
          @click="clearIndex"
        >
          {{ clearing ? '清空中…' : '清空索引' }}
        </el-button>
        <el-button size="small" text type="primary" @click="loadSources">刷新来源</el-button>
      </div>
      <div v-if="activeSource" class="kb-active">
        <div><b>当前：</b>{{ activeSource.name }}</div>
        <div class="kb-path" :class="{ bad: !activeSource.directoryExists }">{{ activeSource.rootPath }}</div>
        <div class="muted">
          {{ activeSource.docCount }} 篇 / {{ activeSource.chunkCount }} 块
          <span v-if="!activeSource.directoryExists" class="kb-bad"> · 目录不可用</span>
        </div>
      </div>

      <p class="card-title" style="margin-top: 14px">知识源（同一时间只有一个生效）</p>
      <div v-if="!sources.length" class="muted">还没有登记知识源，在下方添加一个笔记目录</div>
      <div v-for="source in sources" :key="source.id" class="src-item">
        <div class="src-head">
          <span class="src-name">{{ source.name }}</span>
          <el-tag size="small" type="info">{{ source.type === 'markdown' ? 'Markdown' : 'Obsidian' }}</el-tag>
          <el-tag v-if="source.active" size="small" type="success">使用中</el-tag>
          <el-tag v-if="!source.directoryExists" size="small" type="danger">目录不可用</el-tag>
        </div>
        <div class="kb-path" :class="{ bad: !source.directoryExists }">{{ source.rootPath }}</div>
        <div class="muted">
          索引 {{ source.docCount }} 篇 / {{ source.chunkCount }} 块
          <span v-if="source.lastIndexedAt"> · 最近 {{ source.lastIndexedAt }}</span>
          <span v-else> · 尚未索引</span>
        </div>
        <div class="src-actions">
          <el-button
            v-if="!source.active"
            size="small"
            text
            type="primary"
            :disabled="!source.directoryExists"
            @click="activateSource(source)"
          >切换到此库</el-button>
          <el-button v-if="!source.active" size="small" text type="danger" @click="removeSource(source)">移除</el-button>
          <el-button
            v-else
            size="small"
            text
            type="primary"
            :loading="rebuildingId === source.id"
            :disabled="reindexingAll || clearing"
            title="只重建这个知识源的索引"
            @click="reindexSource(source)"
          >重建索引</el-button>
        </div>
      </div>

      <el-divider style="margin: 14px 0" />

      <p class="card-title">添加笔记库</p>
      <el-input v-model="sourceForm.name" size="small" placeholder="名称（可选，留空取目录名）" />
      <el-select v-model="sourceForm.type" size="small" style="width: 100%; margin-top: 8px">
        <el-option label="Obsidian 笔记库（支持 [[双链]] 与 frontmatter）" value="obsidian" />
        <el-option label="普通 Markdown / TXT 目录" value="markdown" />
      </el-select>
      <div class="kb-path-row">
        <el-input v-model="sourceForm.rootPath" size="small" placeholder="目录绝对路径" />
        <el-button size="small" :loading="picking" @click="pickDirectory">选择目录…</el-button>
      </div>
      <el-button size="small" type="primary" :loading="adding" style="margin-top: 8px" @click="addSource">
        添加并切换
      </el-button>
      <div v-if="sourceStatus.text" class="kb-status" :class="sourceStatus.kind">{{ sourceStatus.text }}</div>

      <el-alert
        v-if="lastAllResult && lastAllResult.failedSources > 0"
        type="error"
        :closable="false"
        show-icon
        style="margin-top: 10px"
        :title="`重建 ${lastAllResult.sources} 个知识源：扫描 ${lastAllResult.scannedNotes} 篇，${lastAllResult.failedSources} 个源失败或没有笔记`"
      >
        <p style="margin: 4px 0">{{ lastAllResult.failureReason || '失败原因未返回' }}</p>
      </el-alert>
      <el-alert
        v-else-if="lastAllResult"
        type="success"
        :closable="false"
        show-icon
        style="margin-top: 10px"
        :title="`重建 ${lastAllResult.sources} 个知识源：扫描 ${lastAllResult.scannedNotes} 篇，成功 ${lastAllResult.indexedNotes} 篇，跳过 ${lastAllResult.skippedNotes} 篇，新增 ${lastAllResult.chunks} 块`"
      />
      <el-alert
        v-if="lastIndexResult && lastIndexResult.failedNotes > 0"
        type="error"
        :closable="false"
        show-icon
        style="margin-top: 10px"
        :title="`扫描 ${lastIndexResult.scannedNotes} 篇，成功 ${lastIndexResult.indexedNotes} 篇，失败 ${lastIndexResult.failedNotes} 篇`"
      >
        <p style="margin: 4px 0">{{ lastIndexResult.failureReason || '失败原因未返回' }}</p>
        <a href="/settings.html" target="_blank" style="color: inherit; text-decoration: underline">
          去 API Key 页检查 embedding 配置
        </a>
      </el-alert>
      <el-alert
        v-else-if="lastIndexResult && lastIndexResult.scannedNotes === 0"
        type="warning"
        :closable="false"
        show-icon
        style="margin-top: 10px"
        :title="lastIndexResult.failureReason || '没有扫描到笔记，请检查知识源目录'"
      />
      <el-alert
        v-else-if="lastIndexResult"
        type="success"
        :closable="false"
        show-icon
        style="margin-top: 10px"
        :title="`扫描 ${lastIndexResult.scannedNotes} 篇：成功 ${lastIndexResult.indexedNotes} 篇，跳过 ${lastIndexResult.skippedNotes} 篇，新增 ${lastIndexResult.chunks} 块`"
      />
      <p class="muted" style="margin-top: 8px">知识库目录只读，索引不会修改原笔记</p>
    </el-tab-pane>
  </el-tabs>

  <el-drawer v-model="noteDrawer" title="笔记详情" size="48%" direction="rtl">
    <div v-loading="noteLoading" style="min-height: 120px">
      <div v-if="noteError" class="muted">{{ noteError }}</div>
      <template v-else-if="noteDetail">
        <div class="note-title">{{ noteDetail.title || noteDetail.path }}</div>
        <div class="muted" style="margin-bottom: 10px">
          {{ noteDetail.path }} · {{ Math.round((noteDetail.size || 0) / 1024) }} KB
          <span v-if="noteDetail.truncated"> · 内容过长，已截断</span>
        </div>
        <pre class="note-body">{{ noteDetail.content }}</pre>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.kb-top {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
}

.kb-active {
  background: #f8fafc;
  border: 1px solid #eef2f7;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.7;
}

.kb-path {
  color: #667085;
  font-size: 12px;
  word-break: break-all;
  line-height: 1.6;
}

.kb-path.bad {
  color: #f56c6c;
}

.kb-bad {
  color: #f56c6c;
}

.src-item {
  border: 1px solid #e3e9f2;
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 8px;
  background: #fcfdff;
}

.src-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 2px;
}

.src-name {
  font-size: 13px;
  font-weight: 600;
  color: #1d2939;
}

.src-actions {
  display: flex;
  gap: 4px;
  margin-top: 4px;
}

.kb-path-row {
  display: flex;
  gap: 6px;
  margin-top: 8px;
}

.kb-path-row .el-input {
  flex: 1;
}

.kb-status {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  min-height: 18px;
}

.kb-status.ok {
  color: #067647;
}

.kb-status.warn {
  color: #b54708;
}

.kb-status.err {
  color: #b42318;
}
</style>