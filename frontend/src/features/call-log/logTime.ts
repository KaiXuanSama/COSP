/**
 * 日志列表的时间列显示。
 *
 * 实时模式下几乎都是当天记录，只看时分秒最紧凑；检索可以跨天甚至跨年，
 * 只显示时分秒会让不同日期的行看起来一模一样。所以按与「现在」的距离逐级补全。
 */

/**
 * @param value `api_call_log.created_at`，格式 `yyyy-MM-ddTHH:mm:ss`（服务器本地时间）
 * @param now 用于判断「当天 / 当年」的参考时刻，测试时注入
 * @returns 当天 `HH:mm:ss`；当年 `MM-dd HH:mm:ss`；其余 `yyyy-MM-dd HH:mm:ss`
 */
export function formatLogTime(value: string, now: Date = new Date()): string {
  if (!value) return ''
  const [date, time] = value.split('T')
  if (!time) return value
  const [year, month, day] = date.split('-')
  if (!year || !month || !day) return value

  const sameYear = Number(year) === now.getFullYear()
  if (sameYear && Number(month) === now.getMonth() + 1 && Number(day) === now.getDate()) {
    return time
  }
  return sameYear ? `${month}-${day} ${time}` : `${date} ${time}`
}
