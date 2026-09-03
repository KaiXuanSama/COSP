package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 修复 {@code tool_use} 与 {@code tool_result} 的邻接关系。
 *
 * <h2>为何必须有这一步</h2>
 * 逐条翻译<strong>必然</strong>违反 Anthropic 的三条结构性不变式：
 * <ol>
 *   <li>{@code tool_result} 必须紧邻前一条 assistant 的 {@code tool_use}</li>
 *   <li>{@code tool_use} 必须被紧邻的下一条 user 应答</li>
 *   <li>角色交替</li>
 * </ol>
 * OpenAI 侧没有任何一条对应约束：{@code role: tool} 消息可以连续多条、
 * 可以与正文 user 消息交错、并行调用的结果顺序也不保证。
 *
 * <h2>三步顺序不可省略也不可交换</h2>
 * <pre>
 * merge → pair → merge
 * </pre>
 * <ul>
 *   <li><strong>首次 merge</strong>：让并行调用聚拢到同一条 assistant 消息。
 *       不先做这步，配对时同一轮的多个 {@code tool_use} 分散在多条消息里，
 *       无从判断哪些结果该跟去哪一条。</li>
 *   <li><strong>pair</strong>：丢弃未被应答的 {@code tool_use}、把匹配的
 *       {@code tool_result} 重新发到紧邻的下一条 user、丢弃孤儿 {@code tool_result}。</li>
 *   <li><strong>二次 merge</strong>：配对可能重新拆分了 user 轮
 *       （原本相邻的正文 user 与 tool_result user 现在中间插了东西），
 *       这一步恢复交替。</li>
 * </ul>
 *
 * <p>思路借鉴 sub2api 的 {@code normalizeAnthropicToolPairing}，
 * 契约第 3.5 节。
 */
final class ToolPairingNormalizer {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_SYSTEM = "system";

    /**
     * 执行 merge → pair → merge。
     *
     * @param messages 已逐条翻译好的消息列表
     * @return 修复后的新列表
     */
    List<Object> normalize(List<Object> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<Object> merged = mergeAdjacentSameRole(messages);
        List<Object> paired = pairToolBlocks(merged);
        return mergeAdjacentSameRole(paired);
    }

    /**
     * 合并相邻的同 role 消息。
     *
     * <p>system 不参与合并：它稍后要被提取到顶层，合并只会让提取逻辑多处理一种形态。
     *
     * <p>合并时统一升格成块数组。字符串 content 在<strong>单条</strong>消息里是
     * 更安全的形态，但两条要合并时必须有个容器，块数组是唯一能同时装文本与
     * tool_use / tool_result 的形态。
     */
    private List<Object> mergeAdjacentSameRole(List<Object> messages) {
        List<Object> result = new ArrayList<>(messages.size());
        for (Object item : messages) {
            if (!(item instanceof Map<?, ?> raw)) {
                result.add(item);
                continue;
            }
            Map<String, Object> message = asStringKeyMap(raw);
            String role = roleOf(message);

            if (ROLE_SYSTEM.equals(role) || result.isEmpty()) {
                result.add(message);
                continue;
            }
            Object previous = result.get(result.size() - 1);
            if (!(previous instanceof Map<?, ?> previousRaw)) {
                result.add(message);
                continue;
            }
            Map<String, Object> previousMessage = asStringKeyMap(previousRaw);
            if (!role.equals(roleOf(previousMessage)) || ROLE_SYSTEM.equals(roleOf(previousMessage))) {
                result.add(message);
                continue;
            }

            List<Object> combined = new ArrayList<>(blocksOf(previousMessage));
            combined.addAll(blocksOf(message));
            Map<String, Object> replacement = new LinkedHashMap<>(previousMessage);
            replacement.put("content", combined);
            result.set(result.size() - 1, replacement);
        }
        return result;
    }

    /**
     * 配对修复。
     *
     * <p>两趟扫描：先建立「哪些 tool_use_id 有应答」的索引，再重排。
     * 一趟做不到——决定是否丢弃某个 {@code tool_use} 需要知道后面有没有它的结果。
     */
    private List<Object> pairToolBlocks(List<Object> messages) {
        Set<Object> answeredIds = collectAnsweredIds(messages);
        Map<Object, Object> resultsById = collectResultsById(messages);

        List<Object> output = new ArrayList<>(messages.size());
        Set<Object> emittedResults = new LinkedHashSet<>();

        for (Object item : messages) {
            if (!(item instanceof Map<?, ?> raw)) {
                output.add(item);
                continue;
            }
            Map<String, Object> message = asStringKeyMap(raw);
            String role = roleOf(message);

            if (ROLE_ASSISTANT.equals(role)) {
                List<Object> kept = keepAnsweredToolUses(message, answeredIds);
                if (kept.isEmpty()) {
                    // 整条只有未被应答的 tool_use，且没有其它内容 —— 丢掉。
                    // 留着会让上游要求「下一条必须应答它」而我们给不出应答。
                    continue;
                }
                output.add(withBlocks(message, kept));

                // 紧接着补一条 user 消息装本轮所有 tool_result，顺序按 tool_use 出现顺序。
                List<Object> resultBlocks = collectResultsFor(kept, resultsById, emittedResults);
                if (!resultBlocks.isEmpty()) {
                    output.add(userMessageOf(resultBlocks));
                }
                continue;
            }

            if (ROLE_USER.equals(role)) {
                // 剥掉 tool_result 块 —— 它们已经（或将要）由上面那段按正确顺序发出。
                // 剥完还有内容才保留，否则这条 user 消息本身就只是个 tool_result 载体。
                List<Object> nonResultBlocks = stripToolResults(message);
                if (!nonResultBlocks.isEmpty()) {
                    output.add(withBlocks(message, nonResultBlocks));
                }
                continue;
            }

            output.add(message);
        }
        return output;
    }

    /** 收集所有出现过的 {@code tool_use_id}，即「有应答的调用」。 */
    private Set<Object> collectAnsweredIds(List<Object> messages) {
        Set<Object> ids = new LinkedHashSet<>();
        forEachBlock(messages, block -> {
            if ("tool_result".equals(block.get("type"))) {
                Object id = block.get("tool_use_id");
                if (id != null) {
                    ids.add(id);
                }
            }
        });
        return ids;
    }

    /**
     * 建立 {@code tool_use_id} → {@code tool_result} 块的索引。
     *
     * <p>同一个 id 出现多次时保留第一个：重复应答本身就是畸形输入，
     * 取第一个是可预测的选择，而把它们都发出去会让上游看到重复的 tool_result。
     */
    private Map<Object, Object> collectResultsById(List<Object> messages) {
        Map<Object, Object> results = new LinkedHashMap<>();
        forEachBlock(messages, block -> {
            if ("tool_result".equals(block.get("type"))) {
                Object id = block.get("tool_use_id");
                if (id != null) {
                    results.putIfAbsent(id, block);
                }
            }
        });
        return results;
    }

    /**
     * 只保留有应答的 {@code tool_use} 块，其它块原样保留。
     */
    private List<Object> keepAnsweredToolUses(Map<String, Object> message, Set<Object> answeredIds) {
        List<Object> kept = new ArrayList<>();
        for (Object block : blocksOf(message)) {
            if (block instanceof Map<?, ?> raw && "tool_use".equals(raw.get("type"))) {
                if (answeredIds.contains(raw.get("id"))) {
                    kept.add(block);
                }
                continue;
            }
            kept.add(block);
        }
        return kept;
    }

    /**
     * 按 {@code tool_use} 的出现顺序取出对应的 {@code tool_result} 块。
     *
     * <p>顺序很重要：Anthropic 按位置而非 id 关联并行调用的结果显示顺序。
     */
    private List<Object> collectResultsFor(List<Object> assistantBlocks,
                                           Map<Object, Object> resultsById,
                                           Set<Object> emittedResults) {
        List<Object> resultBlocks = new ArrayList<>();
        for (Object block : assistantBlocks) {
            if (!(block instanceof Map<?, ?> raw) || !"tool_use".equals(raw.get("type"))) {
                continue;
            }
            Object id = raw.get("id");
            Object result = resultsById.get(id);
            if (result != null && emittedResults.add(id)) {
                resultBlocks.add(result);
            }
        }
        return resultBlocks;
    }

    /** 剥掉 {@code tool_result} 块，返回其余块。 */
    private List<Object> stripToolResults(Map<String, Object> message) {
        List<Object> kept = new ArrayList<>();
        for (Object block : blocksOf(message)) {
            if (block instanceof Map<?, ?> raw && "tool_result".equals(raw.get("type"))) {
                continue;
            }
            kept.add(block);
        }
        return kept;
    }

    /**
     * 取出消息的内容块列表。
     *
     * <p>字符串 content 就地包成单个 text 块——调用方要的是「可以拼接的块序列」，
     * 让每个调用点各自判断两种形态会重复五六遍。
     */
    private List<Object> blocksOf(Map<String, Object> message) {
        Object content = message.get("content");
        if (content instanceof List<?> blocks) {
            return new ArrayList<>(blocks);
        }
        if (content instanceof String text && !text.isBlank()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", text);
            List<Object> single = new ArrayList<>(1);
            single.add(block);
            return single;
        }
        return new ArrayList<>();
    }

    private void forEachBlock(List<Object> messages, java.util.function.Consumer<Map<String, Object>> visitor) {
        for (Object item : messages) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            for (Object block : blocksOf(asStringKeyMap(raw))) {
                if (block instanceof Map<?, ?> blockRaw) {
                    visitor.accept(asStringKeyMap(blockRaw));
                }
            }
        }
    }

    private static Map<String, Object> withBlocks(Map<String, Object> message, List<Object> blocks) {
        Map<String, Object> result = new LinkedHashMap<>(message);
        result.put("content", blocks);
        return result;
    }

    private static Map<String, Object> userMessageOf(List<Object> blocks) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", ROLE_USER);
        message.put("content", blocks);
        return message;
    }

    private static String roleOf(Map<String, Object> message) {
        return message.get("role") instanceof String role ? role : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
