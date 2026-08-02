import { describe, expect, it } from 'vitest'
import {
  alignShift,
  buildMorphFrame,
  framesOfMorph,
  framesOfSeeds,
  morphSignature,
  SLOT_MORPH_EASING,
  type MorphSeed,
} from './useSlotMorph'

/**
 * 按位置形变模型的验证与锁定。
 *
 * 这套动效的核心主张是「所有依赖这批位置的渲染物读同一批数值」，因此测试锁的是
 * 插值本身：任意进度下元素都落在起止之间，新增 / 退场从线端出发或收回。
 * 只要这两条成立，「数量增」「数量减」就无需各自适配 —— 它们本是同一种运算。
 */

/** 造一批只有坐标的元素（端点的形状）。 */
function seeds(...coords: Array<[number, number]>): MorphSeed[] {
  return coords.map(([x, y]) => ({ x, y }))
}

/** 造一批带文字的元素（横轴标签的形状）。 */
function labels(...items: Array<[number, string, number?]>): Array<MorphSeed & { text: string }> {
  return items.map(([x, text, opacity]) => ({ x, y: 0, text, opacity }))
}

/** 标签的内容身份。 */
const byText = (data: { text: string }) => data.text

describe('按位置序号配对', () => {
  it('数量不变时全部原地移动，没有进出场', () => {
    const from = framesOfSeeds(seeds([0, 0.1], [0.5, 0.2], [1, 0.3]))
    const to = seeds([0, 0.9], [0.5, 0.8], [1, 0.7])

    const morph = buildMorphFrame(from, to, 1)

    expect(morph.map((item) => item.phase)).toEqual(['stable', 'stable', 'stable'])
    expect(morph.map((item) => item.y)).toEqual([0.9, 0.8, 0.7])
  })

  it('中途进度落在起止之间 —— 折线因此能读到中间位置', () => {
    const morph = buildMorphFrame(framesOfSeeds(seeds([0, 0])), seeds([0, 1]), 0.5)

    expect(morph[0].y).toBeCloseTo(0.5)
  })

  it('数量增多时公共前缀复用，多出的尾部进场', () => {
    const from = framesOfSeeds(seeds([0, 0.1], [1, 0.2]))
    const to = seeds([0, 0.1], [0.5, 0.2], [1, 0.3])

    const morph = buildMorphFrame(from, to, 1)

    expect(morph.map((item) => item.phase)).toEqual(['stable', 'stable', 'enter'])
    expect(morph.map((item) => item.slot)).toEqual([0, 1, 2])
  })

  it('数量减少时多余的尾部退场', () => {
    const from = framesOfSeeds(seeds([0, 0.1], [0.5, 0.2], [1, 0.3]))

    const morph = buildMorphFrame(from, seeds([0, 0.4], [1, 0.5]), 0.5)

    expect(morph.map((item) => item.phase)).toEqual(['stable', 'stable', 'leave'])
  })

  it('留存与进场在前、退场在后，折线路径按此顺序连线', () => {
    const from = framesOfSeeds(seeds([0, 0.1], [0.5, 0.2], [1, 0.3]))
    const morph = buildMorphFrame(from, seeds([0, 0.4]), 0.5)

    const slots = morph.map((item) => item.slot)
    expect(slots).toEqual([...slots].sort((a, b) => a - b))
  })

  it('数据取目标值，不随位置一起插值', () => {
    // 插值出来的中间读数没有意义，浮框要显示的是这一点真正的用量
    const to = [{ x: 0, y: 0.9, value: 90 }]
    const morph = buildMorphFrame(framesOfSeeds([{ x: 0, y: 0.1, value: 10 }]), to, 0.3)

    expect(morph[0].data).toBe(to[0])
  })

  it('留存元素跨批次共用一个 key，故 DOM 节点被复用', () => {
    const from = framesOfSeeds(seeds([0, 0.1], [1, 0.2]))
    const a = buildMorphFrame(from, seeds([0, 0.5], [1, 0.6]), 0)
    const b = buildMorphFrame(from, seeds([0, 0.5], [1, 0.6]), 1)

    expect(a.map((item) => item.key)).toEqual(b.map((item) => item.key))
  })
})

describe('新增元素从线端生长', () => {
  it('起始帧里新增元素落在上一批的末位上', () => {
    const from = framesOfSeeds(seeds([0, 0.2], [0.5, 0.6]))
    const morph = buildMorphFrame(from, seeds([0, 0.2], [0.5, 0.6], [1, 0.9]), 0)

    // 线因此是从原有尾端抽出来的，而不是在新位置凭空多出一段
    expect(morph[2].x).toBeCloseTo(0.5)
    expect(morph[2].y).toBeCloseTo(0.6)
    expect(morph[2].opacity).toBe(0)
  })

  it('进度走满后新增元素到达目标并完全显影', () => {
    const morph = buildMorphFrame(framesOfSeeds(seeds([0, 0.2])), seeds([0, 0.2], [1, 0.9]), 1)

    expect(morph[1].x).toBe(1)
    expect(morph[1].y).toBe(0.9)
    expect(morph[1].opacity).toBe(1)
  })

  it('首次渲染没有上一批，就地淡入', () => {
    // 无处可出发，位移会退化成一次凭空飞入
    const morph = buildMorphFrame([], seeds([0, 0.1], [1, 0.2]), 0)

    expect(morph.map((item) => item.phase)).toEqual(['enter', 'enter'])
    expect(morph[0].x).toBe(0)
    expect(morph[1].x).toBe(1)
  })

  it('多个新增元素共用同一出发处', () => {
    const from = framesOfSeeds(seeds([0, 0.5]))
    const morph = buildMorphFrame(from, seeds([0, 0.5], [0.5, 0.2], [1, 0.8]), 0)

    expect(morph[1].x).toBeCloseTo(0)
    expect(morph[2].x).toBeCloseTo(0)
  })

  it('进场终态取自身的目标不透明度，而非一律实心', () => {
    // 未来时段的标签本就该淡显，新长出来时不该先变实心再淡回去
    const from = framesOfSeeds(labels([0, 'a']))
    const morph = buildMorphFrame(from, labels([0, 'a'], [1, 'b', 0.42]), 1, byText)

    expect(morph[1].opacity).toBeCloseTo(0.42)
  })
})

describe('退场元素向线端收回', () => {
  it('终态落在新一批的末位上', () => {
    const from = framesOfSeeds(seeds([0, 0.1], [0.5, 0.4], [1, 0.9]))
    const morph = buildMorphFrame(from, seeds([0, 0.1], [1, 0.5]), 1)

    const leaving = morph.find((item) => item.phase === 'leave')!
    // 线被拽短到消失，而不是当帧截断
    expect(leaving.x).toBeCloseTo(1)
    expect(leaving.y).toBeCloseTo(0.5)
    expect(leaving.opacity).toBe(0)
  })

  it('起始帧里退场元素仍在原位且不透明', () => {
    const morph = buildMorphFrame(framesOfSeeds(seeds([0, 0.1], [1, 0.9])), seeds([0, 0.1]), 0)

    expect(morph[1].x).toBe(1)
    expect(morph[1].y).toBe(0.9)
    expect(morph[1].opacity).toBe(1)
  })

  it('多个退场元素同时收拢到同一处', () => {
    const from = framesOfSeeds(seeds([0, 0.1], [0.3, 0.2], [0.6, 0.3], [1, 0.4]))
    const morph = buildMorphFrame(from, seeds([0, 0.7]), 1)

    const leaving = morph.filter((item) => item.phase === 'leave')
    expect(leaving).toHaveLength(3)
    expect(leaving.every((item) => Math.abs(item.x - 0) < 1e-6)).toBe(true)
    expect(leaving.every((item) => Math.abs(item.y - 0.7) < 1e-6)).toBe(true)
  })

  it('新一批为空时退场元素就地淡出', () => {
    // 无处可去，收拢会退化成一次飞向原点
    const morph = buildMorphFrame(framesOfSeeds(seeds([0.4, 0.6])), [], 1)

    expect(morph[0].x).toBe(0.4)
    expect(morph[0].opacity).toBe(0)
  })

  it('退场元素保留自己的数据，淡出途中显示的仍是它自己', () => {
    const from = framesOfSeeds(labels([0, 'a'], [1, 'b']))
    const morph = buildMorphFrame(from, labels([0, 'a']), 0.5, byText)

    expect(morph[1].data.text).toBe('b')
  })
})

describe('内容身份：同一位置换了东西', () => {
  it('开启 crossFade 时旧内容淡出、新内容淡入，两者一起滑向新坐标', () => {
    // 最左侧那一格从 7/24 变成 05:00，位置没动但内容全换了，直接改写文字是一次硬切
    const from = framesOfSeeds(labels([0, '7/24'], [1, '7/30']))
    const morph = buildMorphFrame(from, labels([0, '05:00'], [1, '05:00']), 0.5, byText, true)

    const texts = morph.map((item) => item.data.text)
    expect(texts).toContain('7/24')
    expect(texts).toContain('05:00')

    const outgoing = morph.filter((item) => item.phase === 'leave')
    const incoming = morph.filter((item) => item.phase === 'enter')
    expect(outgoing).toHaveLength(2)
    expect(incoming).toHaveLength(2)
    // 同一格的新旧两者同处一点：淡出与淡入在同一位置交替
    expect(outgoing[0].x).toBeCloseTo(incoming[0].x)
  })

  /**
   * 不开 crossFade 时旧内容直接消失，只有新内容淡入。
   *
   * 端点走这条路：交叉淡化会让被顶掉的旧点串进折线路径，凭空多出一整段线。
   */
  it('未开 crossFade 时不产生交叉淡出的元素', () => {
    const from = framesOfSeeds(labels([0, '7/24'], [1, '7/30']))
    const morph = buildMorphFrame(from, labels([0, '05:00'], [1, '05:00']), 0.5, byText)

    expect(morph.filter((item) => item.phase === 'leave')).toHaveLength(0)
    expect(morph.filter((item) => item.phase === 'enter')).toHaveLength(2)
  })

  it('文字未变则视为留存，只滑动不淡', () => {
    const from = framesOfSeeds(labels([0, '05:00'], [0.5, '11:00']))
    const morph = buildMorphFrame(from, labels([0, '05:00'], [1, '11:00']), 0.5, byText)

    expect(morph.map((item) => item.phase)).toEqual(['stable', 'stable'])
    expect(morph[1].x).toBeCloseTo(0.75)
  })

  it('新旧元素 key 不同，故能同时存在于 DOM', () => {
    const from = framesOfSeeds(labels([0, 'a']))
    const morph = buildMorphFrame(from, labels([0, 'b']), 0.5, byText, true)

    const keys = morph.map((item) => item.key)
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('未给 identity 时位置相同即视为留存，不做交叉', () => {
    // 未给身份就无从对齐，只能按位置配对
    const from = framesOfSeeds(labels([0, 'a']))
    const morph = buildMorphFrame(from, labels([1, 'b']), 0.5)

    expect(morph).toHaveLength(1)
    expect(morph[0].phase).toBe('stable')
  })
})

/**
 * 身份对齐 —— 窗口滑动与跨度变化时的方位正确性。
 *
 * <p>这组锁的是「进出场发生在哪一侧」。纯位置配对把第 i 个新元素接到第 i 个旧元素上，
 * 于是窗口左移一格会被算成「每个位置的读数各自跳变」：没有进出场、没有横向移动，
 * 看不出窗口动过。而实际发生的事情是「整条线右移一格、右端移出、左端补入」。
 */
describe('身份对齐：窗口滑动', () => {
  it('窗口左移一格：右端退场、左端进场，同名元素横向平移', () => {
    // 旧批 a,b,c 在 slot 0..2；新批 z,a,b —— 同名的 a、b 指向位移 δ=-1
    const from = framesOfSeeds(labels([0, 'a'], [0.5, 'b'], [1, 'c']))
    const morph = buildMorphFrame(from, labels([0, 'z'], [0.5, 'a'], [1, 'b']), 1, byText)

    // slot 从 -1 起：负值无妨，它只是渲染 key 与排序依据
    expect(morph.map((item) => item.slot)).toEqual([-1, 0, 1, 2])
    expect(morph.map((item) => item.phase)).toEqual(['enter', 'stable', 'stable', 'leave'])
    // 最左是新进场的 z，最右是退场的 c
    expect(morph[0].data.text).toBe('z')
    expect(morph[3].data.text).toBe('c')
  })

  it('窗口右移一格：左端退场、右端进场', () => {
    const from = framesOfSeeds(labels([0, 'a'], [0.5, 'b'], [1, 'c']))
    const morph = buildMorphFrame(from, labels([0, 'b'], [0.5, 'c'], [1, 'd']), 1, byText)

    expect(morph.map((item) => item.slot)).toEqual([0, 1, 2, 3])
    expect(morph.map((item) => item.phase)).toEqual(['leave', 'stable', 'stable', 'enter'])
    expect(morph[0].data.text).toBe('a')
    expect(morph[3].data.text).toBe('d')
  })

  /**
   * 新元素从<strong>邻近</strong>的那一端出发。
   *
   * 这是「左侧生长」的全部实现：新元素的 slot 比所有旧元素都小，
   * 旧首位离它更近，于是它从左端抽出，而不是横穿整张图从末位跑过来。
   */
  it('左端新增的元素从旧首位出发', () => {
    const from = framesOfSeeds(labels([0.2, 'a'], [1, 'b']))
    // 新批 [z, a, b]：δ=-1，z 落在 slot -1
    const morph = buildMorphFrame(from, labels([0, 'z'], [0.5, 'a'], [1, 'b']), 0, byText)

    const entering = morph.find((item) => item.phase === 'enter')!
    // 进度 0 时它还在出发处 —— 旧首位的 0.2，而非旧末位的 1
    expect(entering.x).toBeCloseTo(0.2)
  })

  it('右端新增的元素从旧末位出发', () => {
    const from = framesOfSeeds(labels([0, 'a'], [0.8, 'b']))
    const morph = buildMorphFrame(from, labels([0, 'a'], [0.5, 'b'], [1, 'c']), 0, byText)

    const entering = morph.find((item) => item.phase === 'enter')!
    expect(entering.x).toBeCloseTo(0.8)
  })

  /** 退场元素归向邻近的新线端 —— 与进场对称，线是被拽短的而非截断的。 */
  it('左端退场的元素收向新首位', () => {
    const from = framesOfSeeds(labels([0, 'a'], [0.5, 'b'], [1, 'c']))
    // 新批 [b, c]：δ=+1，slot 0 的 a 无人接手
    const morph = buildMorphFrame(from, labels([0.3, 'b'], [1, 'c']), 1, byText)

    const leaving = morph.find((item) => item.phase === 'leave')!
    expect(leaving.data.text).toBe('a')
    // 归处是新首位 0.3，而非新末位 1
    expect(leaving.x).toBeCloseTo(0.3)
  })

  it('左侧拖宽：两个新元素都在留存元素左侧', () => {
    const from = framesOfSeeds(labels([0, 'c'], [1, 'd']))
    const morph = buildMorphFrame(from, labels([0, 'a'], [0.33, 'b'], [0.66, 'c'], [1, 'd']), 1, byText)

    expect(morph.map((item) => item.slot)).toEqual([-2, -1, 0, 1])
    expect(morph.map((item) => item.phase)).toEqual(['enter', 'enter', 'stable', 'stable'])
  })

  it('两批毫无重合时退化为位置配对', () => {
    const from = framesOfSeeds(labels([0, 'a'], [1, 'b']))
    const morph = buildMorphFrame(from, labels([0, 'x'], [1, 'y']), 1, byText)

    // δ=0：位置相同即接手，未开 crossFade 故旧内容直接消失
    expect(morph.map((item) => item.slot)).toEqual([0, 1])
    expect(morph.map((item) => item.phase)).toEqual(['enter', 'enter'])
  })
})

describe('alignShift', () => {
  it('两批毫无重合时返回 0', () => {
    expect(alignShift(framesOfSeeds(labels([0, 'a'])), labels([0, 'x']), byText)).toBe(0)
  })

  it('任一批为空时返回 0', () => {
    expect(alignShift([], labels([0, 'a']), byText)).toBe(0)
    expect(alignShift(framesOfSeeds(labels([0, 'a'])), [], byText)).toBe(0)
  })

  it('取票数最多的位移量', () => {
    const from = framesOfSeeds(labels([0, 'a'], [0.3, 'b'], [0.6, 'c'], [1, 'd']))
    // 新批 b,c,d,e 中三个身份指向 δ=+1
    expect(alignShift(from, labels([0, 'b'], [0.3, 'c'], [0.6, 'd'], [1, 'e']), byText)).toBe(1)
  })

  it('平票时取位移绝对值更小的', () => {
    // 旧批只有 b 在 slot 0；新批 [x, b] 让 δ=-1 得一票、无其他候选。
    // 换成 [b, x] 则 δ=0，绝对值更小。
    const from = framesOfSeeds(labels([0.5, 'b']))
    expect(alignShift(from, labels([0, 'b'], [1, 'x']), byText)).toBe(0)
    expect(alignShift(from, labels([0, 'x'], [1, 'b']), byText)).toBe(-1)
  })
})

describe('形变途中打断', () => {
  it('从当前帧续上，起点不是上一个目标', () => {
    // 中途切换时若从上一个目标起算，元素会先跳回还没到达的位置再重新出发
    const half = buildMorphFrame(framesOfSeeds(seeds([0, 0])), seeds([0, 1]), 0.5)
    const resumed = buildMorphFrame(framesOfMorph(half), seeds([0, 0]), 0)

    expect(resumed[0].y).toBeCloseTo(0.5)
  })

  it('退场元素不入下一轮的配对基准', () => {
    // 它们占的位置本次已让出，留着会让下一轮误以为那里仍有东西
    const shrinking = buildMorphFrame(
      framesOfSeeds(seeds([0, 0.1], [1, 0.9])),
      seeds([0, 0.1]),
      0.5,
    )

    expect(framesOfMorph(shrinking)).toHaveLength(1)
  })

  it('被顶掉的旧内容也不入下一轮 —— 该位置已由新内容接手', () => {
    const replacing = buildMorphFrame(framesOfSeeds(labels([0, 'a'])), labels([0, 'b']), 0.5, byText)

    const next = framesOfMorph(replacing)
    expect(next).toHaveLength(1)
    expect(next[0].data.text).toBe('b')
  })
})

describe('进度钳制', () => {
  it('越界进度按端点处理', () => {
    const from = framesOfSeeds(seeds([0, 0]))
    const to = seeds([0, 1])

    expect(buildMorphFrame(from, to, -1)[0].y).toBe(0)
    expect(buildMorphFrame(from, to, 2)[0].y).toBe(1)
  })
})

describe('缓动曲线', () => {
  it('单调且首末对齐，与柱状图形变同一条曲线', () => {
    expect(SLOT_MORPH_EASING(0)).toBe(0)
    expect(SLOT_MORPH_EASING(1)).toBe(1)

    let previous = 0
    for (let i = 1; i <= 20; i += 1) {
      const current = SLOT_MORPH_EASING(i / 20)
      expect(current).toBeGreaterThanOrEqual(previous)
      previous = current
    }
  })
})

describe('动画量签名', () => {
  it('同一批位置产出同一签名 —— 数组换了引用也不重播动画', () => {
    // 范围切换后 HTTP 首屏与 SSE 首帧携带同一份点位、相隔数十毫秒先后到达，
    // 各自都会让上游 computed 产出新数组。若照常重启形变，第二次的起始帧里
    // 已经没有退场元素，正在淡出的那一批会当场被摘掉，动画播到一半被截断。
    expect(morphSignature(seeds([0, 0.1], [1, 0.9])))
      .toBe(morphSignature(seeds([0, 0.1], [1, 0.9])))
  })

  it('位置变了签名就变', () => {
    expect(morphSignature(seeds([0, 0.1])))
      .not.toBe(morphSignature(seeds([0, 0.2])))
  })

  it('数量变了签名就变', () => {
    expect(morphSignature(seeds([0, 0.1])))
      .not.toBe(morphSignature(seeds([0, 0.1], [1, 0.2])))
  })

  it('目标不透明度变了签名就变 —— 它同样被插值', () => {
    expect(morphSignature(labels([0, 'a', 1])))
      .not.toBe(morphSignature(labels([0, 'a', 0.42])))
  })

  it('给了 identity 则文字变化也算', () => {
    expect(morphSignature(labels([0, 'a']), byText))
      .not.toBe(morphSignature(labels([0, 'b']), byText))
  })

  it('未给 identity 时文字变化不算 —— 它不参与插值', () => {
    expect(morphSignature(labels([0, 'a'])))
      .toBe(morphSignature(labels([0, 'b'])))
  })

  it('不参与插值的字段变化不算 —— 读数刷新无需重播动画', () => {
    const before = [{ x: 0, y: 0.5, value: 100 }]
    const after = [{ x: 0, y: 0.5, value: 999 }]

    expect(morphSignature(before)).toBe(morphSignature(after))
  })
})

describe('帧快照', () => {
  it('按顺序占用 0 起的位置，不透明度取各自的目标值', () => {
    const snapshot = framesOfSeeds(labels([0, 'a'], [0.5, 'b', 0.42], [1, 'c']))

    expect(snapshot.map((item) => item.slot)).toEqual([0, 1, 2])
    expect(snapshot.map((item) => item.opacity)).toEqual([1, 0.42, 1])
  })

  it('空数据产出空快照', () => {
    expect(framesOfSeeds([])).toEqual([])
    expect(framesOfMorph([])).toEqual([])
  })
})
