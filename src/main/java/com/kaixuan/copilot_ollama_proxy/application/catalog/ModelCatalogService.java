package com.kaixuan.copilot_ollama_proxy.application.catalog;

import java.util.List;

/**
 * 模型目录服务 —— 聚合所有已启用供应商下已启用的模型，统一供 api 层使用。
 *
 * 将"列出可用模型"这一业务逻辑（查询、去前缀/拼前缀、能力读取）从 Controller
 * 下沉到 application 层，避免 OllamaApiController 与 OpenAiController 各写一遍，
 * 同时让 api 层不再直接依赖 infrastructure 的 ProviderConfigRepository。
 */
public interface ModelCatalogService {

    /**
     * 列出当前所有可用模型（已启用供应商 + 已启用模型）。
     *
     * @return 可用模型描述符列表，可能为空
     */
    List<AvailableModel> listAvailableModels();
}
