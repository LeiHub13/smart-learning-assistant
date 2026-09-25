<template>
  <article class="card pk" :class="{ 'pk-active': active, 'pk-click': clickable }"
           :tabindex="clickable ? '0' : null" :role="clickable ? 'button' : null"
           :aria-pressed="clickable ? String(!!active) : null"
           @click="onCardClick" @keydown.enter.prevent="onCardClick" @keydown.space.prevent="onCardClick">
    <div class="pk-top">
      <span class="pk-kind">课程</span>
      <span v-if="course.role === 'creator'" class="tag ok">我创建的</span>
      <span v-else-if="course.enrolled" class="tag">我加入的</span>
      <span v-if="course.inHub" class="tag">已入驻 Hub</span>
      <div class="pk-actions"><slot name="actions" /></div>
    </div>

    <h3 class="pk-name">{{ course.name }}</h3>
    <p class="pk-desc">{{ course.description || '暂无简介' }}</p>

    <div class="pk-foot">
      <span v-if="course.ownerName" class="pk-owner">
        <i class="pk-dot" />{{ course.ownerName }}
      </span>
      <span v-if="course.memberCount != null">👥 {{ course.memberCount }} 人加入</span>
      <span>📚 知识库 {{ course.kbCount || 0 }}</span>
      <span>📄 文档 {{ course.docCount || 0 }}</span>
      <span>✂️ 片段 {{ course.chunkCount || 0 }}</span>
      <span>✏️ 题库 {{ course.questionCount || 0 }}</span>
    </div>
  </article>
</template>

<script setup>
defineOptions({ name: 'CourseCard' })

const props = defineProps({
  course: { type: Object, required: true },
  active: { type: Boolean, default: false },
  /** 整卡可点（用于「选中展开详情」）；关掉时只保留卡片内的操作按钮 */
  clickable: { type: Boolean, default: true },
})

const emit = defineEmits(['open'])

const onCardClick = () => {
  if (props.clickable) emit('open', props.course)
}
</script>

<style scoped>
/* GitHub pinned 风格：紧凑卡片 + 等宽网格 + 顶部分类标签 + 底部弱化的统计行 */
.pk {
  min-width: 0;
  padding: 16px 18px 14px;
  margin-bottom: 0;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  transition: border-color .2s ease, box-shadow .2s ease, transform .2s ease;
}
.pk:hover { transform: translateY(-2px); border-color: #d8dce8; }
.pk-click { cursor: pointer; }
.pk-click:focus-visible { outline: 2px solid rgba(79, 70, 229, .6); outline-offset: 2px; }
.pk-active, .pk-active:hover {
  border-color: var(--accent);
  box-shadow: inset 0 0 0 1px var(--accent), 0 2px 4px rgba(31,35,60,.04), 0 16px 40px rgba(31,35,60,.08);
}

.pk-top { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 10px; }
.pk-kind {
  font-size: 11px; letter-spacing: .04em; color: var(--muted);
  border: 1px solid var(--border); border-radius: 20px; padding: 1px 8px;
}
.pk-top .tag { font-size: 11px; padding: 1px 8px; }
.pk-actions { margin-left: auto; display: flex; flex-wrap: wrap; justify-content: flex-end; align-items: center; gap: 6px; }

.pk-name {
  font-size: 15px; font-weight: 600; margin: 0; line-height: 1.4;
  overflow-wrap: anywhere;
  color: var(--accent-deep);
}
.pk-click:hover .pk-name { color: var(--accent); }

.pk-desc {
  margin: 6px 0 0; color: var(--muted); font-size: 13px; line-height: 1.6;
  min-height: 42px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}

.pk-foot {
  display: flex; flex-wrap: wrap; gap: 12px;
  margin-top: auto; padding-top: 12px; border-top: 1px solid var(--border);
  font-size: 12px; color: var(--muted);
}
.pk-owner { display: inline-flex; align-items: center; gap: 5px; }
.pk-dot {
  width: 8px; height: 8px; border-radius: 50%; background: var(--accent);
  display: inline-block;
}
</style>
