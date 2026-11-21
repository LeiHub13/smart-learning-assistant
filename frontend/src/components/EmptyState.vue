<template>
  <div class="es" :class="{ compact }">
    <span class="es-ico"><AppIcon :name="icon" :size="compact ? 16 : 20" /></span>
    <div class="es-title">{{ title }}</div>
    <div v-if="hint" class="es-hint">{{ hint }}</div>
    <div v-if="$slots.default" class="es-extra"><slot /></div>
  </div>
</template>

<script setup>
import AppIcon from './AppIcon.vue'

// 统一空状态：图标 + 标题 + 提示 + 可选扩展（默认插槽，如建议问题 chips）
defineProps({
  icon: { type: String, default: 'lightbulb' },
  title: { type: String, required: true },
  hint: { type: String, default: '' },
  compact: { type: Boolean, default: false }, // 侧栏等窄容器用
})
</script>

<style scoped>
.es { display: flex; flex-direction: column; align-items: center; text-align: center; padding: 30px 16px; }
.es-ico {
  width: 44px; height: 44px; border-radius: 12px; margin-bottom: 12px;
  display: flex; align-items: center; justify-content: center;
  background: var(--accent-subtle); color: var(--accent);
}
.es-title { font-size: 14px; font-weight: 600; color: var(--text); }
.es-hint { margin-top: 5px; font-size: 12.5px; line-height: 1.7; color: var(--muted); max-width: 320px; }
.es-extra { margin-top: 14px; display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; }
.es.compact { padding: 16px 10px; }
.es.compact .es-ico { width: 34px; height: 34px; border-radius: 9px; margin-bottom: 8px; }
.es.compact .es-title { font-size: 13px; }
.es.compact .es-hint { font-size: 12px; }
</style>
