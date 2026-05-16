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