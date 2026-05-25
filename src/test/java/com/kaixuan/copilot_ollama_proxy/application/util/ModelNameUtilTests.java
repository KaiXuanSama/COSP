package com.kaixuan.copilot_ollama_proxy.application.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ModelNameUtil 测试")
class ModelNameUtilTests {

    // ========== parse() ==========

    @Nested
    @DisplayName("parse() — 解析带供应商前缀的模型名")
    class ParseTests {

        @Test
        @DisplayName("标准前缀: [ProviderKey]modelName")
        void parseStandardPrefix() {
            var result = ModelNameUtil.parse("[DeepSeek]deepseek-v4-flash");
            assertThat(result.providerKey()).isEqualTo("DeepSeek");
            assertThat(result.modelName()).isEqualTo("deepseek-v4-flash");
        }

        @Test
        @DisplayName("后缀带中括号: [mimo]mimo-v2.5-pro[1m] → 后缀保留")
        void parseSuffixBracketPreserved() {
            var result = ModelNameUtil.parse("[mimo]mimo-v2.5-pro[1m]");
            assertThat(result.providerKey()).isEqualTo("mimo");
            assertThat(result.modelName()).isEqualTo("mimo-v2.5-pro[1m]");
        }

        @Test
        @DisplayName("后缀带多个中括号: [mimo]model-a[latest][v2] → 后缀全部保留")
        void parseMultipleSuffixBracketsPreserved() {
            var result = ModelNameUtil.parse("[mimo]model-a[latest][v2]");
            assertThat(result.providerKey()).isEqualTo("mimo");
            assertThat(result.modelName()).isEqualTo("model-a[latest][v2]");
        }

        @Test
        @DisplayName("无前缀时返回 null providerKey")
        void parseNoPrefix() {
            var result = ModelNameUtil.parse("deepseek-v4-flash");
            assertThat(result.providerKey()).isNull();
            assertThat(result.modelName()).isEqualTo("deepseek-v4-flash");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t"})
        @DisplayName("null 或空白字符串返回 null providerKey")
        void parseNullOrBlank(String input) {
            var result = ModelNameUtil.parse(input);
            assertThat(result.providerKey()).isNull();
            assertThat(result.modelName()).isEqualTo(input);
        }

        @Test
        @DisplayName("只有前缀无模型名: [mimo] → 视为无前缀")
        void parsePrefixOnlyNoModel() {
            // endIndex < fullModelName.length() - 1 不满足 → 无前缀模式
            var result = ModelNameUtil.parse("[mimo]");
            assertThat(result.providerKey()).isNull();
            assertThat(result.modelName()).isEqualTo("[mimo]");
        }

        @Test
        @DisplayName("空前缀: []model → endIndex=1 不满足 >1 → 无前缀模式")
        void parseEmptyProviderKey() {
            var result = ModelNameUtil.parse("[]model");
            assertThat(result.providerKey()).isNull();
            assertThat(result.modelName()).isEqualTo("[]model");
        }

        @Test
        @DisplayName("无前缀 + 后缀带中括号: model-v1[8k][latest] → 后缀保留")
        void parseNoPrefixWithSuffixBrackets() {
            var result = ModelNameUtil.parse("model-v1[8k][latest]");
            assertThat(result.providerKey()).isNull();
            assertThat(result.modelName()).isEqualTo("model-v1[8k][latest]");
        }
    }

    // ========== stripPrefix() ==========

    @Nested
    @DisplayName("stripPrefix() — 去除供应商前缀")
    class StripPrefixTests {

        @Test
        @DisplayName("有前缀时去除")
        void stripPrefixWithPrefix() {
            assertThat(ModelNameUtil.stripPrefix("[DeepSeek]deepseek-v4-flash"))
                    .isEqualTo("deepseek-v4-flash");
        }

        @Test
        @DisplayName("后缀带中括号时仅去除前缀")
        void stripPrefixPreservesSuffix() {
            assertThat(ModelNameUtil.stripPrefix("[mimo]mimo-v2.5-pro[1m]"))
                    .isEqualTo("mimo-v2.5-pro[1m]");
        }

        @Test
        @DisplayName("无前缀时原样返回")
        void stripPrefixNoPrefix() {
            assertThat(ModelNameUtil.stripPrefix("deepseek-v4-flash"))
                    .isEqualTo("deepseek-v4-flash");
        }

        @Test
        @DisplayName("无前缀 + 后缀带中括号时原样返回")
        void stripPrefixNoPrefixWithSuffixBrackets() {
            assertThat(ModelNameUtil.stripPrefix("model-v1[8k][latest]"))
                    .isEqualTo("model-v1[8k][latest]");
        }
    }

    // ========== hasPrefix() ==========

    @Nested
    @DisplayName("hasPrefix() — 判断是否有供应商前缀")
    class HasPrefixTests {

        @Test
        @DisplayName("有前缀返回 true")
        void hasPrefixTrue() {
            assertThat(ModelNameUtil.hasPrefix("[DeepSeek]deepseek-v4-flash")).isTrue();
        }

        @Test
        @DisplayName("后缀带中括号但无前缀返回 false")
        void hasPrefixFalseWithSuffix() {
            assertThat(ModelNameUtil.hasPrefix("model-v1[8k]")).isFalse();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "model-a"})
        @DisplayName("null/空白/无括号返回 false")
        void hasPrefixFalse(String input) {
            assertThat(ModelNameUtil.hasPrefix(input)).isFalse();
        }
    }

    // ========== buildPrefixedName() ==========

    @Nested
    @DisplayName("buildPrefixedName() — 构建带前缀的模型名")
    class BuildPrefixedNameTests {

        @Test
        @DisplayName("标准构建")
        void buildStandard() {
            assertThat(ModelNameUtil.buildPrefixedName("mimo", "mimo-v2.5-pro"))
                    .isEqualTo("[mimo]mimo-v2.5-pro");
        }

        @Test
        @DisplayName("模型名后缀带中括号时原样拼接")
        void buildWithSuffixBrackets() {
            assertThat(ModelNameUtil.buildPrefixedName("mimo", "mimo-v2.5-pro[1m]"))
                    .isEqualTo("[mimo]mimo-v2.5-pro[1m]");
        }

        @Test
        @DisplayName("providerKey 为 null 时返回原始 modelName")
        void buildWithNullProviderKey() {
            assertThat(ModelNameUtil.buildPrefixedName(null, "model-a"))
                    .isEqualTo("model-a");
        }

        @Test
        @DisplayName("providerKey 为空白时返回原始 modelName")
        void buildWithBlankProviderKey() {
            assertThat(ModelNameUtil.buildPrefixedName("  ", "model-a"))
                    .isEqualTo("model-a");
        }
    }

    // ========== extractProviderKey() ==========

    @Nested
    @DisplayName("extractProviderKey() — 提取供应商前缀")
    class ExtractProviderKeyTests {

        @Test
        @DisplayName("有前缀时提取 providerKey")
        void extractWithPrefix() {
            assertThat(ModelNameUtil.extractProviderKey("[DeepSeek]deepseek-v4-flash"))
                    .isEqualTo("DeepSeek");
        }

        @Test
        @DisplayName("无前缀时返回 null")
        void extractWithoutPrefix() {
            assertThat(ModelNameUtil.extractProviderKey("deepseek-v4-flash"))
                    .isNull();
        }

        @Test
        @DisplayName("后缀带中括号时不影响前缀提取")
        void extractWithSuffixBrackets() {
            assertThat(ModelNameUtil.extractProviderKey("[mimo]model-a[latest]"))
                    .isEqualTo("mimo");
        }
    }

    // ========== hasProviderPrefix() ==========

    @Nested
    @DisplayName("ParseResult.hasProviderPrefix() — 判断是否包含供应商前缀")
    class HasProviderPrefixTests {

        @Test
        @DisplayName("有前缀时返回 true")
        void withPrefix() {
            assertThat(ModelNameUtil.parse("[mimo]model-a").hasProviderPrefix()).isTrue();
        }

        @Test
        @DisplayName("无前缀时返回 false")
        void withoutPrefix() {
            assertThat(ModelNameUtil.parse("model-a").hasProviderPrefix()).isFalse();
        }

        @Test
        @DisplayName("null 输入时返回 false")
        void nullInput() {
            assertThat(ModelNameUtil.parse(null).hasProviderPrefix()).isFalse();
        }
    }
}
