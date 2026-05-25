package com.kaixuan.copilot_ollama_proxy.application.util;

/**
 * 模型名称解析工具类。
 *
 * 支持带供应商前缀的模型名称格式：[ProviderKey] modelName
 * 例如：[DeepSeek] deepseek-v4-flash、[Uumit] deepseek-v4-flash
 *
 * 这种格式用于区分同一模型由不同供应商提供的场景，避免路由歧义。
 */
public final class ModelNameUtil {

    /**
     * 解析模型名称，提取供应商前缀和实际模型名。
     * 兼容无空格格式（旧版本）和带空格格式（新版本）。
     * 例如：
     * - "[DeepSeek]deepseek-v4-flash" → providerKey="DeepSeek", modelName="deepseek-v4-flash"
     * - "[DeepSeek] deepseek-v4-flash" → providerKey="DeepSeek", modelName="deepseek-v4-flash"
     * - "[mimo] mimo-v2.5-pro[1m]" → providerKey="mimo", modelName="mimo-v2.5-pro[1m]"
     *
     * @param fullModelName 完整模型名称，可能带前缀
     * @return 解析结果，包含 providerKey 和 modelName；如果无前缀则 providerKey 为 null
     */
    public static ParseResult parse(String fullModelName) {
        if (fullModelName == null || fullModelName.isBlank()) {
            return new ParseResult(null, fullModelName);
        }

        if (fullModelName.startsWith("[") && fullModelName.contains("]")) {
            int endIndex = fullModelName.indexOf("]");
            if (endIndex > 1 && endIndex < fullModelName.length() - 1) {
                String providerKey = fullModelName.substring(1, endIndex);
                String remainder = fullModelName.substring(endIndex + 1);
                // 兼容带空格格式：[ProviderKey] modelName
                String modelName = remainder.startsWith(" ") ? remainder.substring(1) : remainder;
                return new ParseResult(providerKey, modelName);
            }
        }

        return new ParseResult(null, fullModelName);
    }

    /**
     * 构建带供应商前缀的模型名称。
     *
     * @param providerKey 供应商标识
     * @param modelName 模型名称
     * @return 带前缀的完整模型名称，如 "[DeepSeek] deepseek-v4-flash"
     */
    public static String buildPrefixedName(String providerKey, String modelName) {
        if (providerKey == null || providerKey.isBlank() || modelName == null || modelName.isBlank()) {
            return modelName;
        }
        return "[" + providerKey + "] " + modelName;
    }

    /**
     * 判断模型名称是否带供应商前缀。
     *
     * @param fullModelName 完整模型名称
     * @return 如果带前缀返回 true，否则返回 false
     */
    public static boolean hasPrefix(String fullModelName) {
        if (fullModelName == null || fullModelName.isBlank()) {
            return false;
        }
        return fullModelName.startsWith("[") && fullModelName.contains("]");
    }

    /**
     * 提取实际模型名称（去除前缀）。
     *
     * @param fullModelName 完整模型名称，可能带前缀
     * @return 实际模型名称
     */
    public static String stripPrefix(String fullModelName) {
        return parse(fullModelName).modelName();
    }

    /**
     * 提取供应商前缀。
     *
     * @param fullModelName 完整模型名称，可能带前缀
     * @return 供应商标识，如果无前缀则返回 null
     */
    public static String extractProviderKey(String fullModelName) {
        return parse(fullModelName).providerKey();
    }

    /**
     * 模型名称解析结果。
     */
    public record ParseResult(String providerKey, String modelName) {
        /**
         * 是否包含供应商前缀。
         */
        public boolean hasProviderPrefix() {
            return providerKey != null && !providerKey.isBlank();
        }
    }
}