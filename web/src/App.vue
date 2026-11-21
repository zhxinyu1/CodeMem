<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteJson, getJson, patchJson, postJson } from './api'
import ChatPanel from './components/ChatPanel.vue'
import InspectorPanel from './components/InspectorPanel.vue'

const learnerId = ref('wf-user')
const projectId = ref('java-backend')
const conversations = ref([])
const groups = ref([])
const collapsedGroups = ref({})
const conversationId = ref(null)
const messages = ref([])
const citations = ref([])
const agents = ref([])
const memories = ref([])
const refreshToken = ref(0)

const learnerQuery = () => `learnerId=${encodeURIComponent(learnerId.value)}`

async function loadConversations() {
  try {
    conversations.value = await getJson(`/api/v1/conversations?${learnerQuery()}`)
  } catch (error) {
    conversations.value = []
  }
}

async function loadGroups() {
  try {
    groups.value = await getJson(`/api/v1/conversation-groups?${learnerQuery()}`)
  } catch (error) {
    groups.value = []
  }
}

// 自定义分组在前、按分组顺序，未分组会话整体落在最后
const groupedConversations = computed(() => {
  const buckets = groups.value.map((group) => ({
    key: `group-${group.id}`,
    id: group.id,
    name: group.name,
    items: []
  }))
  const byGroupId = new Map(buckets.map((bucket) => [bucket.id, bucket]))
  const ungrouped = { key: 'ungrouped', id: null, name: '未分组', items: [] }
  for (const conversation of conversations.value) {
    const bucket = conversation.groupId != null && byGroupId.has(conversation.groupId)
      ? byGroupId.get(conversation.groupId)
      : ungrouped
    bucket.items.push(conversation)
  }
  const result = [...buckets]
  if (ungrouped.items.length) result.push(ungrouped)
  return result
})

function isCollapsed(key) {
  return !!collapsedGroups.value[key]
}

function toggleGroup(key) {
  collapsedGroups.value = {
    ...collapsedGroups.value,
    [key]: !collapsedGroups.value[key]
  }
}

async function openConversation(id) {
  conversationId.value = id
  try {
    messages.value = await getJson(
      `/api/v1/conversations/${id}/messages?${learnerQuery()}`
    )
  } catch (error) {
    messages.value = []
    ElMessage.error(`加载会话失败：${error.message}`)
  }
}

async function deleteConversation(conversation) {
  try {
    await ElMessageBox.confirm(
      `删除后该会话的对话记录、抽取结果和 Agent 轨迹都会一并清除，且无法恢复。`,
      `删除会话「${conversation.title || '未命名会话'}」？`,
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (ignored) {
    return
  }

  try {
    await deleteJson(
      `/api/v1/conversations/${conversation.id}?${learnerQuery()}`
    )
    // 删掉的正是当前打开的会话时，清空右侧内容，避免继续引用已删除的数据
    if (conversationId.value === conversation.id) {
      newConversation()
    }
    await Promise.all([loadConversations(), loadGroups()])
    ElMessage.success('会话已删除')
  } catch (error) {
    ElMessage.error(`删除失败：${error.message}`)
  }
}

function newConversation() {
  conversationId.value = null
  messages.value = []
  citations.value = []
  agents.value = []
  memories.value = []
}

async function createGroup() {
  let value
  try {
    const result = await ElMessageBox.prompt('输入分组名称', '新建分组', {
      confirmButtonText: '创建',
      cancelButtonText: '取消',
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空'
    })
    value = result.value
  } catch (error) {
    return null
  }
  try {
    const created = await postJson(`/api/v1/conversation-groups?${learnerQuery()}`, {
      name: value.trim()
    })
    await loadGroups()
    ElMessage.success('分组已创建')
    return created.group
  } catch (error) {
    ElMessage.error(`创建分组失败：${error.message}`)
    return null
  }
}

async function renameGroup(group) {
  let value
  try {
    const result = await ElMessageBox.prompt('新的分组名称', `重命名「${group.name}」`, {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: group.name,
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空'
    })
    value = result.value
  } catch (error) {
    return
  }
  try {
    await patchJson(`/api/v1/conversation-groups/${group.id}?${learnerQuery()}`, {
      name: value.trim()
    })
    await loadGroups()
    ElMessage.success('分组已重命名')
  } catch (error) {
    ElMessage.error(`重命名失败：${error.message}`)
  }
}

async function removeGroup(group) {
  try {
    await ElMessageBox.confirm(
      `删除后组内 ${group.items.length} 个会话会回到「未分组」，会话本身不会被删除。`,
      `删除分组「${group.name}」？`,
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch (ignored) {
    return
  }
  try {
    await deleteJson(`/api/v1/conversation-groups/${group.id}?${learnerQuery()}`)
    await Promise.all([loadGroups(), loadConversations()])
    ElMessage.success('分组已删除')
  } catch (error) {
    ElMessage.error(`删除分组失败：${error.message}`)
  }
}

async function assignGroup(conversation, groupId) {
  try {
    await patchJson(`/api/v1/conversations/${conversation.id}/group?${learnerQuery()}`, {
      groupId
    })
    await Promise.all([loadConversations(), loadGroups()])
    ElMessage.success(groupId ? '已归入分组' : '已移出分组')
  } catch (error) {
    ElMessage.error(`归组失败：${error.message}`)
  }
}

async function onGroupCommand(conversation, command) {
  if (command === 'new') {
    const created = await createGroup()
    if (created) {
      await assignGroup(conversation, created.id)
    }
    return
  }
  await assignGroup(conversation, command === 'ungroup' ? null : Number(command))
}

function onConversationCreated(id) {
  conversationId.value = id
  loadConversations()
}

function onTurnDone() {
  loadConversations()
  // 异步抽取约 2s 后写状态，稍后刷新检查器，避免读到旧版本
  setTimeout(() => {
    refreshToken.value += 1
  }, 2500)
}

onMounted(async () => {
  await Promise.all([loadConversations(), loadGroups()])
})

watch([learnerId, projectId], async () => {
  newConversation()
  await Promise.all([loadConversations(), loadGroups()])
})
</script>

<template>
  <el-container class="app">
    <el-header class="header">
      <div class="brand">码忆 CodeMem</div>
      <nav class="nav">
        <a class="nav-item active" href="/web/">对话</a>
        <a class="nav-item" href="/memory.html">记忆库</a>
        <a class="nav-item" href="/instructions.html">指令</a>
        <a class="nav-item" href="/settings.html">API Key</a>
        <a class="nav-item" href="/help.html">帮助</a>
      </nav>
    </el-header>

    <el-container class="body">
      <el-aside width="240px" class="aside">
        <div class="aside-actions">
          <el-button size="small" type="primary" plain @click="newConversation">新建会话</el-button>
          <el-button size="small" @click="createGroup">新建分组</el-button>
        </div>
        <el-scrollbar>
          <div v-for="group in groupedConversations" :key="group.key" class="conv-group">
            <div class="conv-group-head" @click="toggleGroup(group.key)">
              <span class="conv-group-arrow">{{ isCollapsed(group.key) ? '▸' : '▾' }}</span>
              <span class="conv-group-name" :title="group.name">{{ group.name }}</span>
              <span class="conv-group-count">{{ group.items.length }}</span>
              <span v-if="group.id" class="conv-group-tools">
                <button class="group-tool" @click.stop="renameGroup(group)">改名</button>
                <button class="group-tool danger" @click.stop="removeGroup(group)">删除</button>
              </span>
            </div>
            <template v-if="!isCollapsed(group.key)">
              <div v-if="!group.items.length" class="conv-empty">暂无会话</div>
              <div
                v-for="conversation in group.items"
                :key="conversation.id"
                class="conv"
                :class="{ active: conversation.id === conversationId }"
                @click="openConversation(conversation.id)"
              >
                <div class="conv-head">
                  <div class="conv-title" :title="conversation.title">{{ conversation.title || '未命名会话' }}</div>
                  <div class="conv-actions" @click.stop>
                    <el-dropdown trigger="click" @command="(command) => onGroupCommand(conversation, command)">
                      <button class="conv-act" title="归入分组">归组</button>
                      <template #dropdown>
                        <el-dropdown-menu>
                          <el-dropdown-item
                            v-for="option in groups"
                            :key="option.id"
                            :command="String(option.id)"
                          >{{ option.name }}</el-dropdown-item>
                          <el-dropdown-item command="ungroup" divided>移出分组</el-dropdown-item>
                          <el-dropdown-item command="new" divided>新建分组…</el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                    <button class="conv-del" title="删除会话" @click.stop="deleteConversation(conversation)">删除</button>
                  </div>
                </div>
                <div class="conv-time">{{ conversation.updatedAt }}</div>
              </div>
            </template>
          </div>
          <div v-if="!conversations.length" class="muted">还没有会话</div>
        </el-scrollbar>
      </el-aside>

      <el-main class="main">
        <ChatPanel
          :learner-id="learnerId"
          :project-id="projectId"
          :conversation-id="conversationId"
          :initial-messages="messages"
          @update:conversation-id="onConversationCreated"
          @citations="citations = $event"
          @agents="agents = $event"
          @memories="memories = $event"
          @done="onTurnDone"
          @refresh="loadConversations"
          @memory-saved="refreshToken += 1"
        />
      </el-main>

      <el-aside width="360px" class="inspector">
        <InspectorPanel
          :learner-id="learnerId"
          :project-id="projectId"
          :citations="citations"
        />
      </el-aside>
    </el-container>
  </el-container>
</template>