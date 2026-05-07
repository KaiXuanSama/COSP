package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;

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

    private final JdbcUserDetailsManager userDetailsManager;
    private final PasswordEncoder passwordEncoder;
    private final ApiUsageRepository apiUsageRepository;
    private final ProviderConfigRepository providerConfigRepository;

    public AdminPageController(JdbcUserDetailsManager userDetailsManager, PasswordEncoder passwordEncoder,
            ApiUsageRepository apiUsageRepository, ProviderConfigRepository providerConfigRepository) {
        this.userDetailsManager = userDetailsManager;
        this.passwordEncoder = passwordEncoder;
        this.apiUsageRepository = apiUsageRepository;
        this.providerConfigRepository = providerConfigRepository;
    }

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
        // API 调用统计
        model.addAttribute("totalApiCalls", apiUsageRepository.countTotal());
        model.addAttribute("todayApiCalls", apiUsageRepository.countToday());
        int[] tokens = apiUsageRepository.sumTokensToday();
        model.addAttribute("todayInputTokens", tokens[0]);
        model.addAttribute("todayOutputTokens", tokens[1]);
        return "admin/pages/overview";
    }

    // ==================== API 统计接口（JSON） ====================

    @GetMapping("/config/api/stats") @ResponseBody
    public ResponseEntity<Map<String, Object>> apiStats() {
        int[] tokens = apiUsageRepository.sumTokensToday();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalApiCalls", apiUsageRepository.countTotal());
        stats.put("todayApiCalls", apiUsageRepository.countToday());
        stats.put("todayInputTokens", tokens[0]);
        stats.put("todayOutputTokens", tokens[1]);
        return ResponseEntity.ok(stats);
    }

    // ==================== 配置页 ====================

    @GetMapping("/config/settings")
    public String settings(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("pageTitle", "配置");
        model.addAttribute("pageTitleFull", "配置 · Ollama-Switch");
        model.addAttribute("nav", "settings");
        // 从数据库读取服务商配置
        model.addAttribute("longcat", loadProvider("longcat"));
        model.addAttribute("mimo", loadProvider("mimo"));
        return "admin/pages/settings";
    }

    @PostMapping("/config/settings")
    public String saveSettings(Authentication authentication, Model model, @RequestParam Map<String, String> params) {

        // 解析并保存 LongCat 配置
        boolean longcatEnabled = "on".equals(params.get("longcatEnabled"));
        String longcatBaseUrl = params.getOrDefault("longcatBaseUrl", "").trim();
        String longcatApiKey = params.getOrDefault("longcatApiKey", "").trim();
        String longcatApiFormat = params.getOrDefault("longcatApiFormat", "openai").trim();
        int longcatId = providerConfigRepository.saveProvider("longcat", longcatEnabled, longcatBaseUrl, longcatApiKey,
                longcatApiFormat);
        List<Map<String, Object>> longcatModels = parseModels(params, "longcat");
        providerConfigRepository.saveModels(longcatId, longcatModels);

        // 解析并保存 MiMo 配置
        boolean mimoEnabled = "on".equals(params.get("mimoEnabled"));
        String mimoBaseUrl = params.getOrDefault("mimoBaseUrl", "").trim();
        String mimoApiKey = params.getOrDefault("mimoApiKey", "").trim();
        String mimoApiFormat = params.getOrDefault("mimoApiFormat", "openai").trim();
        int mimoId = providerConfigRepository.saveProvider("mimo", mimoEnabled, mimoBaseUrl, mimoApiKey, mimoApiFormat);
        List<Map<String, Object>> mimoModels = parseModels(params, "mimo");
        providerConfigRepository.saveModels(mimoId, mimoModels);

        // 重新加载页面
        model.addAttribute("username", authentication.getName());
        model.addAttribute("pageTitle", "配置");
        model.addAttribute("pageTitleFull", "配置 · Ollama-Switch");
        model.addAttribute("nav", "settings");
        model.addAttribute("longcat", loadProvider("longcat"));
        model.addAttribute("mimo", loadProvider("mimo"));
        model.addAttribute("saveSuccess", true);
        return "admin/pages/settings";
    }

    /**
     * 从数据库加载单个服务商配置，如果不存在则返回默认空对象。
     */
    private Map<String, Object> loadProvider(String providerKey) {
        Map<String, Object> provider = providerConfigRepository.findByKey(providerKey);
        if (provider == null) {
            provider = new LinkedHashMap<>();
            provider.put("providerKey", providerKey);
            provider.put("enabled", false);
            provider.put("baseUrl", "");
            provider.put("apiKey", "");
            provider.put("apiFormat", "openai");
            provider.put("models", new ArrayList<>());
        }
        return provider;
    }

    /**
     * 从请求参数中解析模型列表。
     * 支持格式：providerModels[i].name / providerModels[i].contextSize / providerModels[i].capsTools / providerModels[i].capsVision
     */
    private List<Map<String, Object>> parseModels(Map<String, String> params, String provider) {
        List<Map<String, Object>> models = new ArrayList<>();
        String prefix = provider + "Models[";
        // 收集所有模型索引
        java.util.Set<Integer> indices = new java.util.TreeSet<>();
        for (String key : params.keySet()) {
            if (key.startsWith(prefix) && key.contains("].")) {
                try {
                    int start = prefix.length();
                    int end = key.indexOf(']', start);
                    indices.add(Integer.parseInt(key.substring(start, end)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (int i : indices) {
            String name = params.getOrDefault(prefix + i + "].name", "").trim();
            if (name.isEmpty())
                continue;
            String contextSizeStr = params.getOrDefault(prefix + i + "].contextSize", "0").trim();
            int contextSize = 0;
            try {
                contextSize = Integer.parseInt(contextSizeStr);
            } catch (NumberFormatException ignored) {
            }
            boolean capsTools = "on".equals(params.get(prefix + i + "].capsTools"));
            boolean capsVision = "on".equals(params.get(prefix + i + "].capsVision"));
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("modelName", name);
            model.put("contextSize", contextSize);
            model.put("capsTools", capsTools);
            model.put("capsVision", capsVision);
            models.add(model);
        }
        return models;
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
        return "admin/pages/status";
    }

    // ==================== 账号修改页 ====================

    @GetMapping("/config/account")
    public String account(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("pageTitle", "账号设置");
        model.addAttribute("pageTitleFull", "账号设置 · Ollama-Switch");
        model.addAttribute("nav", "account");
        return "admin/pages/account";
    }

    @PostMapping("/config/account")
    public String saveAccount(Authentication authentication,
            @RequestParam(value = "newUsername", required = false) String newUsername,
            @RequestParam(value = "currentPassword", required = false) String currentPassword,
            @RequestParam(value = "newPassword", required = false) String newPassword,
            @RequestParam(value = "confirmPassword", required = false) String confirmPassword, Model model) {

        String currentUsername = authentication.getName();

        // 验证当前密码
        UserDetails currentUser = userDetailsManager.loadUserByUsername(currentUsername);
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, currentUser.getPassword())) {
            model.addAttribute("username", currentUsername);
            model.addAttribute("pageTitle", "账号设置");
            model.addAttribute("pageTitleFull", "账号设置 · Ollama-Switch");
            model.addAttribute("nav", "account");
            model.addAttribute("saveError", "当前密码不正确。");
            return "admin/pages/account";
        }

        // 确定最终用户名和密码
        final String finalUsername = (newUsername != null && !newUsername.isBlank()
                && !newUsername.equals(currentUsername)) ? newUsername.trim() : currentUsername;
        final String finalPassword;
        boolean passwordChanged = false;

        if (newPassword != null && !newPassword.isBlank()) {
            if (newPassword.length() < 4) {
                model.addAttribute("username", currentUsername);
                model.addAttribute("pageTitle", "账号设置");
                model.addAttribute("pageTitleFull", "账号设置 · Ollama-Switch");
                model.addAttribute("nav", "account");
                model.addAttribute("newUsername", newUsername);
                model.addAttribute("saveError", "新密码长度至少 4 位。");
                return "admin/pages/account";
            }
            if (!newPassword.equals(confirmPassword)) {
                model.addAttribute("username", currentUsername);
                model.addAttribute("pageTitle", "账号设置");
                model.addAttribute("pageTitleFull", "账号设置 · Ollama-Switch");
                model.addAttribute("nav", "account");
                model.addAttribute("newUsername", newUsername);
                model.addAttribute("saveError", "两次输入的新密码不一致。");
                return "admin/pages/account";
            }
            finalPassword = passwordEncoder.encode(newPassword);
            passwordChanged = true;
        } else {
            finalPassword = null;
        }

        // 如果用户名或密码有变化，删除旧用户后重新创建
        if (!finalUsername.equals(currentUsername) || passwordChanged) {
            userDetailsManager.deleteUser(currentUsername);
            String passwordToUse = passwordChanged ? finalPassword : currentUser.getPassword();
            UserDetails newUser = User.withUsername(finalUsername).password(passwordToUse).roles("ADMIN").build();
            userDetailsManager.createUser(newUser);
            model.addAttribute("saveSuccess", "账号信息已修改，请使用新账号重新登录。");
        } else {
            model.addAttribute("saveSuccess", "未做任何修改。");
        }

        model.addAttribute("username", finalUsername);
        model.addAttribute("pageTitle", "账号设置");
        model.addAttribute("pageTitleFull", "账号设置 · Ollama-Switch");
        model.addAttribute("nav", "account");
        model.addAttribute("newUsername", finalUsername);
        return "admin/pages/account";
    }

}