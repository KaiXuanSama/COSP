package com.kaixuan.copilot_ollama_proxy.application.logging;

import com.kaixuan.copilot_ollama_proxy.application.logging.CallLogFilter.MatchMode;
import com.kaixuan.copilot_ollama_proxy.application.logging.CallLogFilter.StatusClass;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallLogFilterTests {

    private static CallLogFilter parseStatus(String... status) {
        return CallLogFilter.parse(null, null, List.of(status), null, null, null, null);
    }

    @Nested
    class Empty {

        /** 全部缺省或空白时等价于 NONE，列表查询因此与引入检索前一致。 */
        @Test
        void blankParamsAreTreatedAsAbsent() {
            CallLogFilter filter = CallLogFilter.parse(" ", "", List.of(" ", ""), "  ", null, "", " ");
            assertThat(filter.isEmpty()).isTrue();
            assertThat(filter).isEqualTo(CallLogFilter.NONE);
        }

        @Test
        void nullStatusListIsAllowed() {
            assertThat(CallLogFilter.parse(null, null, null, null, null, null, null).isEmpty()).isTrue();
        }
    }

    @Nested
    class Time {

        @Test
        void parsesSecondPrecisionLocalTime() {
            CallLogFilter filter = CallLogFilter.parse("2026-11-01T08:00:00", "2026-11-01T09:30:15",
                    null, null, null, null, null);
            assertThat(filter.from()).isEqualTo(LocalDateTime.of(2026, 11, 1, 8, 0, 0));
            assertThat(filter.toText()).isEqualTo("2026-11-01T09:30:15");
            assertThat(filter.isEmpty()).isFalse();
        }

        /** from 等于 to 是合法的单秒区间。 */
        @Test
        void equalBoundsAreAllowed() {
            CallLogFilter filter = CallLogFilter.parse("2026-11-01T08:00:00", "2026-11-01T08:00:00",
                    null, null, null, null, null);
            assertThat(filter.fromText()).isEqualTo(filter.toText());
        }

        /**
         * 只接受与 created_at 存储格式一致的写法。
         *
         * <p>created_at 是 TEXT，比较是字典序：带毫秒的 {@code ...:00.500} 与库内
         * {@code ...:00} 比较会把同一秒的行排除，带 Z 的输入会让人误以为按 UTC 比较。
         */
        @Test
        void rejectsOtherFormats() {
            for (String raw : List.of("2026-11-01 08:00:00", "2026-11-01T08:00", "2026-11-01T08:00:00.500",
                    "2026-11-01T08:00:00Z", "2026-11-01", "2026-02-30T00:00:00")) {
                assertThatThrownBy(() -> CallLogFilter.parse(raw, null, null, null, null, null, null))
                        .as(raw)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("from");
            }
        }

        @Test
        void rejectsFromAfterTo() {
            assertThatThrownBy(() -> CallLogFilter.parse("2026-11-01T08:00:01", "2026-11-01T08:00:00",
                    null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("from 不能晚于 to");
        }
    }

    @Nested
    class Status {

        @Test
        void acceptsClassesAndCodesTogether() {
            CallLogFilter filter = parseStatus("2xx", "5XX", "429", "-1");
            assertThat(filter.statusClasses()).containsExactlyInAnyOrder(StatusClass.SUCCESS, StatusClass.SERVER_ERROR);
            assertThat(filter.statusCodes()).containsExactly(429, -1);
        }

        /** 前端以重复参数提交，手写 URL 时也可能用逗号分隔，两种都接受。 */
        @Test
        void acceptsCommaSeparatedValues() {
            CallLogFilter filter = parseStatus("4xx, 503", "502");
            assertThat(filter.statusClasses()).containsExactly(StatusClass.CLIENT_ERROR);
            assertThat(filter.statusCodes()).containsExactly(503, 502);
        }

        @Test
        void duplicatesCollapse() {
            CallLogFilter filter = parseStatus("429", "429", "2xx", "2xx");
            assertThat(filter.statusCodes()).containsExactly(429);
            assertThat(filter.statusClasses()).containsExactly(StatusClass.SUCCESS);
        }

        @Test
        void rejectsUnknownTokens() {
            for (String raw : List.of("3xx", "ok", "0", "-2", "600", "99")) {
                assertThatThrownBy(() -> parseStatus(raw))
                        .as(raw)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("status");
            }
        }

        @Test
        void rejectsTooManyTokens() {
            String[] tokens = new String[CallLogFilter.MAX_STATUS_TOKENS + 1];
            for (int i = 0; i < tokens.length; i++) {
                tokens[i] = String.valueOf(400 + i);
            }
            assertThatThrownBy(() -> parseStatus(tokens))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("最多");
        }
    }

    @Nested
    class Text {

        @Test
        void defaultsToExactAndTrims() {
            CallLogFilter filter = CallLogFilter.parse(null, null, null, " stepfun ", null, "glm-5", null);
            assertThat(filter.provider()).isEqualTo("stepfun");
            assertThat(filter.providerMatch()).isEqualTo(MatchMode.EXACT);
            assertThat(filter.model()).isEqualTo("glm-5");
            assertThat(filter.modelMatch()).isEqualTo(MatchMode.EXACT);
        }

        @Test
        void parsesMatchModeCaseInsensitively() {
            CallLogFilter filter = CallLogFilter.parse(null, null, null, "step", "Contains", "glm", "EXACT");
            assertThat(filter.providerMatch()).isEqualTo(MatchMode.CONTAINS);
            assertThat(filter.modelMatch()).isEqualTo(MatchMode.EXACT);
        }

        @Test
        void rejectsUnknownMatchMode() {
            assertThatThrownBy(() -> CallLogFilter.parse(null, null, null, "x", "prefix", null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("providerMatch");
        }

        @Test
        void rejectsOverlongText() {
            String overlong = "m".repeat(CallLogFilter.MAX_TEXT_LENGTH + 1);
            assertThatThrownBy(() -> CallLogFilter.parse(null, null, null, null, null, overlong, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("model");
        }

        /** 只有匹配方式而没有值时不构成条件。 */
        @Test
        void matchModeAloneIsNotACondition() {
            assertThat(CallLogFilter.parse(null, null, null, null, "contains", null, "contains").isEmpty()).isTrue();
        }
    }
}
