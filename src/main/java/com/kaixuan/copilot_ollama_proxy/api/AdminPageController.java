package com.kaixuan.copilot_ollama_proxy.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理后台页面控制器 — 负责渲染登录页和各管理子页面。
 * <p>
 * 页面采用 Thymeleaf 片段化组件设计：
 * - config.html 为布局壳（侧边栏 + 页头 + 内容占位）
 * - 各子页面（overview、settings、logs、status）为独立 HTML 文件
 * - 通过 th:insert 将子页面内容注入布局壳
 */
@Controller
public class AdminPageController {

    @Value("${server.port:11434}")
    private int serverPort;

    @Value("${proxy.provider:longcat}")
    private String proxyProvider;

    @Value("${proxy.upstream-chat-service:openai}")
    private String upstreamChatService;

    @Value("${ollama.version:0.6.4}")
    private String ollamaVersion;

    @Value("${longcat.api-key:}")
    private String longcatApiKey;

    @Value("${longcat.base-url:https://api.longcat.chat}")
    private String longcatBaseUrl;

    @Value("${longcat.default-model:LongCat-Flash-Chat}")
    private String longcatDefaultModel;

    @Value("${mimo.api-key:}")
    private String mimoApiKey;

    @Value("${mimo.base-url:https://token-plan-cn.xiaomimimo.com/anthropic}")
    private String mimoBaseUrl;

    @Value("${mimo.default-model:mimo-v2.5-pro}")
    private String mimoDefaultModel;

    // ==================== 登录页 ====================

    @GetMapping("/login")
    public String login(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return "redirect:/config";
        }
        return "admin/pages/login";
    }

    // ==================== 概览页 ====================

    @GetMapping("/config")
    public String overview(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("pageTitle", "概览");
        model.addAttribute("pageTitleFull", "概览 · Ollama-Switch");
        model.addAttribute("nav", "overview");
        model.addAttribute("config", buildConfigModel());
        return "admin/pages/overview";
    }

    // ==================== 配置页 ====================

    @GetMapping("/config/settings")
    public String settings(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("pageTitle", "配置");
        model.addAttribute("pageTitleFull", "配置 · Ollama-Switch");
        model.addAttribute("nav", "settings");
        model.addAttribute("config", buildConfigModel());
        return "admin/pages/settings";
    }

    @PostMapping("/config/settings")
    public String saveSettings(Authentication authentication, Model model, @RequestParam Map<String, String> params) {
        // TODO: 持久化配置到 application.yml 或数据库
        model.addAttribute("username", authentication.getName());
        model.addAttribute("pageTitle", "配置");
        model.addAttribute("pageTitleFull", "配置 · Ollama-Switch");
        model.addAttribute("nav", "settings");
        model.addAttribute("config", buildConfigModel());
        model.addAttribute("saveSuccess", true);
        return "admin/pages/settings";
    }

    // ==================== 日志页 ====================

    @GetMapping("/config/logs")
    public String logs(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("pageTitle", "日志");
        model.addAttribute("pageTitleFull", "日志 · Ollama-Switch");
        model.addAttribute("nav", "logs");
        return "admin/pages/logs";
    }

    // ==================== 状态页 ====================

    @GetMapping("/config/status")
    public String status(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("pageTitle", "状态");
        model.addAttribute("pageTitleFull", "状态 · Ollama-Switch");
        model.addAttribute("nav", "status");
        model.addAttribute("config", buildConfigModel());
        return "admin/pages/status";
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建配置页所需的配置模型 Map。
     */
    private Map<String, Object> buildConfigModel() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("serverPort", serverPort);
        config.put("proxyProvider", proxyProvider);
        config.put("upstreamChatService", upstreamChatService);
        config.put("ollamaVersion", ollamaVersion);
        config.put("longcatApiKey", longcatApiKey);
        config.put("longcatBaseUrl", longcatBaseUrl);
        config.put("longcatDefaultModel", longcatDefaultModel);
        config.put("mimoApiKey", mimoApiKey);
        config.put("mimoBaseUrl", mimoBaseUrl);
        config.put("mimoDefaultModel", mimoDefaultModel);
        // 根据当前 provider 计算活跃配置
        if ("mimo".equals(proxyProvider)) {
            config.put("activeBaseUrl", mimoBaseUrl);
            config.put("defaultModel", mimoDefaultModel);
        } else {
            config.put("activeBaseUrl", longcatBaseUrl);
            config.put("defaultModel", longcatDefaultModel);
        }
        return config;
    }
}