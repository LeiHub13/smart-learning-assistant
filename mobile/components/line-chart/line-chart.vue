<template>
  <view class="lchart">
    <svg class="lchart-svg" :viewBox="'0 0 ' + W + ' ' + H" preserveAspectRatio="xMidYMid meet">
      <line v-for="g in gridYs" :key="'g' + g.y" :x1="padL" :y1="g.y" :x2="W - padR" :y2="g.y" stroke="#eceef2" stroke-width="1" />
      <text v-for="g in gridYs" :key="'gl' + g.y" :x="padL - 6" :y="g.y + 3" text-anchor="end" class="lchart-tick">{{ g.label }}</text>
      <text v-for="t in xTicks" :key="'x' + t.x" :x="t.x" :y="H - 4" :text-anchor="t.anchor" class="lchart-tick">{{ t.label }}</text>
      <g v-for="s in plotted" :key="s.id" :opacity="opacity(s.id)">
        <polyline :points="s.line" fill="none" :stroke="s.color" :stroke-width="isActive(s.id) ? 3 : 2" stroke-linejoin="round" stroke-linecap="round" />
        <circle v-for="(p, i) in s.dots" :key="i" :cx="p.x" :cy="p.y" :r="isActive(s.id) ? 3.5 : 2.5" :fill="s.color" />
      </g>
    </svg>
  </view>
</template>

<script>
/**
 * 轻量多系列折线图（SVG 自绘，App/H5 通用，替代 ECharts）
 * props.series: [{ id, name, color, points: [['yyyy-MM-dd', rate], ...] }]
 * props.activeId: 高亮的系列 id（其余淡化），由父组件渲染图例并切换
 */
const W = 340
const H = 150
const PAD_L = 32
const PAD_R = 10
const PAD_T = 8
const PAD_B = 18

export default {
  props: {
    series: { type: Array, default: () => [] },
    activeId: { type: null, default: null }
  },
  data() {
    return { W, H, padL: PAD_L, padR: PAD_R }
  },
  computed: {
    // 所有系列日期的并集（升序）作为 x 轴刻度
    allDates() {
      const set = new Set()
      this.series.forEach((s) => s.points.forEach((p) => set.add(p[0])))
      return [...set].sort()
    },
    gridYs() {
      return [0, 50, 100].map((v) => ({
        y: PAD_T + (100 - v) / 100 * (H - PAD_T - PAD_B),
        label: v + '%'
      }))
    },
    xTicks() {
      const ds = this.allDates
      if (!ds.length) return []
      const xOf = this.xOf
      const pick = ds.length <= 4 ? ds : [ds[0], ds[Math.floor((ds.length - 1) / 2)], ds[ds.length - 1]]
      return pick.map((d, i) => ({
        x: xOf(d),
        label: Number(d.slice(5, 7)) + '-' + Number(d.slice(8, 10)),
        anchor: i === 0 ? 'start' : i === pick.length - 1 ? 'end' : 'middle'
      }))
    },
    plotted() {
      const xOf = this.xOf
      const yOf = (rate) => PAD_T + (100 - rate) / 100 * (H - PAD_T - PAD_B)
      return this.series.map((s) => {
        const pts = s.points.map((p) => ({ x: xOf(p[0]), y: yOf(p[1]) }))
        return {
          id: s.id,
          color: s.color,
          line: pts.map((p) => p.x + ',' + p.y).join(' '),
          dots: pts
        }
      })
    }
  },
  methods: {
    isActive(id) {
      return this.activeId == null || this.activeId === id
    },
    opacity(id) {
      return this.activeId == null || this.activeId === id ? 1 : 0.15
    },
    xOf(date) {
      const ds = this.allDates
      if (ds.length <= 1) return PAD_L + (W - PAD_L - PAD_R) / 2
      const i = ds.indexOf(date)
      return PAD_L + i / (ds.length - 1) * (W - PAD_L - PAD_R)
    }
  }
}
</script>

<style>
.lchart { width: 100%; }
.lchart-svg { width: 100%; height: 150px; display: block; }
.lchart-tick { font-size: 9px; fill: #9aa0ab; }
</style>
