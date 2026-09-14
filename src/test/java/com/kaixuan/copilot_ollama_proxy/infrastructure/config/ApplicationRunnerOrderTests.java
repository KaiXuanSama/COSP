package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动期 {@link ApplicationRunner} 的执行次序契约。
 *
 * <h2>钉住的缺陷</h2>
 * {@link SchemaMigrationRunner} 曾经<strong>没有</strong> {@code @Order}，依赖的是
 * 「默认顺序在前」这个错误假设。实际上 Spring 按 {@code @Order} <strong>升序</strong>执行，
 * 无注解的 Bean 取 {@link Ordered#LOWEST_PRECEDENCE}（{@code Integer.MAX_VALUE}）——
 * 是最<em>大</em>值，于是迁移器排在了所有带 {@code @Order} 的 Runner 之后。
 *
 * <p>后果：{@code ProxyConfigBootstrap}（{@code @Order(100)}）先跑，它查询的列还没被迁移补上，
 * 抛 {@code no such column} 让整个应用启动失败。这个缺陷潜伏了两个版本 ——
 * V11 加 {@code use_proxy} 时库里已有那列所以没暴露，V13 加 {@code responses_base_url}
 * 遇到一个真正停留在旧版本的库才炸出来。<strong>症状是「换个分支就启动失败」，
 * 而不是「迁移报错」</strong>，因为迁移压根还没开始。
 *
 * <h2>为何读源文件而不是启动上下文</h2>
 * 真正要防的回归是「有人把 {@code @Order} 删了」或「新 Runner 用了比迁移更小的 order」。
 * 起一个 {@code @SpringBootTest} 能验证运行时次序，但那需要真实数据库与完整上下文，
 * 而本契约只关乎注解取值 —— 静态检查更快也更聚焦，且失败信息直接指向违规的类。
 */
class ApplicationRunnerOrderTests {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /**
     * 迁移器必须显式声明最高优先级。
     *
     * <p>不接受「无注解」：那正是缺陷本身的形态。
     */
    @Test
    @DisplayName("SchemaMigrationRunner 显式声明 HIGHEST_PRECEDENCE")
    void migrationRunnerDeclaresHighestPrecedence() {
        assertThat(SchemaMigrationRunner.class.getAnnotation(Order.class))
                .as("迁移器必须带 @Order —— 无注解会取 LOWEST_PRECEDENCE，反而排到最后")
                .isNotNull();
        assertThat(SchemaMigrationRunner.class.getAnnotation(Order.class).value())
                .as("迁移器必须第一个跑：其它 Runner 读的列可能正是本次迁移才补上的")
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    /**
     * 任何其它启动期 Runner 都不得排在迁移器之前。
     *
     * 用 {@link #effectiveOrder} 取值，口径与 Spring 运行时一致：有 {@code @Order} 取其
     * value，没有则 {@link Ordered#LOWEST_PRECEDENCE}。
     */
    @Test
    @DisplayName("其它 ApplicationRunner 的 order 均大于迁移器")
    void everyOtherRunnerComesAfterMigration() throws Exception {
        List<Class<?>> runners = discoverRunnerClasses();

        assertThat(runners)
                .as("至少应发现迁移器自身，否则是扫描逻辑失效而非真的没有 Runner")
                .contains(SchemaMigrationRunner.class);

        for (Class<?> runner : runners) {
            if (runner.equals(SchemaMigrationRunner.class)) {
                continue;
            }
            int effective = effectiveOrder(runner);
            assertThat(effective)
                    .as("%s 排在了迁移器之前或同序，它会在列尚未补齐时查询数据库", runner.getSimpleName())
                    .isGreaterThan(Ordered.HIGHEST_PRECEDENCE);
        }
    }

    /**
     * 与 Spring 运行时同一口径：有 {@code @Order} 取其 value，没有则
     * {@link Ordered#LOWEST_PRECEDENCE}。不走 {@code OrderComparator.getOrder} ——
     * 那个方法是 {@code protected}。
     */
    private static int effectiveOrder(Class<?> type) {
        Order annotation = type.getAnnotation(Order.class);
        return annotation == null ? Ordered.LOWEST_PRECEDENCE : annotation.value();
    }

    /**
     * 扫描源码目录找出所有 Runner 实现。
     *
     * <p>按源文件文本匹配而非 classpath 扫描：后者需要引入额外依赖
     * （{@code ClassPathScanningCandidateComponentProvider} 要能加载类，
     * 而部分 Runner 的构造依赖 Spring Bean）。这里只需要类名。
     */
    private static List<Class<?>> discoverRunnerClasses() throws IOException {
        List<Class<?>> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (!source.contains("implements ApplicationRunner")
                        && !source.contains("implements CommandLineRunner")) {
                    continue;
                }
                String className = toClassName(file);
                try {
                    found.add(Class.forName(className));
                } catch (ClassNotFoundException ignored) {
                    // 内部类或包名与路径不一致时跳过；本仓库当前不存在这种情况。
                }
            }
        }
        return found;
    }

    /** 由源文件路径推出全限定类名（本仓库包结构与目录一致）。 */
    private static String toClassName(Path file) {
        Path relative = SOURCE_ROOT.relativize(file);
        String withoutExtension = relative.toString().replaceAll("\\.java$", "");
        return withoutExtension.replace(java.io.File.separatorChar, '.');
    }
}
