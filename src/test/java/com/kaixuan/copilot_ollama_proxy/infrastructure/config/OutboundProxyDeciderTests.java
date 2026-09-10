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
 *
 * <p>语义只有一句「有一个代理，对启用代理的供应商用，对不启用的直连」，拆成两条正交判据：
 * 地址是否配置（有没有代理）、目标是否命中集合（用不用代理），缺任一条都直连。
 * <strong>没有全局启停总闸</strong>——空地址即等于关。
 */
class OutboundProxyDeciderTests {

    private static SocketAddress target(String host, int port) {
        // createUnresolved：避免测试触发真实 DNS 解析。
        return InetSocketAddress.createUnresolved(host, port);
    }

    /** 配好地址 + 目标集合的常见起点，多数用例从这里出发。 */
    private static OutboundProxyDecider deciderWith(String address, Set<String> targets) {
        OutboundProxyDecider decider = new OutboundProxyDecider();
        decider.updateProxyAddress(address);
        decider.replaceProxiedTargets(targets);
        return decider;
    }

    @Nested
    @DisplayName("代理地址维度")
    class ProxyAddressDimension {

        @Test
        @DisplayName("没配地址时一切直连，即便目标在集合里")
        void noAddressForcesDirectEvenForListedTargets() {
            OutboundProxyDecider decider = new OutboundProxyDecider();
            decider.replaceProxiedTargets(Set.of("api.example.com:443"));

            assertThat(decider.hasProxyAddress()).isFalse();
            assertThat(decider.isDirect(target("api.example.com", 443))).isTrue();
        }

        @Test
        @DisplayName("清空地址等于关闭代理，此前命中的目标也回到直连")
        void clearingAddressDisablesProxy() {
            OutboundProxyDecider decider = deciderWith("127.0.0.1:7890", Set.of("api.example.com:443"));
            assertThat(decider.isDirect(target("api.example.com", 443))).isFalse();

            decider.updateProxyAddress("");

            assertThat(decider.hasProxyAddress()).isFalse();
            assertThat(decider.isDirect(target("api.example.com", 443))).isTrue();
        }

        @Test
        @DisplayName("地址读写往返一致，两端空白被 trim")
        void addressRoundTripsAndTrims() {
            OutboundProxyDecider decider = new OutboundProxyDecider();
            decider.updateProxyAddress("  127.0.0.1:7890  ");

            assertThat(decider.currentProxyAddress()).isEqualTo("127.0.0.1:7890");
            assertThat(decider.hasProxyAddress()).isTrue();
        }

        @Test
        @DisplayName("传 null 视为清空，不抛异常")
        void nullAddressIsTreatedAsEmpty() {
            OutboundProxyDecider decider = new OutboundProxyDecider();
            decider.updateProxyAddress(null);

            assertThat(decider.currentProxyAddress()).isEmpty();
            assertThat(decider.hasProxyAddress()).isFalse();
        }
    }

    @Nested
    @DisplayName("目标集合判定")
    class TargetMatching {

        @Test
        @DisplayName("配了地址但集合为空时全部直连 —— 没有任何供应商开代理")
        void configuredAddressButEmptySetProxiesNothing() {
            OutboundProxyDecider decider = new OutboundProxyDecider();
            decider.updateProxyAddress("127.0.0.1:7890");

            assertThat(decider.hasProxyAddress()).isTrue();
            assertThat(decider.isDirect(target("api.example.com", 443))).isTrue();
            assertThat(decider.isDirect(target("other.example.org", 8080))).isTrue();
        }

        @Test
        @DisplayName("集合非空时只有命中的目标走代理，其余直连")
        void onlyListedTargetsAreProxied() {
            OutboundProxyDecider decider = deciderWith("127.0.0.1:7890", Set.of("blocked.example.com:443"));

            assertThat(decider.isDirect(target("blocked.example.com", 443))).isFalse();
            assertThat(decider.isDirect(target("reachable.example.com", 443))).isTrue();
        }

        @Test
        @DisplayName("端口参与匹配：同主机不同端口是不同目标")
        void portIsPartOfTheKey() {
            OutboundProxyDecider decider = deciderWith("127.0.0.1:7890", Set.of("relay.example.com:8443"));

            assertThat(decider.isDirect(target("relay.example.com", 8443))).isFalse();
            assertThat(decider.isDirect(target("relay.example.com", 443))).isTrue();
        }

        @Test
        @DisplayName("主机名大小写不敏感")
        void hostMatchingIsCaseInsensitive() {
            OutboundProxyDecider decider = deciderWith("127.0.0.1:7890", Set.of("API.Example.COM:443"));

            assertThat(decider.isDirect(target("api.example.com", 443))).isFalse();
        }

        @Test
        @DisplayName("整体替换会撤销此前的目标，不留陈旧项")
        void replaceRevokesPreviousTargets() {
            OutboundProxyDecider decider = deciderWith("127.0.0.1:7890", Set.of("first.example.com:443"));
            decider.replaceProxiedTargets(Set.of("second.example.com:443"));

            assertThat(decider.isDirect(target("first.example.com", 443))).isTrue();
            assertThat(decider.isDirect(target("second.example.com", 443))).isFalse();
        }

        @Test
        @DisplayName("传入 null 或空集清空目标，此后一切直连")
        void nullOrEmptyClearsTargets() {
            OutboundProxyDecider decider = deciderWith("127.0.0.1:7890", Set.of("stale.example.com:443"));
            decider.replaceProxiedTargets(null);

            // 空集 = 没有任何供应商开代理 = 全部直连（绝非「全部走代理」）。
            assertThat(decider.isDirect(target("stale.example.com", 443))).isTrue();
            assertThat(decider.isDirect(target("anything.example.com", 443))).isTrue();
        }

        @Test
        @DisplayName("集合里的空白项被忽略，不会变成一个匹配不上的幽灵目标")
        void blankEntriesAreIgnored() {
            OutboundProxyDecider decider = deciderWith("127.0.0.1:7890", Set.of("  ", "real.example.com:443"));

            assertThat(decider.isDirect(target("real.example.com", 443))).isFalse();
        }
    }

    @Nested
    @DisplayName("非 IP 目标")
    class NonInetTargets {

        @Test
        @DisplayName("非 InetSocketAddress 保守直连")
        void nonInetAddressFallsBackToDirect() {
            OutboundProxyDecider decider = deciderWith("127.0.0.1:7890", Set.of("whatever.example.com:443"));

            // 代理无从施加到非 IP 目标上，此时应直连而非试图代理。
            assertThat(decider.isDirect(new SocketAddress() {
            })).isTrue();
        }
    }
}
