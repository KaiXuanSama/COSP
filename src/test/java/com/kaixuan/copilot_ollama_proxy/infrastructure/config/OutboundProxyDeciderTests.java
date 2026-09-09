package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OutboundProxyDecider} 的判定行为。
 *
 * <p>断言方向统一按被测方法的语义写：{@code isDirect} 返回 true 表示<strong>绕过代理</strong>。
 * 这与 Reactor Netty 的 {@code nonProxyHostsPredicate} 同向，测试里不做取反以免读起来拧着。
 */
class OutboundProxyDeciderTests {

    private static SocketAddress target(String host, int port) {
        // createUnresolved：避免测试触发真实 DNS 解析。
        return InetSocketAddress.createUnresolved(host, port);
    }

    @Nested
    @DisplayName("全局总闸")
    class GlobalSwitch {

        @Test
        @DisplayName("关闭时一切直连，即便目标在走代理集合里")
        void disabledForcesDirectEvenForListedTargets() {
            OutboundProxyDecider decider = new OutboundProxyDecider(false);
            decider.replaceProxiedTargets(Set.of("api.example.com:443"));

            assertThat(decider.isEnabled()).isFalse();
            assertThat(decider.isDirect(target("api.example.com", 443))).isTrue();
        }

        @Test
        @DisplayName("打开且集合为空时全部走代理，保持接入供应商开关前的既有行为")
        void enabledWithEmptySetProxiesEverything() {
            OutboundProxyDecider decider = new OutboundProxyDecider(true);

            assertThat(decider.isEnabled()).isTrue();
            assertThat(decider.isDirect(target("api.example.com", 443))).isFalse();
            assertThat(decider.isDirect(target("other.example.org", 8080))).isFalse();
        }
    }

    @Nested
    @DisplayName("目标集合判定")
    class TargetMatching {

        @Test
        @DisplayName("集合非空时只有命中的目标走代理，其余直连")
        void onlyListedTargetsAreProxied() {
            OutboundProxyDecider decider = new OutboundProxyDecider(true);
            decider.replaceProxiedTargets(Set.of("blocked.example.com:443"));

            assertThat(decider.isDirect(target("blocked.example.com", 443))).isFalse();
            assertThat(decider.isDirect(target("reachable.example.com", 443))).isTrue();
        }

        @Test
        @DisplayName("端口参与匹配：同主机不同端口是不同目标")
        void portIsPartOfTheKey() {
            OutboundProxyDecider decider = new OutboundProxyDecider(true);
            decider.replaceProxiedTargets(Set.of("relay.example.com:8443"));

            assertThat(decider.isDirect(target("relay.example.com", 8443))).isFalse();
            assertThat(decider.isDirect(target("relay.example.com", 443))).isTrue();
        }

        @Test
        @DisplayName("主机名大小写不敏感")
        void hostMatchingIsCaseInsensitive() {
            OutboundProxyDecider decider = new OutboundProxyDecider(true);
            decider.replaceProxiedTargets(Set.of("API.Example.COM:443"));

            assertThat(decider.isDirect(target("api.example.com", 443))).isFalse();
        }

        @Test
        @DisplayName("整体替换会撤销此前的目标，不留陈旧项")
        void replaceRevokesPreviousTargets() {
            OutboundProxyDecider decider = new OutboundProxyDecider(true);
            decider.replaceProxiedTargets(Set.of("first.example.com:443"));
            decider.replaceProxiedTargets(Set.of("second.example.com:443"));

            assertThat(decider.isDirect(target("first.example.com", 443))).isTrue();
            assertThat(decider.isDirect(target("second.example.com", 443))).isFalse();
        }

        @Test
        @DisplayName("传入 null 或空集回到默认判定，不会残留上一次的目标")
        void nullOrEmptyResetsToDefault() {
            OutboundProxyDecider decider = new OutboundProxyDecider(true);
            decider.replaceProxiedTargets(Set.of("stale.example.com:443"));
            decider.replaceProxiedTargets(null);

            // 空集的默认判定是「全部走代理」，因此此前被单独点名的目标也不再特殊。
            assertThat(decider.isDirect(target("stale.example.com", 443))).isFalse();
            assertThat(decider.isDirect(target("anything.example.com", 443))).isFalse();
        }

        @Test
        @DisplayName("集合里的空白项被忽略，不会变成一个匹配不上的幽灵目标")
        void blankEntriesAreIgnored() {
            OutboundProxyDecider decider = new OutboundProxyDecider(true);
            decider.replaceProxiedTargets(Set.of("  ", "real.example.com:443"));

            assertThat(decider.isDirect(target("real.example.com", 443))).isFalse();
        }
    }

    @Nested
    @DisplayName("非 IP 目标")
    class NonInetTargets {

        @Test
        @DisplayName("非 InetSocketAddress 保守直连")
        void nonInetAddressFallsBackToDirect() {
            OutboundProxyDecider decider = new OutboundProxyDecider(true);

            // 代理无从施加到非 IP 目标上，此时应直连而非试图代理。
            assertThat(decider.isDirect(new SocketAddress() {
            })).isTrue();
        }
    }
}
