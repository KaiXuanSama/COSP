package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import java.lang.annotation.*;

/**
 * 标记一个组件仅在管理端口（80）生效，避免被主端口（11434）加载。
 * 用法：在 Filter 或 Controller 上标注 @AdminPortOnly 即可。
 */
@Target({ ElementType.TYPE, ElementType.METHOD }) @Retention(RetentionPolicy.RUNTIME) @Documented
public @interface AdminPortOnly {
}
