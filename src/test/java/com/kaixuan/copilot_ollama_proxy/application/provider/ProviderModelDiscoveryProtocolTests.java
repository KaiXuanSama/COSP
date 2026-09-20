package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拉取模型时的协议解析口径。
 *
 * <p>只测 {@code ModelPullCommand} 的纯逻辑，不发请求、不需要 Spring 上下文 ——
 * 拉取本身要真实 HTTP，而这里要钉的是「认不得的协议名怎么办」和「新协议落到哪个分支」。
 */
class ProviderModelDiscoveryProtocolTests {

    /** 三个协议名都能被识别，且大小写不敏感。 */
    @Test
    void parsesEveryProtocolNameCaseInsensitively() {
        for (WireProtocol protocol : WireProtocol.values()) {
            assertThat(ProviderModelDiscoveryService.ModelPullCommand.parseProtocol(protocol.name()))
                    .as("大写 %s", protocol)
                    .isEqualTo(protocol);
            assertThat(ProviderModelDiscoveryService.ModelPullCommand
                            .parseProtocol(protocol.name().toLowerCase()))
                    .as("小写 %s", protocol)
                    .isEqualTo(protocol);
        }
    }

    /**
     * 认不得的协议名（含缺失与空白）静默回退 CHAT，而不是报错。
     *
     * <p>拉取模型是辅助操作：因一个认不得的协议名拒绝整个请求比「按默认协议试一下」
     * 更让人困惑，且 Chat 是绝大多数供应商的形态。
     */
    @Test
    void unknownProtocolFallsBackToChatInsteadOfFailing() {
        assertThat(ProviderModelDiscoveryService.ModelPullCommand.parseProtocol(null))
                .isEqualTo(WireProtocol.CHAT);
        assertThat(ProviderModelDiscoveryService.ModelPullCommand.parseProtocol("   "))
                .isEqualTo(WireProtocol.CHAT);
        assertThat(ProviderModelDiscoveryService.ModelPullCommand.parseProtocol("GRPC"))
                .isEqualTo(WireProtocol.CHAT);
        // 旧协议名在 V12 之后也是认不得的值，同样走回退而不是报错。
        assertThat(ProviderModelDiscoveryService.ModelPullCommand.parseProtocol("OPENAI"))
                .isEqualTo(WireProtocol.CHAT);
    }

    /**
     * {@code ModelPullCommand} 的构造器对协议缺省同样落 CHAT。
     *
     * <p>与 {@link #unknownProtocolFallsBackToChatInsteadOfFailing()} 是两条路径：
     * 那个是「字符串解析」，这个是「record 规范化」。两处口径必须一致，
     * 否则同一个请求经不同入口会拉到不同线路。
     */
    @Test
    void commandNormalizesMissingProtocolToChat() {
        ProviderModelDiscoveryService.ModelPullCommand command =
                new ProviderModelDiscoveryService.ModelPullCommand(
                        " https://api.example/v1 ", " key ", " uuid ", " /models ", null);

        assertThat(command.protocol()).isEqualTo(WireProtocol.CHAT);
        // 顺带钉住 trim：地址两侧空白会让 URL 拼接出一个打不通的地方。
        assertThat(command.baseUrl()).isEqualTo("https://api.example/v1");
        assertThat(command.apiKey()).isEqualTo("key");
        assertThat(command.keyUuid()).isEqualTo("uuid");
        assertThat(command.modelPullPath()).isEqualTo("/models");
    }

}
