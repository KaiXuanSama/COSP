/**
 * 实时同步的串行化与信号补偿。
 *
 * 两个日志视角都靠 SSE 信号触发「拉第一页并合并新行」，都需要防止并发拉取，
 * 也都需要在拉取期间到来的信号不被丢掉。抽成纯逻辑是因为这段行为无法在真实
 * 环境里稳定复现 —— 它只在「信号间隔短于一次请求往返」时才走到，而正常使用
 * 下调用间隔是几十秒量级。只有把它与组件解耦，才能用受控的时序把它测出来。
 */

/**
 * 创建一个「同一时刻至多跑一次、期间来的触发不丢」的同步器。
 *
 * <h2>为何不能简单丢弃并发信号</h2>
 * 信号是每个落库分支各发一次的（一次调用若重试 N 轮就有 N 个信号），
 * 直接 {@code if (running) return} 在绝大多数时候无害 —— 下一个信号会带上全部新行。
 * 但如果被丢弃的恰好是<strong>最后一个</strong>信号，那批行就要等到下次新调用
 * 或用户手动刷新才出现。窗口不大，却正好落在「刚发生一批重试」这种最需要
 * 及时观察的时刻。
 *
 * <h2>为何补偿只需一次而非计数</h2>
 * 每次同步都是「拉第一页并合并所有超过水位线的新行」，是幂等的全量对齐而非
 * 增量应用。期间无论积压了多少个信号，一次补拉就能把它们全部覆盖，
 * 故用布尔标记而非计数器 —— 计数只会带来若干次查到相同数据的空转。
 *
 * <h2>失败也要补偿</h2>
 * 任务失败不该把期间到来的信号一并吞掉，否则「失败 + 信号被丢」会叠加成更长的
 * 不同步窗口。故循环先排空所有积压，再把首个错误抛给调用方 —— 两者都不丢。
 *
 * <h2>为何用循环而非递归</h2>
 * 递归补偿（在 finally 里 await 自身）会让首次调用的 promise 一直悬到所有补偿
 * 结束，且补偿抛错会顶替掉原始错误。循环排空没有这两个问题，语义也更直白：
 * 返回的 promise resolve 即代表「已完全对齐」。
 *
 * @param task 实际的同步动作，通常是「拉第一页并合并」
 * @returns 触发函数；重复调用在同一时刻只会让 task 串行执行
 */
export function createCoalescingSync(task: () => Promise<void>): () => Promise<void> {
  let running = false
  let pending = false

  return async function trigger(): Promise<void> {
    if (running) {
      pending = true
      return
    }
    running = true
    let firstError: unknown
    try {
      do {
        // 先清标记再执行：执行期间新到的信号才能被下一轮看见。
        pending = false
        try {
          await task()
        } catch (e) {
          if (firstError === undefined) firstError = e
        }
      } while (pending)
    } finally {
      running = false
    }
    if (firstError !== undefined) throw firstError
  }
}
