package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RequestPredicate;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 前端 SPA 路由回退（WebFlux）。
 *
 * <p>Vue Router 使用 {@code createWebHistory()}，前端路由（{@code /login}、{@code /overview} 等）
 * 在浏览器直接访问、刷新或整页跳转时都会真实打到后端，必须回退返回 {@code index.html}，
 * 由 Vue 在浏览器端接管路由。
 *
 * <h2>为什么是通配回退，而不是逐个枚举路由</h2>
 *
 * <p>这里曾经是一份手写的路由清单，且在 {@code SecurityConfig} 里还有第二份拷贝。
 * 两份清单不可能长期保持一致 —— {@code /preferences} 就只出现在其中一份里。
 * 漏配的前端路由会落到 Spring 的默认 404，返回的是 JSON 错误体而不是 HTML，
 * 浏览器拿不到任何可渲染内容，用户看到的就是白屏 + 一条含义不明的 404。
 *
 * <p>因此改为通配匹配：凡是「想要 HTML 的 GET 请求」且「不属于后端 API」且「不像静态文件」，
 * 一律返回 index.html。新增前端页面只改 Vue 路由表，后端无需同步任何清单。
 *
 * <h2>缺产物时给出可读诊断</h2>
 *
 * <p>另一类同样表现为白屏的故障是「index.html 存在，但它引用的 {@code /assets/*.js} 不存在」，
 * 典型成因是只跑了 {@code npm run build}（只写入 {@code src/main/resources/static}），
 * 没让产物进入运行时 classpath（{@code target/classes/static}）。
 * 此时返回那个空壳 index.html 注定渲染不出东西，所以启动时先自检，
 * 缺资源就打 ERROR 日志并改为返回一个说明问题与修复命令的诊断页面。
 */
@Configuration
public class SpaRoutingConfig {

    private static final Logger log = LoggerFactory.getLogger(SpaRoutingConfig.class);

    /**
     * 不参与 SPA 回退的后端路径前缀。
     *
     * <p>这些前缀下的未知路径应当照常 404，而不是被回退成 HTML —— 否则接口路径写错时
     * 客户端会收到一个 200 的 HTML 页面，排查成本极高。
     */
    private static final List<String> BACKEND_PREFIXES = List.of(
            "/api/", "/v1/", "/config/", "/auth/", "/logout");

    /** 从 index.html 中提取被引用的构建产物路径，如 {@code /assets/index-abc123.js}。 */
    private static final Pattern ASSET_REFERENCE = Pattern.compile("(?:src|href)=\"(/assets/[^\"]+)\"");

    private final Resource indexHtml = new ClassPathResource("static/index.html");

    /**
     * 前端产物不可用的原因；{@code null} 表示健康。
     *
     * <p>volatile：健康时走快路径不再自检；损坏时每次请求重新检测，
     * 使得补齐产物后刷新页面即可恢复，无需重启服务。
     */
    private volatile String brokenReason;

    public SpaRoutingConfig() {
        this.brokenReason = detectBrokenFrontend();
        if (brokenReason != null) {
            log.error("前端构建产物不可用：{}。管理后台页面无法渲染（后端接口不受影响）。"
                    + "请执行 ./mvnw process-resources 把 frontend 的构建产物同步到运行时 classpath —— "
                    + "单独执行 npm run build 只会写入 src/main/resources/static，不会更新已启动服务的 classpath。",
                    brokenReason);
        }
    }

    /**
     * SPA 回退路由：把面向浏览器的未知 GET 请求交给前端路由处理。
     *
     * <p>{@code RouterFunctionMapping} 的顺序（-1）高于静态资源处理器，因此必须靠
     * {@link #isSpaPath} 把带扩展名的路径排除掉，否则 {@code /assets/index-xxx.js}
     * 会被这里吞掉，反而制造出新的白屏。
     *
     * @return SPA 回退的 RouterFunction
     */
    @Bean
    public RouterFunction<ServerResponse> spaRoutes() {
        RequestPredicate spaFallback = RequestPredicates.GET("/**")
                .and(RequestPredicates.accept(MediaType.TEXT_HTML))
                .and(request -> isSpaPath(request.path()));
        return RouterFunctions.route(spaFallback, request -> serveIndex());
    }

    /**
     * 判断某路径是否应回退给前端路由。
     *
     * @param path 请求路径
     * @return true 表示交给 SPA
     */
    private boolean isSpaPath(String path) {
        for (String prefix : BACKEND_PREFIXES) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        // 末段含 '.' 视为静态文件请求（/assets/index-xxx.js、/img/icon.png、/favicon.ico），
        // 交给静态资源处理器，缺失时应当 404 而不是返回一个 HTML 页面。
        int lastSlash = path.lastIndexOf('/');
        return path.indexOf('.', lastSlash + 1) < 0;
    }

    /**
     * 返回 index.html；产物缺失时返回可读的诊断页面而非空壳。
     */
    private Mono<ServerResponse> serveIndex() {
        if (brokenReason != null) {
            // 上次检测为损坏：重新检测，让「补齐产物」无需重启即可生效。
            brokenReason = detectBrokenFrontend();
        }
        String reason = brokenReason;
        if (reason != null) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.TEXT_HTML)
                    .bodyValue(diagnosticPage(reason));
        }
        return ServerResponse.ok().contentType(MediaType.TEXT_HTML).bodyValue(indexHtml);
    }

    /**
     * 自检前端产物是否完整。
     *
     * <p>只校验 index.html 里实际引用到的具体文件，不判断 {@code static/assets} 目录是否存在 ——
     * jar 内未必有目录条目，按目录判断会在正常的 jar 部署下误报。
     *
     * @return 损坏原因描述，完整时返回 {@code null}
     */
    private String detectBrokenFrontend() {
        if (!indexHtml.exists()) {
            return "classpath:static/index.html 不存在";
        }
        String html;
        try (InputStream in = indexHtml.getInputStream()) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 读不出来不代表产物有问题，放行交给正常渲染路径，避免误报拦掉可用页面。
            log.warn("读取 classpath:static/index.html 失败，跳过前端产物自检：{}", e.getMessage());
            return null;
        }

        List<String> missing = new ArrayList<>();
        Matcher matcher = ASSET_REFERENCE.matcher(html);
        while (matcher.find()) {
            String assetPath = matcher.group(1);
            if (!new ClassPathResource("static" + assetPath).exists()) {
                missing.add(assetPath);
            }
        }
        return missing.isEmpty() ? null : "index.html 引用的构建产物缺失 " + missing;
    }

    /**
     * 构造诊断页面 —— 取代空白页，直接告诉用户发生了什么以及怎么修。
     */
    private String diagnosticPage(String reason) {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>COSP 管理后台不可用</title>
                  <style>
                    body { margin: 0; min-height: 100vh; display: flex; align-items: center;
                           justify-content: center; background: #f5f3ee; color: #4a4740;
                           font-family: system-ui, "Segoe UI", "Microsoft YaHei", sans-serif; }
                    main { max-width: 34rem; padding: 2.5rem; background: #fff; border: 1px solid #e8e5de;
                           border-radius: 12px; }
                    h1 { margin: 0 0 .75rem; font-size: 1.25rem; color: #1a1917; }
                    p { margin: 0 0 1rem; line-height: 1.7; }
                    code { display: block; padding: .75rem 1rem; background: #f5f3ee; border-radius: 6px;
                           border-left: 3px solid #c27a3e; font-size: .9rem; word-break: break-all; }
                    .reason { color: #9a9590; font-size: .875rem; }
                  </style>
                </head>
                <body>
                  <main>
                    <h1>管理后台前端资源缺失</h1>
                    <p>后端服务运行正常，Ollama 与 OpenAI 接口不受影响；只是管理后台的前端构建产物没有进入运行时 classpath，页面无法渲染。</p>
                    <p>在项目根目录执行：</p>
                    <code>./mvnw process-resources</code>
                    <p>然后刷新本页，无需重启服务。单独执行 npm run build 只会写入 src/main/resources/static，不会同步到已启动服务的 classpath。</p>
                    <p class="reason">诊断详情：%s</p>
                  </main>
                </body>
                </html>
                """.formatted(reason);
    }
}
