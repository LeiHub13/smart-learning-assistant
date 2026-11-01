<template>
  <div class="profile-page">
    <nav class="profile-tabs" aria-label="个人中心" role="tablist">
      <button v-for="tab in tabs" :key="tab.id" type="button" role="tab"
              :aria-selected="activeTab === tab.id" :class="{ selected: activeTab === tab.id }"
              @click="activeTab = tab.id">
        <AppIcon :name="tab.icon" :size="16" />{{ tab.label }}
      </button>
    </nav>

    <div class="profile-layout">
      <aside class="identity">
        <div class="identity-avatar-wrap">
          <img v-if="me?.avatar" :src="me.avatar" class="identity-avatar" alt="个人头像" />
          <div v-else class="identity-avatar avatar-placeholder">{{ avatarChar }}</div>
          <button class="avatar-action" type="button" :disabled="uploading" title="更换头像" aria-label="更换头像" @click="fileRef?.click()">
            <AppIcon name="camera" :size="16" />
          </button>
          <input ref="fileRef" type="file" accept="image/jpeg,image/png,image/webp" class="visually-hidden"
                 tabindex="-1" aria-hidden="true" @change="uploadAvatar" />
        </div>
        <div class="identity-name">
          <h1>{{ me?.nickname || me?.username || '个人中心' }}</h1>
          <p v-if="me?.username">{{ me.username }}</p>
        </div>
        <button class="btn identity-edit" type="button" @click="activeTab = 'profile'">编辑个人资料</button>
        <div class="identity-meta">
          <div><AppIcon name="mail" :size="16" /><span>{{ me?.email || '尚未绑定邮箱' }}</span></div>
          <div v-if="study"><AppIcon name="clock" :size="16" /><span>{{ studyPeriod }}学习 {{ formatDuration(study.totalMinutes) }}</span></div>
        </div>
        <div class="identity-section">
          <h2>我的学习</h2>
          <div class="identity-stats">
            <span><b>{{ study ? study.activeDays : '—' }}</b> 天活跃</span>
            <span><b>{{ practiceSummary ? practiceSummary.total : '—' }}</b> 次练习</span>
          </div>
        </div>
        <router-link class="billing-link" to="/billing">
          <span><AppIcon name="sparkles" :size="16" />AI 次数与套餐</span>
          <span class="billing-hint">查看 →</span>
        </router-link>
      </aside>

      <div class="profile-content">
        <div v-if="msg" class="profile-message success-message" role="status">{{ msg }}</div>
        <div v-if="err" class="profile-message error-message" role="alert">{{ err }}</div>

        <section v-if="activeTab === 'overview'" role="tabpanel" aria-label="概览">
          <div class="section-heading">
            <div><h2>我的课程</h2><p>在这里继续你的学习</p></div>
            <router-link class="section-link" to="/manage">查看全部课程</router-link>
          </div>
          <div v-if="coursesLoading" class="empty-box">正在加载课程…</div>
          <div v-else-if="coursesError" class="empty-box">课程加载失败，请稍后重试。</div>
          <div v-else-if="!courses.length" class="empty-box">
            还没有课程，<router-link to="/manage">创建课程</router-link> 或到 <router-link to="/hub">课程 Hub</router-link> 看看。
          </div>
          <div v-else class="course-grid">
            <router-link v-for="course in courses.slice(0, 6)" :key="course.id" class="course-tile"
                         :to="{ path: '/manage', query: { courseId: course.id } }">
              <div class="course-title"><AppIcon name="manage" :size="16" /><strong>{{ course.name }}</strong><span>课程</span></div>
              <p>{{ course.description || '暂无课程简介' }}</p>
              <div class="course-meta"><span><i></i>{{ course.docCount ?? 0 }} 篇文档</span><span>{{ course.questionCount ?? 0 }} 道题目</span></div>
            </router-link>
          </div>

          <div class="section-heading activity-heading">
            <div><h2>学习活跃记录</h2><p v-if="study">{{ studyPeriod }}有 {{ study.activeDays }} 天在学习</p><p v-else>查看近一年的学习足迹</p></div>
            <span class="period-label">{{ studyPeriod }}</span>
          </div>
          <div class="heat-panel">
            <div v-if="studyLoading" class="empty-inner">正在加载学习记录…</div>
            <div v-else-if="studyError" class="empty-inner">学习记录加载失败，请稍后重试。</div>
            <template v-else-if="study">
              <div class="heat-scroll"><StudyHeatmap class="profile-heatmap" :calendar="study.calendar || []" /></div>
              <div class="heat-summary">
                <div><b>{{ study.activeDays }}</b><span>活跃天数</span></div>
                <div><b>{{ formatDuration(study.totalMinutes) }}</b><span>{{ studyPeriod }}学习时长</span></div>
                <div><b>{{ bestStreak }} 天</b><span>最长连续学习</span></div>
              </div>
            </template>
          </div>

          <div class="section-heading activity-heading">
            <div><h2>最近练习</h2><p>记录每一次学习进步</p></div>
            <router-link class="section-link" to="/practice">查看全部</router-link>
          </div>
          <div v-if="practiceLoading" class="empty-box">正在加载练习记录…</div>
          <div v-else-if="practiceError" class="empty-box">练习记录加载失败，请稍后重试。</div>
          <div v-else-if="!practiceSummary?.records?.length" class="empty-box">还没有练习记录，<router-link to="/practice">开始第一次练习</router-link>。</div>
          <div v-else class="practice-list">
            <div v-for="record in practiceSummary.records" :key="record.id" class="practice-item">
              <span class="practice-icon"><AppIcon name="practice" :size="15" /></span>
              <div class="practice-body"><b>{{ record.title || '练习记录' }}</b><span>{{ record.courseName || '未关联课程' }} · {{ formatDate(record.createdAt) }}</span></div>
              <span v-if="record.score != null && record.totalScore != null" class="practice-score">{{ record.score }} / {{ record.totalScore }} 分</span>
            </div>
          </div>
        </section>

        <section v-else-if="activeTab === 'profile'" class="settings" role="tabpanel" aria-label="资料设置">
          <div class="settings-heading"><h2>个人资料</h2><p>管理你在学习助手中的个人信息</p></div>
          <div class="settings-card">
            <div class="settings-title"><h3>头像</h3><p>支持 JPG、PNG 和 WebP，不超过 2MB</p></div>
            <button class="btn small" type="button" :disabled="uploading" @click="fileRef?.click()">{{ uploading ? '上传中…' : '更换头像' }}</button>
          </div>
          <div class="settings-card">
            <div class="settings-title"><h3>基本资料</h3><p>这些信息会显示在你的个人中心</p></div>
            <div class="setting-field">
              <label for="profile-username">用户名</label>
              <div class="field-control">
                <input id="profile-username" v-model="username" type="text" maxlength="32" :disabled="isAdminAccount" placeholder="登录账号，全局唯一" />
                <button class="btn small" aria-label="保存用户名" :disabled="savingUsername || isAdminAccount" @click="saveUsername">保存</button>
              </div>
              <p v-if="isAdminAccount" class="field-note">管理员账号不允许修改用户名</p>
            </div>
            <div class="setting-field">
              <label for="profile-nickname">昵称</label>
              <div class="field-control">
                <input id="profile-nickname" v-model="nickname" type="text" maxlength="20" placeholder="展示在页面上的名字" />
                <button class="btn small" aria-label="保存昵称" :disabled="savingNick" @click="saveNickname">保存</button>
              </div>
            </div>
            <div class="setting-field">
              <label for="profile-email">通知邮箱</label>
              <div class="field-control">
                <input id="profile-email" v-model="email" type="email" placeholder="用于接收复习提醒" />
                <button class="btn small" aria-label="保存邮箱" :disabled="savingEmail" @click="saveEmail">保存</button>
              </div>
            </div>
          </div>
        </section>

        <section v-else class="settings" role="tabpanel" aria-label="账号与安全">
          <div class="settings-heading"><h2>账号与安全</h2><p>管理密码、通知和个人数据</p></div>
          <div class="settings-card">
            <div class="settings-title"><h3>修改密码</h3><p>你可以使用原密码或邮箱验证码修改密码</p></div>
            <div class="password-fields">
              <div v-if="!pwdByEmail" class="setting-field">
                <label for="old-password">原密码</label>
                <input id="old-password" v-model="oldPwd" type="password" autocomplete="current-password" />
              </div>
              <template v-else>
                <div class="setting-field">
                  <label for="reset-email">绑定邮箱</label>
                  <input id="reset-email" :value="me?.email || '未绑定'" type="email" disabled />
                </div>
                <div class="setting-field">
                  <label for="reset-code">邮箱验证码</label>
                  <div class="field-control">
                    <input id="reset-code" v-model="emailCode" type="text" maxlength="6" placeholder="6 位验证码" />
                    <button class="btn small" :disabled="countdown > 0 || sendingCode" @click="sendResetCode">{{ countdown > 0 ? countdown + 's 后重发' : '发送验证码' }}</button>
                  </div>
                </div>
              </template>
              <div class="setting-field">
                <label for="new-password">新密码</label>
                <input id="new-password" v-model="newPwd" type="password" autocomplete="new-password" placeholder="至少 6 位" />
              </div>
              <div class="setting-field">
                <label for="confirm-password">确认新密码</label>
                <input id="confirm-password" v-model="newPwd2" type="password" autocomplete="new-password" />
              </div>
            </div>
            <div class="settings-actions">
              <button class="btn small" :disabled="savingPwd" @click="pwdByEmail ? savePasswordByEmail() : savePassword()">{{ pwdByEmail ? '确认修改' : '修改密码' }}</button>
              <button class="text-button" @click="togglePwdMode">{{ pwdByEmail ? '返回原密码方式' : '忘记原密码？邮箱验证码修改' }}</button>
            </div>
            <p v-if="pwdByEmail && !me?.email" class="field-note">尚未绑定邮箱，请先到资料设置中保存邮箱。</p>
          </div>
          <div class="settings-card">
            <div class="settings-title"><h3>通知邮件偏好</h3><p>勾选的类型会发送邮件；站内通知不受影响。</p></div>
            <div class="mute-grid">
              <label v-for="(lab, t) in NOTIFY_TYPES" :key="t" class="mute-item">
                <input type="checkbox" :checked="!mutedTypes.has(t)" @change="toggleMute(t)" />{{ lab }}
              </label>
            </div>
            <div class="settings-actions">
              <button class="btn small" :disabled="savingMute" @click="saveMute">保存偏好</button>
              <span class="field-note">{{ mailMute ? '不收邮件：' + mailMuteLabels : '全部类型接收邮件' }}</span>
            </div>
          </div>
          <div class="settings-card">
            <div class="settings-title"><h3>我的数据导出</h3><p>将你的练习记录或考试成绩导出为 Excel</p></div>
            <div class="settings-actions">
              <button class="btn small" :disabled="!!exporting" @click="exportData('/api/export/practice.xlsx', '练习记录.xlsx')">{{ exporting === 'practice' ? '导出中…' : '导出练习记录' }}</button>
              <button class="btn small" :disabled="!!exporting" @click="exportData('/api/export/exams.xlsx', '考试成绩.xlsx')">{{ exporting === 'exams' ? '导出中…' : '导出考试成绩' }}</button>
            </div>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onActivated, onUnmounted } from 'vue'
import { api, getToken, downloadFile } from '../api'
import AppIcon from '../components/AppIcon.vue'
import StudyHeatmap from '../components/StudyHeatmap.vue'

defineOptions({ name: 'ProfileView' })

const tabs = [
  { id: 'overview', label: '概览', icon: 'manage' },
  { id: 'profile', label: '资料设置', icon: 'profile' },
  { id: 'security', label: '账号与安全', icon: 'lock' }
]
const activeTab = ref('overview')
const courses = ref([])
const study = ref(null)
const practiceSummary = ref(null)
const coursesLoading = ref(true)
const studyLoading = ref(true)
const practiceLoading = ref(true)
const coursesError = ref(false)
const studyError = ref(false)
const practiceError = ref(false)
let overviewRequest = 0

const me = ref(null)
const username = ref('')
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
const savingUsername = ref(false)
const savingEmail = ref(false)
const savingPwd = ref(false)
const exporting = ref('')
const pwdByEmail = ref(false)
const emailCode = ref('')
const sendingCode = ref(false)
const countdown = ref(0)
let timer = null

const avatarChar = computed(() => Array.from((me.value?.nickname || me.value?.username || '?').trim())[0] || '?')
const studyPeriod = computed(() => {
  const days = study.value?.calendar?.length
  return !days || days >= 365 ? '近一年' : `近 ${days} 天`
})
const bestStreak = computed(() => {
  let current = 0
  let best = 0
  for (const day of study.value?.calendar || []) {
    current = day.minutes > 0 ? current + 1 : 0
    best = Math.max(best, current)
  }
  return best
})
const formatDuration = (value) => {
  const minutes = Math.max(0, Number(value) || 0)
  if (minutes < 60) return minutes + ' 分钟'
  const hours = Math.floor(minutes / 60)
  return hours + ' 小时' + (minutes % 60 ? ' ' + minutes % 60 + ' 分钟' : '')
}
const formatDate = (value) => {
  if (!value) return '日期未知'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '日期未知'
  return `${date.getFullYear()} 年 ${date.getMonth() + 1} 月 ${date.getDate()} 日`
}

const loadOverview = async () => {
  const request = ++overviewRequest
  coursesLoading.value = studyLoading.value = practiceLoading.value = true
  const [courseResult, studyResult, practiceResult] = await Promise.allSettled([
    api('/api/courses'),
    api('/api/study/summary?days=365'),
    api('/api/practice/history?size=4')
  ])
  if (request !== overviewRequest) return
  coursesError.value = courseResult.status === 'rejected' || !Array.isArray(courseResult.value)
  studyError.value = studyResult.status === 'rejected' || !Array.isArray(studyResult.value?.calendar)
  practiceError.value = practiceResult.status === 'rejected' || !practiceResult.value || !Array.isArray(practiceResult.value.records)
  courses.value = coursesError.value ? [] : courseResult.value
  study.value = studyError.value ? null : studyResult.value
  practiceSummary.value = practiceError.value ? null : practiceResult.value
  coursesLoading.value = studyLoading.value = practiceLoading.value = false
}

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
  username.value = me.value.username || ''
  nickname.value = me.value.nickname || ''
  email.value = me.value.email || ''
  mailMute.value = me.value.mailMute || ''
  window.dispatchEvent(new CustomEvent('profile-updated', { detail: me.value }))
}

/* 勾选表示接收；未勾选的类型保存在 mailMute 清单中。 */
const NOTIFY_TYPES = {
  review: '复习提醒', plan: '学习计划', system: '系统通知', exam: '考试',
  practice: '练习', dm: '私信', friend: '好友', announcement: '公告',
}
const mailMute = ref('')
const savingMute = ref(false)
const mutedTypes = computed(() => new Set(mailMute.value.split(',').map((t) => t.trim()).filter(Boolean)))

const toggleMute = (t) => {
  const set = new Set(mailMute.value.split(',').map((x) => x.trim()).filter(Boolean))
  set.has(t) ? set.delete(t) : set.add(t)
  mailMute.value = [...set].join(',')
}

const mailMuteLabels = computed(() =>
  mailMute.value.split(',').map((t) => NOTIFY_TYPES[t.trim()] || t.trim()).filter(Boolean).join('、'))

const saveMute = async () => {
  savingMute.value = true
  err.value = ''
  try {
    await api('/api/auth/me/notify-pref', { method: 'PUT', body: { mailMute: mailMute.value } })
    msg.value = '通知邮件偏好已保存'
    setTimeout(() => (msg.value = ''), 2500)
    await loadMe()
  } catch (e) {
    err.value = e.message
  } finally {
    savingMute.value = false
  }
}

const isAdminAccount = computed(() => me.value?.username === 'admin')

const saveUsername = async () => {
  const v = username.value.trim()
  if (!v) {
    err.value = '用户名不能为空'
    return
  }
  if (v === me.value?.username) {
    err.value = '新用户名与当前用户名相同'
    return
  }
  username.value = v
  savingUsername.value = true
  err.value = ''
  try {
    await api('/api/auth/me/username', { method: 'PUT', body: { username: v } })
    await loadMe()
    flash('用户名已保存，下次登录请使用新用户名')
  } catch (e) {
    err.value = e.message
  } finally {
    savingUsername.value = false
  }
}

const flash = (t) => {
  msg.value = t
  setTimeout(() => { msg.value = '' }, 2500)
}

const saveNickname = async () => {
  const v = nickname.value.trim()
  if (!v) {
    err.value = '昵称不能为空'
    return
  }
  nickname.value = v
  savingNick.value = true
  err.value = ''
  try {
    await api('/api/auth/me/profile', { method: 'PUT', body: { nickname: v } })
    await loadMe()
    flash('昵称已保存')
  } catch (e) {
    err.value = e.message
  } finally {
    savingNick.value = false
  }
}

const saveEmail = async () => {
  const v = email.value.trim()
  if (v && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v)) {
    err.value = '邮箱格式不正确'
    return
  }
  email.value = v
  savingEmail.value = true
  err.value = ''
  try {
    await api('/api/auth/me/email', { method: 'PUT', body: { email: v } })
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

const togglePwdMode = () => {
  pwdByEmail.value = !pwdByEmail.value
  err.value = ''
  oldPwd.value = emailCode.value = newPwd.value = newPwd2.value = ''
}

const sendResetCode = async () => {
  if (!me.value?.email) {
    err.value = '尚未绑定邮箱，请先在资料设置中保存邮箱'
    return
  }
  sendingCode.value = true
  err.value = ''
  try {
    await api('/api/auth/email-code', {
      method: 'POST',
      body: { email: me.value.email, scene: 'reset_password' }
    })
    flash('验证码已发送至 ' + me.value.email + '，10 分钟内有效')
    countdown.value = 60
    if (timer) clearInterval(timer)
    timer = setInterval(() => {
      countdown.value--
      if (countdown.value <= 0) {
        clearInterval(timer)
        timer = null
      }
    }, 1000)
  } catch (e) {
    err.value = e.message
  } finally {
    sendingCode.value = false
  }
}

const savePasswordByEmail = async () => {
  if (!me.value?.email) {
    err.value = '尚未绑定邮箱，无法使用验证码修改密码'
    return
  }
  if (!emailCode.value.trim()) {
    err.value = '请填写邮箱验证码'
    return
  }
  if (newPwd.value.length < 6) {
    err.value = '新密码至少 6 位'
    return
  }
  if (newPwd.value !== newPwd2.value) {
    err.value = '两次输入的新密码不一致'
    return
  }
  savingPwd.value = true
  err.value = ''
  try {
    await api('/api/auth/reset-password', {
      method: 'POST',
      body: { email: me.value.email, code: emailCode.value.trim(), newPassword: newPwd.value }
    })
    emailCode.value = newPwd.value = newPwd2.value = ''
    togglePwdMode()
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

const refreshProfile = () => {
  loadMe().catch((e) => { err.value = e.message })
  loadOverview()
}
let hasActivated = false
onMounted(refreshProfile)
onActivated(() => {
  if (hasActivated) refreshProfile()
  hasActivated = true
})
onUnmounted(() => timer && clearInterval(timer))
</script>

<style scoped>
.profile-page { max-width: 1440px; min-height: 680px; margin: 0 auto; padding: 0 28px 38px; background: var(--bg); border: 1px solid var(--border); border-radius: 8px; }
.profile-tabs { display: flex; gap: 4px; overflow-x: auto; border-bottom: 1px solid var(--border); }
.profile-tabs button { display: inline-flex; align-items: center; gap: 8px; flex: none; min-height: 57px; padding: 0 16px; border: 0; border-bottom: 2px solid transparent; background: none; color: var(--muted); font: inherit; cursor: pointer; }
.profile-tabs button:hover { color: var(--text); background: var(--soft); }
.profile-tabs button.selected { color: var(--text); border-bottom-color: #fd8c73; font-weight: 600; }
.profile-layout { display: grid; grid-template-columns: minmax(190px, 250px) minmax(0, 1fr); gap: 30px; padding-top: 28px; }
.identity { min-width: 0; }
.identity-avatar-wrap { position: relative; width: min(100%, 238px); }
.identity-avatar { display: flex; width: 100%; aspect-ratio: 1; object-fit: cover; align-items: center; justify-content: center; border-radius: 50%; border: 1px solid var(--border); background: var(--soft); }
.avatar-placeholder { color: var(--primary); font-size: clamp(48px, 7vw, 82px); font-weight: 600; }
.avatar-action { position: absolute; right: 3px; bottom: 15px; display: grid; place-items: center; width: 36px; height: 36px; background: var(--bg); color: var(--muted); border: 1px solid var(--border); border-radius: 50%; cursor: pointer; box-shadow: 0 1px 2px rgba(31,35,40,.08); }
.avatar-action:hover { color: var(--primary); background: var(--soft); }
.avatar-action:disabled { opacity: .6; cursor: wait; }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
.identity-name { margin: 17px 0 15px; overflow-wrap: anywhere; }
.identity-name h1 { font-size: 24px; line-height: 1.3; font-weight: 600; }
.identity-name p { margin-top: 3px; font-size: 17px; line-height: 1.3; color: var(--muted); }
.identity-edit { width: 100%; font-weight: 600; }
.identity-meta { display: grid; gap: 11px; padding: 20px 0; border-bottom: 1px solid var(--border); }
.identity-meta > div { display: flex; align-items: flex-start; gap: 8px; color: var(--muted); font-size: 13px; line-height: 1.45; overflow-wrap: anywhere; }
.identity-section { padding: 20px 0; border-bottom: 1px solid var(--border); }
.identity-section h2 { margin-bottom: 12px; font-size: 14px; }
.identity-stats { display: flex; flex-wrap: wrap; gap: 6px 18px; color: var(--muted); font-size: 13px; }
.identity-stats b { color: var(--text); }
.billing-link { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 18px 0; color: var(--text); text-decoration: none; font-size: 13px; }
.billing-link > span:first-child { display: flex; align-items: center; gap: 8px; }
.billing-link:hover, .section-link:hover { color: var(--primary); }
.billing-hint { flex: none; color: var(--muted); font-size: 12px; }
.profile-content { min-width: 0; }
.profile-message { padding: 11px 14px; margin-bottom: 16px; border: 1px solid; border-radius: 6px; font-size: 13px; }
.success-message { color: var(--ok-strong); border-color: var(--ok-line); background: var(--ok-soft); }
.error-message { color: var(--danger); border-color: var(--bad-line); background: var(--bad-soft); }
.section-heading { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; margin-bottom: 12px; }
.section-heading h2, .settings-heading h2 { font-size: 17px; font-weight: 600; line-height: 1.35; }
.section-heading p, .settings-heading p { margin-top: 5px; font-size: 12px; color: var(--muted); }
.section-link { color: var(--primary); text-decoration: none; font-size: 12px; white-space: nowrap; }
.course-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.course-tile { min-width: 0; min-height: 124px; display: flex; flex-direction: column; padding: 15px 16px; border: 1px solid var(--border); border-radius: 6px; color: inherit; text-decoration: none; }
.course-tile:hover { background: var(--soft); border-color: var(--muted); }
.course-title { display: flex; min-width: 0; align-items: center; gap: 7px; color: var(--muted); }
.course-title strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--primary); font-size: 14px; font-weight: 600; }
.course-title span { flex: none; padding: 1px 7px; border: 1px solid var(--border); border-radius: 12px; font-size: 11px; }
.course-tile p { min-height: 36px; margin: 9px 0 12px; overflow: hidden; color: var(--muted); font-size: 12px; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.course-meta { display: flex; flex-wrap: wrap; gap: 8px 18px; margin-top: auto; color: var(--muted); font-size: 12px; }
.course-meta span { display: inline-flex; align-items: center; gap: 5px; }
.course-meta i { width: 9px; height: 9px; background: var(--success); border-radius: 50%; }
.activity-heading { margin-top: 28px; }
.period-label { color: var(--muted); font-size: 12px; white-space: nowrap; }
.heat-panel { border: 1px solid var(--border); border-radius: 6px; }
.heat-scroll { overflow-x: auto; padding: 18px 18px 12px; }
.profile-heatmap { --shm-size: 11px; --shm-gap: 2px; width: max-content; min-width: 100%; }
.heat-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); border-top: 1px solid var(--border); }
.heat-summary > div { display: flex; flex-direction: column; gap: 5px; padding: 16px 18px; }
.heat-summary > div + div { border-left: 1px solid var(--border); }
.heat-summary b { font-size: 15px; }
.heat-summary span { color: var(--muted); font-size: 12px; }
.empty-box, .empty-inner { padding: 26px 18px; border: 1px dashed var(--border); border-radius: 6px; color: var(--muted); text-align: center; font-size: 13px; line-height: 1.7; }
.empty-inner { border: 0; }
.empty-box a { color: var(--primary); text-decoration: none; }
.practice-list { border-top: 1px solid var(--border); }
.practice-item { display: flex; align-items: center; gap: 12px; min-height: 67px; padding: 12px 0; border-bottom: 1px solid var(--border); }
.practice-icon { display: grid; place-items: center; flex: none; width: 32px; height: 32px; border-radius: 50%; background: var(--soft); color: var(--muted); }
.practice-body { display: flex; flex: 1; min-width: 0; flex-direction: column; gap: 5px; }
.practice-body b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; font-weight: 600; }
.practice-body span, .practice-score { color: var(--muted); font-size: 12px; }
.practice-score { flex: none; }
.settings { max-width: 760px; }
.settings-heading { margin-bottom: 22px; }
.settings-card { margin-bottom: 14px; padding: 20px; border: 1px solid var(--border); border-radius: 6px; }
.settings-title { margin-bottom: 18px; }
.settings-title h3 { font-size: 15px; font-weight: 600; }
.settings-title p { margin-top: 5px; color: var(--muted); font-size: 12px; line-height: 1.5; }
.setting-field { max-width: 480px; margin-top: 16px; }
.setting-field label { display: block; margin-bottom: 6px; font-size: 13px; font-weight: 600; }
.field-control { display: flex; align-items: center; gap: 10px; }
.field-control input { flex: 1; min-width: 0; }
.field-note { margin-top: 6px; color: var(--muted); font-size: 12px; line-height: 1.5; }
.settings-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 18px; }
.settings-actions .field-note { margin: 0; }
.text-button { padding: 4px 0; border: 0; background: none; color: var(--primary); font-size: 12px; cursor: pointer; }
.text-button:hover { text-decoration: underline; }
.mute-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(116px, 1fr)); gap: 12px; }
.mute-item { display: inline-flex; align-items: center; gap: 7px; font-size: 13px; cursor: pointer; }
.mute-item input { width: auto; cursor: pointer; }
@media (max-width: 900px) { .profile-page { padding: 0 20px 28px; } .profile-layout { grid-template-columns: 180px minmax(0, 1fr); gap: 22px; } .course-grid { grid-template-columns: 1fr; } }
@media (max-width: 650px) {
  .profile-page { padding: 0 16px 24px; }
  .profile-tabs button { min-height: 50px; padding: 0 10px; font-size: 13px; }
  .profile-layout { display: block; padding-top: 18px; }
  .identity { display: grid; grid-template-columns: 76px minmax(0, 1fr); gap: 0 14px; margin-bottom: 26px; }
  .identity-avatar-wrap { grid-row: span 2; width: 76px; }
  .avatar-placeholder { font-size: 30px; }
  .avatar-action { right: -2px; bottom: -2px; width: 28px; height: 28px; }
  .identity-name { align-self: end; margin: 0 0 8px; }
  .identity-name h1 { font-size: 20px; }
  .identity-name p { font-size: 14px; }
  .identity-edit { align-self: start; }
  .identity-meta, .identity-section, .billing-link { grid-column: 1 / -1; }
  .identity-meta { padding: 16px 0; }
  .identity-section { padding: 14px 0; }
  .billing-link { padding: 14px 0 0; }
  .section-heading { align-items: center; }
  .heat-summary > div { padding: 12px 9px; }
  .heat-summary b { font-size: 13px; }
  .heat-summary span { font-size: 11px; }
  .settings-card { padding: 16px; }
}
</style>
