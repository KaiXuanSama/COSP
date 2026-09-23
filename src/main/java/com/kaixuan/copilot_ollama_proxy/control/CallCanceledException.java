package com.kaixuan.copilot_ollama_proxy.control;

/**
 * 表示一次调用被管理后台主动取消（「上游迟迟不吐首字」时用户点击取消按钮）。
 *
 * <p>控制器据此与「上游错误」「客户端断连」区分开：取消需要向下游回传自定义错误
 * （触发 Copilot 的重试机制），并发出 {@code ABORTED} 生命周期事件。
 *
 * <p>不携带堆栈（{@code super(msg, null, false, false)}）：这是预期的控制流信号，
 * 不是真正的异常，无需堆栈开销。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：控制面（<strong>不是主干/支线</strong>） · 位置：{@code control/}
 * 取消信号异常—— 控制器据此回 ABORTED
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 */
public class CallCanceledException extends RuntimeException {

    public CallCanceledException() {
        super("调用被主动取消", null, false, false);
    }
}
