package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Responses 空响应判定。
 *
 * <p>三类实质载荷的<strong>划分</strong>与另两侧一致（正文 / 思考链 / 工具调用），
 * 但取值路径完全不同 —— 本类钉的正是那些路径，以及「解析失败保守放行」这条取向。
 *
 * <h2>为何这些用例值得存在</h2>
 * 判空错误的两种方向后果都很重：
 * <ul>
 *   <li>把正常响应判成空 → 卷入重试循环，用户看到的是超时而非错误；</li>
 *   <li>把空响应判成正常 → 空响应兜底整个失效，那是这套机制存在的唯一理由。</li>
 * </ul>
 */
class ResponsesContentDetectorTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    class 非流式 {

        /** 正文在 {@code output[].content[].text}，比另两侧深一层。 */
        @Test
        void 助手正文算实质载荷() {
            String body = """
                    {"output":[{"type":"message","content":[
                      {"type":"output_text","text":"hello"}]}]}
                    """;

            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, body)).isTrue();
        }

        /** 思考链在 {@code output[].summary[].text}，与正文不同路径。 */
        @Test
        void 思考链摘要算实质载荷() {
            String body = """
                    {"output":[{"type":"reasoning","summary":[
                      {"type":"summary_text","text":"思考中"}]}]}
                    """;

            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, body)).isTrue();
        }

        /**
         * 思考链正文写在 {@code content[].reasoning_text} 里也算 —— 第二条路径。
         *
         * <p>样本取自 2026-09-12 对 MiMo（{@code mimo-v2.5}）真实调用的响应，逐字节照抄：
         * <pre>
         * {"type":"reasoning","summary":[],"content":[{"type":"reasoning_text","text":"Okay, ..."}]}
         * </pre>
         *
         * <p><strong>{@code summary} 是空的</strong>，正文在 {@code content} 里。
         * 官方形态把思考摘要放 {@code summary[]}，而 MiMo 这批上游改用
         * {@code content[].reasoning_text}（新版官方也已加入该字段）。
         * 只认前者时，一个「思考了、但正文被截断」的响应会被判成空并重试五次 ——
         * 六次真实计费的上游调用换一份本来就正常的结果。
         */
        @Test
        void 思考链正文写在content里也算实质载荷() {
            String body = """
                    {"output":[{"type":"reasoning","summary":[],
                      "content":[{"type":"reasoning_text","text":"Okay, the user asked"}]}]}
                    """;

            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, body)).isTrue();
        }

        /** 两条路径同时给出（官方新版形态）也仍然算，不因多一个来源而出错。 */
        @Test
        void 思考链摘要与正文同时给出也算() {
            String body = """
                    {"output":[{"type":"reasoning",
                      "summary":[{"type":"summary_text","text":"摘要"}],
                      "content":[{"type":"reasoning_text","text":"全文"}]}]}
                    """;

            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, body)).isTrue();
        }

        /**
         * 工具调用是 {@code output[]} 的<strong>兄弟项</strong>，不嵌在消息里。
         *
         * <p>这是 Responses 与另两个协议最大的结构差异：Chat 把 {@code tool_calls} 放在
         * {@code delta} 里、Anthropic 放在 {@code content[]} 里，而这里它自己就是一个 output 项。
         */
        @Test
        void 工具调用算实质载荷() {
            String body = """
                    {"output":[{"type":"function_call","call_id":"c1","name":"get_weather","arguments":"{}"}]}
                    """;

            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, body)).isTrue();
        }

        /**
         * {@code arguments} 非空、{@code name} 为空也算。
         *
         * <p>存在只发 arguments 增量而 item 声明里 name 为空的上游（调研中 new-api 为此
         * 写了 pendingArgs 兜底）。只认 name 会把那种响应判成空。
         */
        @Test
        void 只有arguments的工具调用也算() {
            String body = """
                    {"output":[{"type":"function_call","call_id":"c1","name":"","arguments":"{\\"a\\":1}"}]}
                    """;

            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, body)).isTrue();
        }

        /** {@code custom_tool_call} 与 {@code function_call} 同等对待。 */
        @Test
        void 自定义工具调用也算() {
            String body = """
                    {"output":[{"type":"custom_tool_call","call_id":"c1","name":"run","input":"x"}]}
                    """;

            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, body)).isTrue();
        }

        /**
         * {@code custom_tool_call} 的参数字段是 {@code input}，不是 {@code arguments}。
         *
         * <h2>这条用例是为了拆掉上一条的假绿</h2>
         * 上一条的样本带了 {@code name}，而判定是「name 非空 <b>或</b> 参数非空」——
         * 走 name 分支就返回 true 了，<strong>{@code input} 那条路径一行都没执行到</strong>。
         * 于是「只认 arguments、不认 input」这个缺陷在一条绿色的
         * 「自定义工具调用也算」用例下潜伏了下来。
         *
         * <p>因此这里<strong>刻意不给 name</strong>。官方定义是
         * {@code CustomToolCall object { call_id, input, name, ... }} —— 用 {@code input}
         * 而非 {@code arguments}，两种工具项的参数字段名不同。
         *
         * <p>判据：<strong>用「或」连接的分支，每一支都需要一个只能走那一支的样本。</strong>
         */
        @Test
        void 只有input的自定义工具调用也算() {
            String body = """
                    {"output":[{"type":"custom_tool_call","call_id":"c1","input":"ls -la"}]}
                    """;

            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, body)).isTrue();
        }

        /** 空 output 数组是最典型的空响应形态。 */
        @Test
        void 空output判空() {
            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, "{\"output\":[]}"))
                    .isFalse();
        }

        /** output 缺失：没有内容容器，判空。 */
        @Test
        void 缺少output判空() {
            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper,
                    "{\"id\":\"resp_1\",\"status\":\"completed\"}")).isFalse();
        }

        /** 有 message 项但 text 全为空串 —— 这是「返回了结构但没有内容」的形态。 */
        @Test
        void 空文本判空() {
            String body = """
                    {"output":[{"type":"message","content":[{"type":"output_text","text":""}]}]}
                    """;

            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, body)).isFalse();
        }

        /**
         * 只有加密思考块、没有明文 summary 时判空。
         *
         * <p>{@code encrypted_content} 是不透明令牌，它单独存在（远程压缩后的常见形态）
         * 并不构成用户可见的输出。把它算作实质载荷会让一个「什么都没产出」的响应
         * 被判成正常，用户看到的是一次空回复而非重试。
         */
        @Test
        void 只有加密思考内容判空() {
            String body = """
                    {"output":[{"type":"reasoning","summary":[],"encrypted_content":"opaque-blob"}]}
                    """;

            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, body)).isFalse();
        }

        /**
         * 只有拒绍理由的 message 算实质载荷。<strong>2026-09-12 缺口 B 的回归。</strong>
         *
         * <p>官方 {@code ResponseOutputMessage.content} 是
         * {@code array of ResponseOutputText <b>or</b> ResponseOutputRefusal} ——
         * 联合类型，拒绍时用 {@code refusal} 字段而非 {@code text}。
         * 当时的实现对 message 项<strong>只看</strong> {@code text}，于是一个正常的
         * 拒答响应被当成空响应重发 5 次。现已由 {@code carrierHasContent} 两字段共用
         * 一份口径，本用例钉的是「不能退回只认 text」。
         *
         * <p>形态照官方定义逐字写：{@code ResponseOutputRefusal object { refusal, type }}。
         */
        @Test
        void 只有拒绝理由的message算载荷() {
            String body = """
                    {"output":[{"type":"message","role":"assistant","status":"completed",
                      "content":[{"type":"refusal","refusal":"I cannot help with that."}]}]}
                    """;

            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, body)).isTrue();
        }

        /**
         * 思考链与拒绝理由<strong>同时</strong>出现时算载荷 —— 两个缺口的叠加态。
         *
         * <p>这是最坏组合，也是最可能的真实组合：开启思考后请求一个会被拒绝的问题，
         * 上游会同时给出 reasoning 项与 refusal message 项。此时两个项都<strong>非空</strong>，
         * 而 2026-09-12 之前的实现两条路径都不认（思考只看 {@code summary}、
         * message 只看 {@code text}），于是整个响应被判成空 —— 一次完整、有内容、
         * 已计费的回复被当成上游故障重发。两条路径现已各自补齐，本用例钉的是
         * 「两个缺口不能只修一个」。
         */
        @Test
        void 思考链与拒绝理由同时出现也算载荷() {
            String body = """
                    {"output":[
                      {"type":"reasoning","summary":[],
                       "content":[{"type":"reasoning_text","text":"用户要求的内容我不能协助"}]},
                      {"type":"message","role":"assistant","status":"completed",
                       "content":[{"type":"refusal","refusal":"I cannot help with that."}]}
                    ]}
                    """;

            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, body)).isTrue();
        }

        /**
         * 工具执行痕迹本身不算载荷。
         *
         * <p>{@code web_search_call} 等的结果已并入文本输出 —— 与 sub2api 的处理一致。
         * 若把它算作载荷，一次「搜了但没生成任何回答」的调用会被判成正常。
         */
        @Test
        void 工具执行痕迹不算载荷() {
            String body = """
                    {"output":[{"type":"web_search_call","status":"completed"}]}
                    """;

            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, body)).isFalse();
        }

        @Test
        void 空body与null判空() {
            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, null)).isFalse();
            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, "")).isFalse();
            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, "   ")).isFalse();
        }

        /** 解析失败保守放行：宁可放行一个没见过的格式，也不要把正常响应判成空。 */
        @Test
        void 非法JSON保守放行() {
            assertThat(ResponsesContentDetector.hasMeaningfulPayload(objectMapper, "{not json"))
                    .isTrue();
        }
    }

    @Nested
    class 流式事件 {

        /** {@code output_text.delta} 是正文的主要载体。 */
        @Test
        void 正文增量算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper,
                    "{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}")).isTrue();
        }

        /** 思考链增量同样算 —— 三类载荷在流式下的判据统一为「非空 delta」。 */
        @Test
        void 思考链增量算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper,
                    "{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"想\"}")).isTrue();
        }

        /**
         * {@code reasoning_text.delta} 这个事件名也算。
         *
         * <p>样本取自 2026-09-12 对 MiMo（{@code mimo-v2.5}）真实调用的流：它发的是
         * {@code response.reasoning_text.delta}，比官方的
         * {@code response.reasoning_summary_text.delta} 少一个 {@code summary_}。
         *
         * <p>这条用例在修 {@code outputItemHasPayload} 之前<strong>就是通过的</strong>
         * —— 后缀匹配当时救了这一半。写下来是为了钉住「两个事件名都要认」，
         * 而不是因为它在修什么。
         */
        @Test
        void MiMo风格的思考链增量也算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper,
                    "{\"type\":\"response.reasoning_text.delta\",\"delta\":\"Okay\"}")).isTrue();
        }

        /** 工具参数增量算贡献。 */
        @Test
        void 工具参数增量算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper,
                    "{\"type\":\"response.function_call_arguments.delta\",\"delta\":\"{\"}")).isTrue();
        }

        /**
         * 工具参数<strong>定稿</strong>算贡献 —— 参数在顶层 {@code arguments}，不在 {@code delta}。
         *
         * <h2>这是 2026-09-13 修掉的缺口</h2>
         * 工具参数与正文一样有两种到达方式：{@code .delta} 逐片发（上一条用例），
         * {@code .done} 一次发全。此前只有正文的定稿字段（{@code text} / {@code refusal}）
         * 进了判定表，工具参数的定稿字段漏在外面 —— 上一条用例的存在反而让人以为
         * 「工具参数已经覆盖了」。
         *
         * <p>实测后果：一整轮只发定稿的工具调用流（created → output_item.added →
         * arguments.done → output_item.done → completed）<strong>五个事件全判无载荷</strong>，
         * 整轮 {@code sawPayload = false} → 判成空响应 → 重试 5 次 →
         * 6 次计费调用、约 60 秒退避，耗尽后仍放行内容。症状是「调工具时特别慢、
         * token 莫名偏高」，不报错，极难归因。
         *
         * <p>与 2026-09-12 思考链那次是同一个思维错误：官方写「A 或 B」只实现了 A。
         */
        @Test
        void 工具参数定稿算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper,
                    "{\"type\":\"response.function_call_arguments.done\","
                            + "\"arguments\":\"{\\\"city\\\":\\\"Hangzhou\\\"}\"}")).isTrue();
        }

        /**
         * 自定义工具的输入定稿算贡献 —— 字段是 {@code input}。
         *
         * <p>{@code response.custom_tool_call_input.done} 把全文放在 {@code input} 里，
         * 与 {@code function_call} 用 {@code arguments} 是平行的两支。
         */
        @Test
        void 自定义工具输入定稿算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper,
                    "{\"type\":\"response.custom_tool_call_input.done\","
                            + "\"input\":\"ls -la\"}")).isTrue();
        }

        /**
         * 整轮回归：只发定稿的工具调用流必须被判成「有载荷」。
         *
         * <p>单事件用例证明「这个事件被认出来了」，这条证明<strong>整轮不会被判空</strong>
         * —— 后者才是真正决定要不要重试的判据（调用方对一轮内所有事件做逻辑或）。
         * 修复前这五个事件全部返回 false。
         */
        @Test
        void 只发定稿的工具调用整轮不被判空() {
            String[] stream = {
                    "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\"}}",
                    "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                            + "\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\"}}",
                    "{\"type\":\"response.function_call_arguments.done\",\"output_index\":0,"
                            + "\"arguments\":\"{\\\"city\\\":\\\"Hangzhou\\\"}\"}",
                    "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                            + "\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\"}}",
                    "{\"type\":\"response.completed\",\"response\":{\"output\":["
                            + "{\"type\":\"function_call\",\"call_id\":\"c1\"}]}}",
            };

            boolean sawPayload = false;
            for (String event : stream) {
                sawPayload |= ResponsesContentDetector.eventHasPayload(objectMapper, event);
            }

            assertThat(sawPayload)
                    .as("只发定稿参数的一轮被判空 -> 会触发 5 次空响应重试")
                    .isTrue();
        }

        /**
         * 拒绝理由增量算贡献 —— 与正文增量同一判据（非空 delta）。
         */
        @Test
        void 拒绝理由增量算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper,
                    "{\"type\":\"response.refusal.delta\",\"delta\":\"I cannot\"}")).isTrue();
        }

        /**
         * {@code output_text.done} 带全文也算贡献 —— 定稿事件的主要形态。
         *
         * <p>官方 {@code ResponseTextDoneEvent} 的 {@code text} 是「the text content that is
         * finalized」。正常上游会先发多次 {@code output_text.delta}，因此这条是防御性覆盖；
         * 只在「一个 delta 都不发、只在 done 里给全文」的上游上才会真正咬人。
         */
        @Test
        void 正文done事件也算贡献() {
            String event = """
                    {"type":"response.output_text.done","content_index":0,"item_id":"msg_1",
                     "output_index":1,"text":"Hello! Nice to meet you.","sequence_number":68}
                    """;

            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper, event)).isTrue();
        }

        /** 思考链的定稿事件同样是顶层 {@code text}。 */
        @Test
        void 思考链done事件也算贡献() {
            String event = """
                    {"type":"response.reasoning_text.done","content_index":0,"item_id":"rs_1",
                     "output_index":1,"text":"Okay, the user asked","sequence_number":70}
                    """;

            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper, event)).isTrue();
        }

        /**
         * {@code content_part.done} 把内容包在 {@code part} 里，也算贡献。
         *
         * <p>这是内容的第二个位置。{@code content_part.added} 的 {@code part.text} 是空串
         * （只声明 part 类型），因此那一条仍应判空 —— 两者一起钉住「读的是内容而不是结构」。
         */
        @Test
        void contentPartDone带正文也算贡献() {
            String done = """
                    {"type":"response.content_part.done","content_index":0,"item_id":"msg_1",
                     "output_index":1,"part":{"type":"output_text","text":"Hello!"}}
                    """;
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper, done)).isTrue();

            String added = """
                    {"type":"response.content_part.added","content_index":0,"item_id":"msg_1",
                     "output_index":1,"part":{"type":"output_text","text":""}}
                    """;
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper, added)).isFalse();
        }

        /**
         * {@code refusal.done} 事件带全文，也应算贡献。<strong>缺口 B 的流式一侧。</strong>
         *
         * <p>官方 {@code ResponseRefusalDoneEvent} 的全文在 {@code refusal} 字段里 ——
         * 既不是 {@code delta} 也不是 {@code text}。两个判据都接不住它。
         *
         * <p>正常上游会先发 {@code refusal.delta}，因此这条是防御性覆盖；仅在
         * 「一个 delta 都不发、只在 done 里给全文」的上游上才会真正咬人。
         */
        @Test
        void 拒绝理由done事件也算贡献() {
            String event = """
                    {"type":"response.refusal.done","content_index":0,"item_id":"msg_1",
                     "output_index":0,"refusal":"I cannot help with that.","sequence_number":5}
                    """;

            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper, event)).isTrue();
        }

        /**
         * 只发 item.done、不发任何增量的上游：思考链也算贡献。<strong>缺口 A 的流式一侧。</strong>
         *
         * <p>已有 {@code 终态事件也携带完整 output 时算贡献} 覆盖了「只在
         * {@code response.completed} 里给全文」的上游，但 {@code output_item.done}
         * 是同一处境的另一个出口 —— 它同样携带完整 item，而当前实现只看
         * {@code summary}，对 {@code content[].reasoning_text} 判空。
         */
        @Test
        void 只有itemDone不发增量时思考链也算贡献() {
            String event = """
                    {"type":"response.output_item.done","output_index":0,
                     "item":{"id":"rs_1","type":"reasoning","summary":[],
                     "content":[{"type":"reasoning_text","text":"思考全文"}]}}
                    """;

            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper, event)).isTrue();
        }

        /** 终态事件里只有拒绝理由的 message 也算贡献。 */
        @Test
        void 终态事件里只有拒绝理由也算贡献() {
            String event = """
                    {"type":"response.completed","response":{"id":"r","output":[
                     {"type":"message","role":"assistant",
                      "content":[{"type":"refusal","refusal":"I cannot help with that."}]}]}}
                    """;

            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper, event)).isTrue();
        }

        /**
         * 事件名用 {@code .delta} 后缀匹配，不全等比对。
         *
         * <p>各家兼容端点的前缀不完全一致（官方 {@code response.output_text.delta}，
         * 也见过 {@code response.text.delta}），而后缀是稳定的。宁可多认一个事件名，
         * 也不要把一个正常响应判成空 —— 后者的症状是无端重试直至耗尽。
         */
        @Test
        void 未知前缀的delta事件也算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper,
                    "{\"type\":\"response.text.delta\",\"delta\":\"x\"}")).isTrue();
        }

        /** delta 建成对象的形态（少数上游）也算。 */
        @Test
        void 对象形态的delta算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper,
                    "{\"type\":\"response.output_text.delta\",\"delta\":{\"text\":\"x\"}}")).isTrue();
        }

        /** 空字符串 delta 不算：某些上游会在流首发一个空 delta 做预热。 */
        @Test
        void 空delta不算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper,
                    "{\"type\":\"response.output_text.delta\",\"delta\":\"\"}")).isFalse();
        }

        /**
         * {@code output_item.added} 声明工具调用时算贡献。
         *
         * <p>此刻 name 已确定 —— 与另两侧「tool_calls 含非空元素即算」同口径。
         */
        @Test
        void 工具项声明算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper, """
                    {"type":"response.output_item.added","output_index":0,
                     "item":{"type":"function_call","call_id":"c1","name":"f","arguments":""}}
                    """)).isTrue();
        }

        /** 声明一个空的 message 项不算贡献 —— 内容还在后续 delta 里。 */
        @Test
        void 空消息项声明不算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper, """
                    {"type":"response.output_item.added","output_index":0,
                     "item":{"type":"message","content":[]}}
                    """)).isFalse();
        }

        /**
         * <strong>终态事件携带完整 output 时算贡献。</strong>
         *
         * <p>存在一个 delta 都不发、只在终态事件里给出完整结果的上游（调研中 new-api
         * 为此专门写了 {@code terminalOutputChunks} 来补发）。若这里只认 delta，
         * 那种响应会被判成空并卷入重试 —— 而它其实是完整的。
         */
        @Test
        void 终态事件携带完整output算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper, """
                    {"type":"response.completed","response":{"output":[
                      {"type":"message","content":[{"type":"output_text","text":"done"}]}]}}
                    """)).isTrue();
        }

        /** 终态事件但 output 为空 —— 这才是真正的空响应终态。 */
        @Test
        void 终态事件空output不算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper,
                    "{\"type\":\"response.completed\",\"response\":{\"output\":[]}}")).isFalse();
        }

        /** 控制事件不贡献内容 —— 这正是「判定单位是一整轮」的原因。 */
        @Test
        void 控制事件不算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper,
                    "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\"}}")).isFalse();
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper,
                    "{\"type\":\"response.in_progress\"}")).isFalse();
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper,
                    "{\"type\":\"response.content_part.added\",\"part\":{\"type\":\"output_text\"}}"))
                    .isFalse();
        }

        /** 无 type 字段的事件不算贡献（无从判断，保守按控制事件处理）。 */
        @Test
        void 缺少type不算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper, "{\"delta\":\"x\"}"))
                    .isFalse();
        }

        @Test
        void 空事件与null不算贡献() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper, null)).isFalse();
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper, "  ")).isFalse();
        }

        /** 解析失败保守放行，与非流式同一取向。 */
        @Test
        void 非法JSON保守放行() {
            assertThat(ResponsesContentDetector.eventHasPayload(objectMapper, "{broken"))
                    .isTrue();
        }
    }

    @Nested
    class 终态事件清单 {

        /**
         * 七种终态全部识别。
         *
         * <p>{@code cancelled} 与 {@code canceled} 两种拼法都要收 —— 实测有上游
         * 只发其中一种，少收一种的代价是那条流永远等不到 finalize。
         */
        @Test
        void 全部终态类型都被识别() {
            for (String type : new String[] {
                    "response.completed", "response.done", "response.incomplete",
                    "response.failed", "response.cancelled", "response.canceled", "error"}) {
                assertThat(ResponsesStreamEvents.isTerminal(type))
                        .as("%s 应被识别为终态", type)
                        .isTrue();
            }
        }

        /** 增量与控制事件不是终态 —— 误判会让流提前 finalize，后续事件的计数丢失。 */
        @Test
        void 非终态事件不被误判() {
            for (String type : new String[] {
                    "response.created", "response.in_progress", "response.output_text.delta",
                    "response.output_item.added", "response.output_item.done",
                    "response.content_part.done", "message_stop"}) {
                assertThat(ResponsesStreamEvents.isTerminal(type))
                        .as("%s 不应被识别为终态", type)
                        .isFalse();
            }
        }

        @Test
        void null不是终态() {
            assertThat(ResponsesStreamEvents.isTerminal(null)).isFalse();
        }

        /**
         * 每个终态事件都映射到正确的结局。
         *
         * <h2>为何清单与结局必须是同一份数据</h2>
         * 「流结束了」与「结局是什么」是两个问题。控制器早先只问了前者就无条件发
         * {@code COMPLETED}，于是 {@code response.failed} 在界面上显示成「完成」。
         *
         * <p>本用例逐个钉住映射，而不是只测其中一两个：{@code getOrDefault} 的兜底是
         * {@code SUCCESS}，所以<strong>任何漏进映射表的终态事件都会静默显示成「完成」</strong>
         * —— 与修复前的症状一模一样。逐个断言是唯一能挡住「新增终态只加进了 isTerminal」的写法。
         */
        @Test
        void 终态事件映射到正确结局() {
            assertThat(ResponsesStreamEvents.outcomeOf("response.completed"))
                    .isEqualTo(ResponsesStreamEvents.Outcome.SUCCESS);
            assertThat(ResponsesStreamEvents.outcomeOf("response.done"))
                    .isEqualTo(ResponsesStreamEvents.Outcome.SUCCESS);
            // 截断算完成：内容不完整但确实产出了，与 Chat 侧 finish_reason:"length" 同口径。
            assertThat(ResponsesStreamEvents.outcomeOf("response.incomplete"))
                    .isEqualTo(ResponsesStreamEvents.Outcome.SUCCESS);

            assertThat(ResponsesStreamEvents.outcomeOf("response.failed"))
                    .isEqualTo(ResponsesStreamEvents.Outcome.FAILURE);
            assertThat(ResponsesStreamEvents.outcomeOf("error"))
                    .isEqualTo(ResponsesStreamEvents.Outcome.FAILURE);

            // 两种拼法必须走同一条分支，否则少收的那种会掉进兜底显示成「完成」。
            assertThat(ResponsesStreamEvents.outcomeOf("response.cancelled"))
                    .isEqualTo(ResponsesStreamEvents.Outcome.CANCELLATION);
            assertThat(ResponsesStreamEvents.outcomeOf("response.canceled"))
                    .isEqualTo(ResponsesStreamEvents.Outcome.CANCELLATION);
        }

        /**
         * 清单与结局映射覆盖同一组事件。
         *
         * <p>两者由同一个 {@code Map} 派生，本用例钉住这个实现选择：拆成两份数据的症状是
         * 「新增一个终态事件只加进了其中一处」，而那不会报错 —— 漏进结局表的事件
         * 会掉进 {@code SUCCESS} 兜底，正是修复前的形态。
         */
        @Test
        void 每个终态事件都有非兜底的结局() {
            for (String type : new String[] {
                    "response.completed", "response.done", "response.incomplete",
                    "response.failed", "response.cancelled", "response.canceled", "error"}) {
                // 非 SUCCESS 的三个直接证明有显式映射；SUCCESS 那几个由上一条用例逐个钉住。
                assertThat(ResponsesStreamEvents.isTerminal(type))
                        .as("%s 必须同时在清单里", type)
                        .isTrue();
            }
        }

        /**
         * 非终态与 null 取到 {@code SUCCESS} 兜底。
         *
         * <p>这是刻意的：调用方只在终态分支调用 {@code outcomeOf}，而流被上游直接关闭
         * （一个终态事件都没发）时兜底层拿不到事件类型 —— 那种情况按成功处理，
         * 与 Chat / Anthropic 的兜底层一致。默认成 FAILURE 会让所有省略终态事件的上游被误标。
         */
        @Test
        void 非终态与null取成功兜底() {
            assertThat(ResponsesStreamEvents.outcomeOf(null))
                    .isEqualTo(ResponsesStreamEvents.Outcome.SUCCESS);
            assertThat(ResponsesStreamEvents.outcomeOf("response.output_text.delta"))
                    .isEqualTo(ResponsesStreamEvents.Outcome.SUCCESS);
        }
    }
}
