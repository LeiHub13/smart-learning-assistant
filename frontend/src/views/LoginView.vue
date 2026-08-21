<template>
  <div class="login">
    <div class="intro">
      <div class="intro-in">
        <div class="brand"><i></i>智学助手</div>
        <div class="slogan">基于大语言模型的智能学习助手</div>
        <div class="feats">
          <div class="feat" v-for="f in feats" :key="f.t">
            <div class="ft">{{ f.t }}</div>
            <div class="fd">{{ f.d }}</div>
          </div>
        </div>
        <div class="arch">Java 业务骨架 · Python langchain AI 服务 · 在线大模型（DeepSeek）</div>
      </div>
    </div>
    <div class="box">
      <h1>{{ mode === 'login' ? '欢迎登录' : '创建账号' }}</h1>
      <div class="sub">智学助手 · 登录 / 注册</div>
      <div v-if="error" class="err">{{ error }}</div>
      <div class="f">
        <span class="label">用户名</span>
        <input v-model="form.username" type="text" placeholder="输入用户名，如 xiaoming" />
      </div>
      <div class="f">
        <span class="label">密码</span>
        <input v-model="form.password" type="password" placeholder="123456" @keyup.enter="submit" />
      </div>
      <button class="btn" style="width:100%" :disabled="loading" @click="submit">
        <i v-if="loading" style="width:13px;height:13px;border:2px solid rgba(255,255,255,.4);border-top-color:#fff;border-radius:50%;animation:bootsp .8s linear infinite"></i>
        {{ mode === 'login' ? '登 录' : '注 册' }}
      </button>
      <div class="sw">
        <template v-if="mode === 'login'">还没有账号？<a @click="mode = 'register'">去注册</a></template>
        <template v-else>已有账号？<a @click="mode = 'login'">去登录</a></template>
      </div>
      <div class="tips">
        <b>演示账号</b>（密码均为 123456）：<br />
        xiaoming（小明） · xiaohong（小红） · xiaoyu（小宇）
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, setToken } from '../api'

const router = useRouter()
const mode = ref('login')
const loading = ref(false)
const error = ref('')
const form = reactive({ username: '', password: '' })

const feats = [
  { t: 'AI 答疑', d: '基于课程知识库检索（RAG）作答，回答标注引用来源，支持多轮对话与记忆' },
  { t: '讲义与题目生成', d: '一键生成课程讲义、练习题，题目自动入库供练习使用' },
  { t: '智能批改', d: '主观题由大模型按评分标准打分并逐题点评，客观题自动判分' },
  { t: '学情分析', d: '知识点掌握度追踪，AI 生成个性化复习建议' },
]

const submit = async () => {
  if (!form.username || !form.password) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const path = mode.value === 'login' ? '/api/auth/login' : '/api/auth/register'
    const data = await api(path, { method: 'POST', body: form })
    setToken(data.accessToken || data.token)
    const target = router.currentRoute.value.query.redirect || '/chat'
    router.replace(target)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>