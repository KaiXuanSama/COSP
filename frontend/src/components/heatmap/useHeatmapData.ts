import { computed, type Ref } from 'vue'
import type {
  ActivityHeatmapProps,
  HeatmapModeConfig,
  HeatmapRecord,
  MonthSegment,
  NormalizedHeatmapDay,
  VisibleWeek,
} from './heatmap'

const DEFAULT_DAY_LABELS_WIDTH = 72
const DEFAULT_CELL_SIZE = 16
const DEFAULT_GAP = 4

/**
 * 负责把外部传入的 data / datasets 归一化成热力图渲染所需的统一结构。
 *
 * 这里不处理任何动画状态，只解决三件事：
 * 1. 对齐所有模式的数据日期轴。
 * 2. 把日期切成按周显示的列。
 * 3. 计算当前容器宽度下真正可见的列和月份标签。
 */
export function useHeatmapData(
  props: Readonly<ActivityHeatmapProps>,
  containerWidth: Ref<number>,
  dayLabelsWidth: Ref<number>,
  effectiveGap: Readonly<Ref<number>>,
) {
  const sourceDataByMode = computed<Record<string, HeatmapRecord[]>>(() => {
    return props.modes.reduce<Record<string, HeatmapRecord[]>>((accumulator, mode) => {
      accumulator[mode.key] = props.datasets?.[mode.key] ?? props.data
      return accumulator
    }, {})
  })

  const modeLookup = computed<Record<string, HeatmapModeConfig<any>>>(() => {
    return props.modes.reduce<Record<string, HeatmapModeConfig<any>>>((accumulator, mode) => {
      accumulator[mode.key] = mode
      return accumulator
    }, {})
  })

  const dataMapByMode = computed<Record<string, Map<string, HeatmapRecord>>>(() => {
    return props.modes.reduce<Record<string, Map<string, HeatmapRecord>>>((accumulator, mode) => {
      const map = new Map<string, HeatmapRecord>()
      for (const item of sourceDataByMode.value[mode.key] ?? []) {
        const date = resolveDate(item, props)
        if (date) map.set(date, item)
      }
      accumulator[mode.key] = map
      return accumulator
    }, {})
  })

  const allDates = computed<string[]>(() => {
    const dates = new Set<string>()
    for (const mode of props.modes) {
      for (const item of sourceDataByMode.value[mode.key] ?? []) {
        const date = resolveDate(item, props)
        if (date) dates.add(date)
      }
    }

    return [...dates].sort((left, right) => left.localeCompare(right))
  })

  const normalizedDays = computed<NormalizedHeatmapDay[]>(() => {
    return allDates.value.map((date) => ({
      date,
      recordsByMode: props.modes.reduce<Record<string, HeatmapRecord | null>>((accumulator, mode) => {
        accumulator[mode.key] = dataMapByMode.value[mode.key]?.get(date) ?? null
        return accumulator
      }, {}),
    }))
  })

  // structuralSignature 用于判断“数据本体是否换了一套”，哪怕长度相同也能识别。
  const structuralSignature = computed(() => {
    if (!normalizedDays.value.length || !props.modes.length) return ''
    const modeKeys = props.modes.map((mode) => mode.key).join('|')
    const dates = normalizedDays.value.map((day) => day.date).join('|')
    return `${modeKeys}::${dates}`
  })

  const resolvedActiveMode = computed(() => {
    if (props.modes.some((mode) => mode.key === props.activeMode)) return props.activeMode ?? ''
    return props.modes[0]?.key ?? ''
  })

  // 所有数据会先对齐到完整日期轴，再按周切成列，模板层只消费 visibleWeeks。
  const allWeeks = computed<VisibleWeek[]>(() => {
    if (!normalizedDays.value.length) return []

    const weeks: VisibleWeek[] = []
    const firstDow = getDayOfWeek(normalizedDays.value[0].date)
    let currentWeek: VisibleWeek = Array.from({ length: firstDow }, () => null)

    for (const day of normalizedDays.value) {
      currentWeek.push(day)
      if (currentWeek.length === 7) {
        weeks.push(currentWeek)
        currentWeek = []
      }
    }

    if (currentWeek.length) {
      while (currentWeek.length < 7) currentWeek.push(null)
      weeks.push(currentWeek)
    }

    return weeks
  })

  const visibleColumnCount = computed(() => {
    if (!allWeeks.value.length) return 0
    if (!containerWidth.value) return allWeeks.value.length

    // 左侧星期标签区占掉的宽度需要扣除，否则可见列数会被高估。
    // gap 从 effectiveGap 取，保持与组件壳的间隙值一致。
    const gap = effectiveGap.value
    const cellSize = props.cellSize ?? DEFAULT_CELL_SIZE
    const labelsWidth = dayLabelsWidth.value || DEFAULT_DAY_LABELS_WIDTH
    const availableWidth = Math.max(0, containerWidth.value - labelsWidth)
    const fittedColumns = Math.floor((availableWidth + gap) / (cellSize + gap))
    return Math.max(1, Math.min(allWeeks.value.length, fittedColumns))
  })

  const visibleWeeks = computed<VisibleWeek[]>(() => {
    if (!visibleColumnCount.value) return []
    return allWeeks.value.slice(Math.max(0, allWeeks.value.length - visibleColumnCount.value))
  })

  // visibleStateSignature 只关注当前屏幕内真正参与动画的列集合。
  const visibleStateSignature = computed(() => {
    if (!visibleWeeks.value.length || !props.modes.length) return ''
    const modeKeys = props.modes.map((mode) => mode.key).join('|')
    const anchors = visibleWeeks.value.map((week) => getWeekAnchor(week)).join('|')
    return `${modeKeys}::${anchors}`
  })

  const modeMax = computed<Record<string, number>>(() => {
    const maxValues = props.modes.reduce<Record<string, number>>((accumulator, mode) => {
      accumulator[mode.key] = 0
      return accumulator
    }, {})

    for (const day of normalizedDays.value) {
      for (const mode of props.modes) {
        maxValues[mode.key] = Math.max(maxValues[mode.key], getModeValue(day.recordsByMode[mode.key], mode.key))
      }
    }

    return maxValues
  })

  const monthSegments = computed<MonthSegment[]>(() => {
    const segments: MonthSegment[] = []
    const gap = effectiveGap.value
    const cellSize = props.cellSize ?? DEFAULT_CELL_SIZE
    let lastMonth = -1
    let startCol = 0

    for (let index = 0; index < visibleWeeks.value.length; index += 1) {
      const anchorDate = getWeekAnchor(visibleWeeks.value[index])
      const month = anchorDate ? getMonthNumber(anchorDate) : -1
      if (month !== lastMonth) {
        if (lastMonth !== -1) {
          segments.push({
            key: `${lastMonth}-${startCol}`,
            label: `${lastMonth}月`,
            width: (index - startCol) * (cellSize + gap) - gap,
          })
        }
        lastMonth = month
        startCol = index
      }
    }

    if (lastMonth !== -1) {
      segments.push({
        key: `${lastMonth}-${startCol}`,
        label: `${lastMonth}月`,
        width: (visibleWeeks.value.length - startCol) * (cellSize + gap) - gap,
      })
    }

    return segments
  })

  /**
   * 根据 mode 配置统一读取数值。
   * 优先走 getValue，自定义映射不受原始字段名限制；否则退回 valueKey。
   */
  function getModeConfig(modeKey: string) {
    return modeLookup.value[modeKey] ?? props.modes[0]
  }

  function getModeValue(item: HeatmapRecord | null, modeKey: string) {
    const mode = getModeConfig(modeKey)
    if (!mode || !item) return 0

    if (mode.getValue) {
      const value = mode.getValue(item)
      return Number.isFinite(value) ? value : 0
    }

    if (mode.valueKey) {
      const value = Number((item as Record<string, unknown>)[mode.valueKey])
      return Number.isFinite(value) ? value : 0
    }

    return 0
  }

  function getLevel(value: number, maxValue: number) {
    if (value === 0 || maxValue === 0) return 0
    const ratio = value / maxValue
    if (ratio <= 0.25) return 1
    if (ratio <= 0.5) return 2
    if (ratio <= 0.75) return 3
    return 4
  }

  return {
    normalizedDays,
    structuralSignature,
    resolvedActiveMode,
    visibleWeeks,
    visibleStateSignature,
    monthSegments,
    modeMax,
    getModeConfig,
    getModeValue,
    getLevel,
    getWeekAnchor,
  }
}

function resolveDate(item: HeatmapRecord, props: Readonly<ActivityHeatmapProps>) {
  // 允许页面层通过 getDate 完全接管日期提取逻辑，默认再回退到 dateKey。
  if (props.getDate) return props.getDate(item)
  const value = (item as Record<string, unknown>)[props.dateKey ?? 'usageDate']
  return typeof value === 'string' ? value : ''
}

function getDayOfWeek(dateStr: string) {
  const [year, month, day] = dateStr.split('-').map(Number)
  return new Date(year, month - 1, day).getDay()
}

function getWeekAnchor(week: VisibleWeek) {
  return week.find((day) => day)?.date ?? ''
}

function getMonthNumber(dateStr: string) {
  return Number(dateStr.split('-')[1])
}