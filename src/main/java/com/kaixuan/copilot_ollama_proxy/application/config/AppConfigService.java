package com.kaixuan.copilot_ollama_proxy.application.config;

/**
 * 应用运行配置服务 —— 领域接口，封装 app_config 键值对的读写。
 *
 * 供 api / application 层读取运行时配置（如 Ollama 伪装版本号），
 * 不直接依赖 infrastructure 的具体 Repository 实现。
 */
public interface AppConfigService {

    /**
     * 读取配置项的值。
     *
     * @param key 配置键
     * @return 配置值，不存在时返回 null
     */
    String findValue(String key);

    /**
     * 保存（UPSERT）配置项。
     *
     * @param key   配置键
     * @param value 配置值
     */
    void saveValue(String key, String value);
}
