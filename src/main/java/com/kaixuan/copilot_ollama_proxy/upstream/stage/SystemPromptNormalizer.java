package com.kaixuan.copilot_ollama_proxy.upstream.stage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * system 提示词抬升的<strong>纯逻辑</strong> —— 被 {@link SystemPromptNormalizeStage} 的
 * MESSAGES 实现与 Anthropic 执行器共同调用。
 *
 * <h2>为何是静态工具而不是把逻辑放进实现类</h2>
 * 与 {@code UpstreamChunkNormalizer} / {@code ReasoningFallback} 同一取向：
 * 支线化（Step 3.3d）**先让组件长成可查表的形状**，而调用点的切换是下一步的事。
 * 若把逻辑直接搬进实现类，执行器在切换前就得留下一份自己的副本 ——
 * **两份等价逻辑就是「静默分叉」的温床**：改一处忘另一处，两条路径行为不同而无人察觉
 * （本项目最警惕的失效形态）。
 *
 * <p>因此逻辑留在静态工具里、两个调用点都转调它。等支线接线完成（3.3d-2），
 * 执行器那份调用自然消失，工具类仍然只此一份。
 *
 * <h2>为何不是实例方法</h2>
 * 全部载荷由入参决定，不依赖任何配置或状态 —— 与另两个工具类一致。
 * 需要用 {@code ObjectMapper} 的实现会在自己那层从容器拿到再传进来。
 */
public final class SystemPromptNormalizer {

    private SystemPromptNormalizer() {
    }

    /**
     * 把 {@code messages} 里的 system 消息提取到顶层 {@code system} 字段。
     *
     * <p>若请求已带顶层 {@code system}，则保留它并把 messages 里的追加在后面 ——
     * 下游可能两种形态都用了，丢掉任何一份都会改变语义。
     *
     * <h2>顶层 system 是数组时不能降级成字符串</h2>
     * Anthropic 允许 {@code system} 是块数组，Claude CLI 就是这么发的：
     * 三个 {@code {type:"text"}} 块，后两块带 {@code cache_control:{type:"ephemeral"}}，
     * 表示「到此块为止的内容可缓存」。把它压成字符串会<strong>连缓存断点一起丢掉</strong>，
     * 每条请求都退化成缓存未命中。
     *
     * <p>早先这里的保留判据是 {@code instanceof String}，数组形态因此既不进拼接缓冲、
     * 又会被末尾那句 {@code put} <strong>整体覆盖</strong> —— 发往上游的 {@code system}
     * 只剩 messages 里抬上来的那一小段，上万字的系统提示词无声消失，而请求仍然 200。
     * 触发需要「数组形态顶层 system」与「messages 里有 system 消息」同时成立，
     * 缺任一个都走不到那句 {@code put}，所以它藏了很久：既有的抬升用例全是字符串形态。
     *
     * <p>抬升内容一律<strong>追加到末尾</strong>而非插入开头：数组里每个块都可能带缓存标记，
     * 改动任何已有块的内容都会让它之后的内容全部缓存失效。
     *
     * @param body 请求体，会被原地修改；没有可抬升内容时保持原样
     */
    @SuppressWarnings("unchecked")
    public static void extractSystemPrompt(Map<String, Object> body) {
        if (!(body.get("messages") instanceof List<?> rawMessages)) {
            return;
        }
        // 已有的顶层 system 与抬升内容分开收集：前者要按原形态落地（数组仍是数组），
        // 后者一律并入它的末尾。合成一个缓冲就会逼两者共用一个输出形态，
        // 那正是数组被降级成字符串的原因。
        Object existingSystem = body.get("system");
        StringBuilder liftedText = new StringBuilder();
        List<Object> kept = new ArrayList<>();
        for (Object item : rawMessages) {
            if (item instanceof Map<?, ?> raw && "system".equals(raw.get("role"))) {
                String text = stringifyContent(((Map<String, Object>) raw).get("content"));
                if (text != null && !text.isBlank()) {
                    if (!liftedText.isEmpty()) {
                        liftedText.append("\n\n");
                    }
                    liftedText.append(text);
                }
                continue;
            }
            kept.add(item);
        }
        body.put("messages", kept);

        // 没有可抬升的内容时顶层 system 原样不动 —— 包括「本来就没有」和「空块」两种情形，
        // 后者也无需为无内容的消息凭空造一个字段。
        if (!liftedText.isEmpty()) {
            body.put("system", mergeSystem(existingSystem, liftedText.toString()));
        }
    }

    /**
     * 把抬升出来的 system 文本并入已有的顶层 {@code system}。
     *
     * <p>三种形态，判据与 {@link #stringifyContent} 对 content 的处理同构：
     * <ul>
     *   <li><strong>数组</strong> —— 追加一个新的 {@code text} 块，保持数组形态不变。
     *       不合并进已有块：那会改变已有块的文本，令其缓存标记覆盖的范围失效。</li>
     *   <li><strong>非空字符串</strong> —— 用空行拼接，这是下游同时提供两种形态时的既有语义。</li>
     *   <li><strong>缺失、空串或其它类型</strong> —— 以抬升内容为准。
     *       第三种实际不会出现（Anthropic 只接受字符串与数组），按此处理是为了与
     *       「抬升前」的行为保持一致，不在这里新增判断分支。</li>
     * </ul>
     *
     * @param existingSystem 顶层原有的 {@code system}，可能为 null
     * @param lifted          从 messages 抬升上来的文本，保证非空
     * @return 合并后的顶层 system 值
     */
    public static Object mergeSystem(Object existingSystem, String lifted) {
        if (existingSystem instanceof List<?> blocks) {
            List<Object> merged = new ArrayList<>(blocks);
            // 用可变 Map 而非 Map.of：规则引擎若需改写这个块，不可变集合会直接抛异常。
            Map<String, Object> appended = new LinkedHashMap<>();
            appended.put("type", "text");
            appended.put("text", lifted);
            merged.add(appended);
            return merged;
        }
        if (existingSystem instanceof String existing && !existing.isBlank()) {
            return existing + "\n\n" + lifted;
        }
        return lifted;
    }

    /**
     * 把 message 的 content 转成纯文本。
     *
     * <p>content 可能是字符串，也可能是 OpenAI 多模态那种
     * {@code [{"type":"text","text":"..."}]} 数组 —— 后者取出所有 text 片段拼接。
     * 非文本片段（图片等）在 system 提示词里没有意义，忽略。
     *
     * @param content 消息的 content 字段，类型不定
     * @return 纯文本；无法转成文本时返回 null
     */
    public static String stringifyContent(Object content) {
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> parts) {
            StringBuilder builder = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map && "text".equals(map.get("type"))
                        && map.get("text") instanceof String text) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            }
            return builder.toString();
        }
        return null;
    }
}
