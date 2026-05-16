export type HeatmapRecord = object

export interface HeatmapModeConfig<T extends HeatmapRecord = HeatmapRecord> {
  key: string
  display: string
  unit: string
  colors: [string, string, string, string, string]
  valueKey?: keyof T & string
  getValue?: (item: T) => number
}

export interface HeatmapTooltipPayload<T extends HeatmapRecord = HeatmapRecord> {
  item: T | null
  date: string
  value: number
  mode: HeatmapModeConfig<T>
}

export interface ActivityHeatmapProps {
  data: HeatmapRecord[]
  datasets?: Record<string, HeatmapRecord[]>
  modes: HeatmapModeConfig<any>[]
  activeMode?: string
  loading?: boolean
  failed?: boolean
  emptyText?: string
  loadingText?: string
  errorText?: string
  dayLabels?: string[]
  dateKey?: string
  getDate?: (item: HeatmapRecord) => string
  tooltipFormatter?: (payload: HeatmapTooltipPayload<any>) => string
  cellSize?: number
  gap?: number
  initialRevealDelay?: number
  rippleDelay?: number
  rippleDuration?: number
  flipDelay?: number
  flipDuration?: number
}

export interface NormalizedHeatmapDay {
  date: string
  recordsByMode: Record<string, HeatmapRecord | null>
}

export interface MonthSegment {
  key: string
  label: string
  width: number
}

export type VisibleWeek = Array<NormalizedHeatmapDay | null>
export type RevealState = 'pending' | 'animating' | 'done'