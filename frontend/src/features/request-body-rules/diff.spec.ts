import { describe, it, expect } from 'vitest'
import { buildDiffTree } from './diff'
import { DEFAULT_REQUEST_BODY } from './defaultRequestBody'

describe('buildDiffTree', () => {
  it('未变化时全部标记为 same', () => {
    const input = { a: 1, b: { c: 'x' } }
    const tree = buildDiffTree(input, input)
    expect(tree.status).toBe('same')
    expect(tree.kind).toBe('object')
    expect(tree.children?.every((c) => c.status === 'same')).toBe(true)
  })

  it('字段值修改标记为 changed', () => {
    const original = { role: 'tool' }
    const current = { role: 'user' }
    const tree = buildDiffTree(original, current)
    expect(tree.status).toBe('changed')
    const role = tree.children?.find((c) => c.key === 'role')
    expect(role?.status).toBe('changed')
    expect(role?.value).toBe('user')
  })

  it('字段删除标记为 deleted 并保留原值', () => {
    const original = { role: 'tool', tool_call_id: '123' }
    const current = { role: 'user' }
    const tree = buildDiffTree(original, current)
    const deleted = tree.children?.find((c) => c.key === 'tool_call_id')
    expect(deleted?.status).toBe('deleted')
    expect(deleted?.value).toBe('123')
  })

  it('字段新增标记为 added', () => {
    const original = { a: 1 }
    const current = { a: 1, b: 2 }
    const tree = buildDiffTree(original, current)
    const added = tree.children?.find((c) => c.key === 'b')
    expect(added?.status).toBe('added')
    expect(added?.value).toBe(2)
  })

  it('数组元素不携带序号键名', () => {
    const tree = buildDiffTree(
      { messages: [{ role: 'user' }, { role: 'assistant' }] },
      { messages: [{ role: 'user' }, { role: 'assistant' }] },
    )
    const messages = tree.children?.find((c) => c.key === 'messages')
    expect(messages?.kind).toBe('array')
    expect(messages?.children?.every((child) => child.key === null)).toBe(true)
  })

  /**
   * 图片 tool 消息被改写后的高亮形态。
   *
   * <p>转换结果是**手写**的而非由引擎算出：规则的执行已迁到后端，前端不再有引擎；
   * 而这条用例要验的是 diff 树的着色，与「谁算出这个结果」无关。
   * 手写输入输出对反而让被测意图更清楚 —— 改了 role、删了 tool_call_id，别的都没动。
   */
  it('图片 tool 消息改写后 role 标 changed、tool_call_id 标 deleted', () => {
    const original = DEFAULT_REQUEST_BODY
    const transformed = JSON.parse(JSON.stringify(original)) as typeof DEFAULT_REQUEST_BODY
    const imageToolMessage = transformed.messages[4] as Record<string, unknown>
    imageToolMessage.role = 'user'
    delete imageToolMessage.tool_call_id

    const tree = buildDiffTree(original, transformed)
    const messages = tree.children?.find((c) => c.key === 'messages')
    expect(messages?.kind).toBe('array')

    const imageMsg = messages?.children?.[4]
    expect(imageMsg?.status).toBe('changed')

    const role = imageMsg?.children?.find((c) => c.key === 'role')
    expect(role?.status).toBe('changed')
    expect(role?.value).toBe('user')

    const toolCallId = imageMsg?.children?.find((c) => c.key === 'tool_call_id')
    expect(toolCallId?.status).toBe('deleted')
    expect(toolCallId?.value).toBe('<string>')

    // 未被改动的文本 tool 消息保持 same
    expect(messages?.children?.[3]?.status).toBe('same')
  })

  /**
   * 数组元素配对。
   *
   * 纯下标对齐在「删掉非末尾元素」上会整体错位：被删元素之后的所有元素左移一位，
   * 于是每一位都在拿不同的两个元素相比，预览把「删了 1 个」显示成
   * 「每个都被改写 + 末尾整体删除」。规则执行没错，但高亮指向的位置全错。
   *
   * 修法是先用相等元素锚定，间隙内再按下标对齐 —— 两种信息都要保住，
   * 所以这组用例既验删除（位移）也验改写（同位）。
   */
  describe('数组元素配对', () => {
    /**
     * 删掉首元素，剩下的元素不受牵连。
     *
     * 这正是 MiMo 预设「移除 web_search 工具」的形态：`tools[0]` 被摘掉，
     * `tools[1]` 原地不动。错误的对齐会把 function 标成 changed。
     */
    it('删除首元素时后续元素保持 same', () => {
      const webSearch = { type: 'web_search', external_web_access: false }
      const shell = { type: 'function', name: 'shell' }
      const tree = buildDiffTree({ tools: [webSearch, shell] }, { tools: [shell] })

      const tools = tree.children?.find((c) => c.key === 'tools')
      expect(tools?.status).toBe('changed')
      expect(tools?.children?.map((c) => c.status)).toEqual(['deleted', 'same'])
      expect(tools?.children?.[0]?.value).toEqual(webSearch)
      // 保留下来的元素内部不能有任何标记 —— 它一个字节都没变
      expect(tools?.children?.[1]?.children?.every((c) => c.status === 'same')).toBe(true)
    })

    /** 删掉中间元素，两侧邻居都不受影响。 */
    it('删除中间元素时前后邻居保持 same', () => {
      const tree = buildDiffTree({ list: ['a', 'b', 'c'] }, { list: ['a', 'c'] })

      const list = tree.children?.find((c) => c.key === 'list')
      expect(list?.children?.map((c) => c.status)).toEqual(['same', 'deleted', 'same'])
      expect(list?.children?.[1]?.value).toBe('b')
    })

    /** 删掉多个不相邻的元素。 */
    it('删除多个不相邻元素', () => {
      const tree = buildDiffTree({ list: ['a', 'b', 'c', 'd', 'e'] }, { list: ['b', 'd'] })

      const list = tree.children?.find((c) => c.key === 'list')
      expect(list?.children?.map((c) => c.status)).toEqual([
        'deleted',
        'same',
        'deleted',
        'same',
        'deleted',
      ])
      expect(list?.children?.map((c) => c.value)).toEqual(['a', 'b', 'c', 'd', 'e'])
    })

    /**
     * 同位改写仍标 changed，而不是拆成「删一个 + 加一个」。
     *
     * 这是不能只用 LCS 的原因：LCS 只认相等与否，会丢掉「同一个元素变了」这层信息。
     */
    it('同位元素被改写时标 changed 而非删除加新增', () => {
      const tree = buildDiffTree(
        { list: [{ id: 1, role: 'tool' }] },
        { list: [{ id: 1, role: 'user' }] },
      )

      const list = tree.children?.find((c) => c.key === 'list')
      expect(list?.children).toHaveLength(1)
      expect(list?.children?.[0]?.status).toBe('changed')
      const role = list?.children?.[0]?.children?.find((c) => c.key === 'role')
      expect(role?.status).toBe('changed')
      expect(role?.value).toBe('user')
    })

    /** 「删一个 + 改一个」并存：删除不能把改写挤成错位。 */
    it('删除与改写并存时各自归位', () => {
      const tree = buildDiffTree(
        { list: ['drop', { id: 1, mode: 'a' }, 'keep'] },
        { list: [{ id: 1, mode: 'b' }, 'keep'] },
      )

      const list = tree.children?.find((c) => c.key === 'list')
      expect(list?.children?.map((c) => c.status)).toEqual(['deleted', 'changed', 'same'])
      const mode = list?.children?.[1]?.children?.find((c) => c.key === 'mode')
      expect(mode?.value).toBe('b')
    })

    /** 新增元素插在开头时，原有元素不跟着变。 */
    it('开头插入新元素时原有元素保持 same', () => {
      const tree = buildDiffTree({ list: ['b', 'c'] }, { list: ['a', 'b', 'c'] })

      const list = tree.children?.find((c) => c.key === 'list')
      expect(list?.children?.map((c) => c.status)).toEqual(['added', 'same', 'same'])
      expect(list?.children?.[0]?.value).toBe('a')
    })

    /** 全部删空。 */
    it('数组被清空时全部标 deleted', () => {
      const tree = buildDiffTree({ list: ['a', 'b'] }, { list: [] })

      const list = tree.children?.find((c) => c.key === 'list')
      expect(list?.status).toBe('changed')
      expect(list?.children?.map((c) => c.status)).toEqual(['deleted', 'deleted'])
    })

    /**
     * 键序不同但内容相同的元素算相等。
     *
     * 后端引擎重建对象时键序可能与输入不一致（如整体替换 `format`）。
     * 若指纹按原始键序算，这类元素会失去锚点资格，位移又会重现。
     */
    it('键序不同的等值元素仍能锚定', () => {
      const tree = buildDiffTree(
        { list: ['drop', { a: 1, b: 2 }] },
        { list: [{ b: 2, a: 1 }] },
      )

      const list = tree.children?.find((c) => c.key === 'list')
      expect(list?.children?.map((c) => c.status)).toEqual(['deleted', 'same'])
    })

    /** 重复元素不会互相抢锚点导致数量错乱。 */
    it('重复元素删掉一个时只标一处 deleted', () => {
      const tree = buildDiffTree({ list: ['x', 'x', 'x'] }, { list: ['x', 'x'] })

      const list = tree.children?.find((c) => c.key === 'list')
      expect(list?.children?.filter((c) => c.status === 'deleted')).toHaveLength(1)
      expect(list?.children?.filter((c) => c.status === 'same')).toHaveLength(2)
    })

    /**
     * 超过对齐上限时退回按下标对齐，不抛错也不卡住。
     *
     * 上限只为防住极端输入（LCS 的 DP 表是 O(n×m)，而 diff 随输入同步重算）。
     * 这种规模的请求体在实际路径上不存在，退化的高亮可以接受，卡死不行。
     */
    it('超大数组退回下标对齐且不报错', () => {
      const original = Array.from({ length: 260 }, (_, i) => i)
      const current = original.slice(1)
      const tree = buildDiffTree({ list: original }, { list: current })

      const list = tree.children?.find((c) => c.key === 'list')
      expect(list?.children).toHaveLength(260)
      expect(list?.status).toBe('changed')
    })

    /**
     * 「无锚点」输入不能卡住界面。
     *
     * <h2>为什么数组级上限挡不住这种输入</h2>
     * 每个元素都被改写时 LCS 一个锚点都找不到，<strong>整个数组落进同一个间隙</strong>，
     * 于是配对次数是 n×m 而非 n。数组级的 200 在这里等于 40000 次配对 ——
     * 实测修复前 n=200 要 547ms，而 `buildDiffTree` 在 `computed` 里同步执行，
     * 那就是界面冻住半秒。修法两条：指纹只算一次（纯缓存），以及给间隙单独设上限。
     *
     * <p>断言耗时而非只断言「不抛错」：这个缺陷的唯一症状就是慢，
     * 不设时间上限的用例对它完全没有约束力。阈值取 150ms 而非实测的 14ms ——
     * CI 机器与本机性能差异可能有数倍，留足余量后仍能挡住 547ms 那一档。
     */
    it('每个元素都被改写时不退化成秒级', () => {
      const messageAt = (index: number, marker: string) => ({
        role: index % 2 === 0 ? 'user' : 'assistant',
        content: [
          { type: 'text', text: `第 ${index} 条消息的正文内容，长度中等` },
          { type: 'image_url', image_url: { url: `https://example.com/${index}.png` } },
        ],
        metadata: { seq: index, marker },
      })
      const original = { messages: Array.from({ length: 200 }, (_, i) => messageAt(i, 'a')) }
      const current = { messages: Array.from({ length: 200 }, (_, i) => messageAt(i, 'b')) }

      const start = performance.now()
      const tree = buildDiffTree(original, current)
      const elapsed = performance.now() - start

      expect(elapsed).toBeLessThan(150)
      // 结果仍要正确：200 个元素逐位配对，全部标 changed。
      const messages = tree.children?.find((c) => c.key === 'messages')
      expect(messages?.children).toHaveLength(200)
      expect(messages?.children?.every((c) => c.status === 'changed')).toBe(true)
    })

    /**
     * 间隙退回下标对齐后，锚点仍然生效。
     *
     * <p>间隙级上限只作用于<strong>那一段</strong>，不能连带让整个数组退化 ——
     * 否则一个长改写段会毁掉它前后所有相等元素的对齐。
     */
    it('长改写段退化时不影响两端的锚点', () => {
      const filler = (i: number, marker: string) => ({ id: i, marker })
      const original = {
        list: ['head', ...Array.from({ length: 80 }, (_, i) => filler(i, 'a')), 'tail'],
      }
      const current = {
        list: ['head', ...Array.from({ length: 80 }, (_, i) => filler(i, 'b')), 'tail'],
      }

      const list = buildDiffTree(original, current).children?.find((c) => c.key === 'list')
      // 首尾两个相等元素被锚定，不受中间那 80 个改写元素的退化影响。
      expect(list?.children?.[0]?.status).toBe('same')
      expect(list?.children?.[list.children.length - 1]?.status).toBe('same')
      expect(list?.children).toHaveLength(82)
    })
  })
})
