<template>
  <div>
    <div class="page-title">个人中心</div>
    <div class="page-sub">头像、昵称、通知邮箱与密码管理</div>

    <div v-if="msg" class="card" style="border-color:#c98f4e"><b>{{ msg }}</b></div>
    <div v-if="err" class="err">{{ err }}</div>

    <div class="card">
      <h3>头像</h3>
      <div class="row" style="align-items:center">
        <img v-if="me?.avatar" :src="me.avatar" class="avatar-preview" alt="头像" />
        <span v-else class="avatar-preview avatar-ph">{{ (me?.nickname || '?').slice(0, 1) }}</span>
        <div>
          <input ref="fileRef" type="file" accept="image/jpeg,image/png,image/webp" style="display:none" @change="uploadAvatar" />
          <button class="btn small" :disabled="uploading" @click="fileRef?.click()">
            {{ uploading ? '上传中…' : '更换头像' }}
          </button>
          <div class="hint">支持 jpg / png / webp，不超过 2MB</div>
        </div>
      </div>
    </div>

    <div class="card">
      <h3>基本资料</h3>
      <div class="row" style="align-items:center">
        <div>
          <span class="label">昵称</span>
          <input v-model="nickname" maxlength="20" />
        </div>
        <button class="btn small" :disabled="savingNick" @click="saveNickname">保存昵称</button>
      </div>
      <div class="row" style="align-items:center;margin-top:12px">
        <div>
          <span class="label">通知邮箱</span>
          <input v-model="email" placeholder="接收复习提醒邮件" />
        </div>
        <button class="btn small" :disabled="savingEmail" @click="saveEmail">保存邮箱</button>
      </div>
      <div class="hint" style="margin-top:8px">用户名：{{ me?.username }}（不可修改）</div>
    </div>

    <div class="card">
      <h3>我的数据导出</h3>
      <div class="row" style="gap:12px">
        <button class="btn small" :disabled="exporting" @click="exportData('/api/export/practice.xlsx', '练习记录.xlsx')">
          {{ exporting === 'practice' ? '导出中…' : '导出练习记录 (Excel)' }}
        </button>
        <button class="btn small" :disabled="exporting" @click="exportData('/api/export/exams.xlsx', '考试成绩.xlsx')">
          {{ exporting === 'exams' ? '导出中…' : '导出考试成绩 (Excel)' }}
        </button>
      </div>
    </div>

    <div class="card">
      <h3>修改密码</h3>
      <div class="row" style="align-items:center">
        <div><span class="label">原密码</span><input v-model="oldPwd" type="password" /></div>
        <div><span class="label">新密码</span><input v-model="newPwd" type="password" placeholder="至少 6 位" /></div>
        <div><span class="label">确认新密码</span><input v-model="newPwd2" type="password" /></div>
        <button class="btn small" :disabled="savingPwd" @click="savePassword">修改密码</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api, getToken, downloadFile } from '../api'

defineOptions({ name: 'ProfileView' })

const me = ref(null)
const nickname = ref('')
const email = ref('')
const oldPwd = ref('')
const newPwd = ref('')
const newPwd2 = ref('')
const fileRef = ref(null)
const msg = ref('')
const err = ref('')
const uploading = ref(false)
const savingNick = ref(false)
const savingEmail = ref(false)
const savingPwd = ref(false)
const exporting = ref('')

const exportData = async (path, filename) => {
  exporting.value = path.includes('exams') ? 'exams' : 'practice'
  try {
    await downloadFile(path, filename)
    flash('已导出 ' + filename)
  } catch (e) {
    err.value = e.message
  } finally {
    exporting.value = ''
  }
}

const loadMe = async () => {
  me.value = await api('/api/auth/me')
  nickname.value = me.value.nickname || ''
  email.value = me.value.email || ''
}

const flash = (t) => {
  msg.value = t
  setTimeout(() => { msg.value = '' }, 2500)
}

const saveNickname = async () => {
  savingNick.value = true
  err.value = ''
  try {
    await api('/api/auth/me/profile', { method: 'PUT', body: { nickname: nickname.value } })
    await loadMe()
    flash('昵称已保存')
  } catch (e) {
    err.value = e.message
  } finally {
    savingNick.value = false
  }
}

const saveEmail = async () => {
  savingEmail.value = true
  err.value = ''
  try {
    await api('/api/auth/me/email', { method: 'PUT', body: { email: email.value } })
    await loadMe()
    flash('邮箱已保存')
  } catch (e) {
    err.value = e.message
  } finally {
    savingEmail.value = false
  }
}

const savePassword = async () => {
  if (newPwd.value !== newPwd2.value) {
    err.value = '两次输入的新密码不一致'
    return
  }
  savingPwd.value = true
  err.value = ''
  try {
    await api('/api/auth/me/password', {
      method: 'PUT',
      body: { oldPassword: oldPwd.value, newPassword: newPwd.value }
    })
    oldPwd.value = newPwd.value = newPwd2.value = ''
    flash('密码已修改')
  } catch (e) {
    err.value = e.message
  } finally {
    savingPwd.value = false
  }
}

const uploadAvatar = async (ev) => {
  const file = ev.target.files?.[0]
  ev.target.value = ''
  if (!file) return
  uploading.value = true
  err.value = ''
  try {
    const fd = new FormData()
    fd.append('file', file)
    const res = await fetch('/api/auth/me/avatar', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + getToken() },
      body: fd
    })
    const json = await res.json().catch(() => null)
    if (!res.ok || (json && json.code !== 0)) {
      throw new Error((json && json.message) || '上传失败(' + res.status + ')')
    }
    await loadMe()
    flash('头像已更新')
  } catch (e) {
    err.value = e.message
  } finally {
    uploading.value = false
  }
}

onMounted(loadMe)
</script>

<style scoped>
.avatar-preview {
  width: 72px; height: 72px; border-radius: 18px; object-fit: cover;
  border: 1px solid var(--border); background: var(--soft);
}
.avatar-ph {
  display: flex; align-items: center; justify-content: center;
  font-size: 30px; font-weight: 800; color: var(--primary);
}
.hint { font-size: 12px; color: var(--muted); }
</style>
