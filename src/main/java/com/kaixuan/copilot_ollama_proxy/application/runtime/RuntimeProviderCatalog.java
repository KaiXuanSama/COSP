package com.kaixuan.copilot_ollama_proxy.application.runtime;

import java.util.List;

/**
 * 运行时 Provider 目录。
 * 应用层通过该接口读取可路由的 Provider 配置，而不是直接依赖底层仓库返回的 Map 结构。
 */
public interface RuntimeProviderCatalog {

    // 获取当前所有激活的 Provider 配置列表，应用层通过该接口获取可用的 Provider 配置，而不是直接依赖底层仓库返回的 Map 结构。
    List<ProviderRuntimeConfiguration> getActiveProviders();

    /**
     * 根据 providerKey 获取对应的 Provider 配置，应用层通过该接口获取特定 Provider 的配置，而不是直接依赖底层仓库返回的 Map 结构。
     * @param providerKey Provider 的唯一标识键
     * @return 如果找到对应的 Provider 配置则返回，否则返回 null
     */
    default ProviderRuntimeConfiguration getActiveProvider(String providerKey) {
        return getActiveProviders() // 获取当前所有激活的 Provider 配置列表。
                .stream() // 将 Provider 配置列表转换为 Stream 以便进行过滤操作。
                .filter(provider -> provider.providerKey().equals(providerKey)) // 过滤出 providerKey 匹配的 Provider 配置项。
                .findFirst() // 尝试获取第一个匹配的 Provider 配置项，理论上应该只有一个匹配项。
                .orElse(null); // 如果没有找到匹配的 Provider 配置项，则返回 null。
    }
}