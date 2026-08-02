package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPA 路由回退的行为测试。
 *
 * <p>覆盖曾经导致白屏的两类回归：
 * <ul>
 *   <li>前端路由没被后端回退成 index.html，返回 404 JSON，浏览器渲染不出任何东西；</li>
 *   <li>回退规则过宽，把 {@code /assets/*.js} 也变成 HTML，脚本加载失败同样白屏。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpaRoutingConfigTests {

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    /** 浏览器直接访问前端路由（含未在后端登记过的路径）必须拿到 HTML，而不是 404 JSON。 */
    @Test
    void 前端路由返回HTML() {
        for (String path : new String[] {"/login", "/overview", "/settings", "/preferences",
                "/account", "/call-log", "/some-future-page"}) {
            byte[] body = client.get().uri(path)
                    .accept(MediaType.TEXT_HTML)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                    .expectBody().returnResult().getResponseBody();
            assertThat(new String(body == null ? new byte[0] : body))
                    .as("路径 %s 应返回 SPA 外壳", path)
                    .contains("<div id=\"app\">");
        }
    }

    /** 带扩展名的路径属于静态资源：不存在时必须 404，不能被回退成 HTML。 */
    @Test
    void 缺失的静态资源不回退为HTML() {
        client.get().uri("/assets/does-not-exist.js")
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isNotFound();
    }

    /** 后端 API 前缀下的未知路径照常 404，避免接口路径写错时收到 200 的 HTML。 */
    @Test
    void 后端API前缀不回退为HTML() {
        for (String path : new String[] {"/api/nope", "/v1/nope", "/config/nope", "/auth/nope"}) {
            client.get().uri(path)
                    .accept(MediaType.TEXT_HTML)
                    .exchange()
                    .expectStatus().value(status -> assertThat(status)
                            .as("路径 %s 不应被 SPA 回退吞掉", path)
                            .isNotEqualTo(200));
        }
    }
}
