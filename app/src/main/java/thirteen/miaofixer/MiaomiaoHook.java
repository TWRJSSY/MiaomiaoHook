package thirteen.miaofixer;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MiaomiaoHook implements IXposedHookLoadPackage {

    private static final String TARGET_PKG = "com.youqi.miaomiao";

    // 真实内部类路径（APK壳内）
    private static final String SCREEN_CAPTURE_SERVICE =
            "com.lanniser.kittykeeping.service.ScreenCaptureService";

    // 强制停止超时：2分钟
    private static final long FORCE_STOP_DELAY_MS = 2 * 60 * 1000L;

    // TracingMuxer 最小 sleep 间隔
    private static final long MIN_SLEEP_MS = 500L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PKG)) return;

        XposedBridge.log("[MiaomiaoHook] 已注入: " + TARGET_PKG);

        hookScreenCaptureService(lpparam);
        hookTracingMuxerSleep();
    }

    /**
     * Hook 1: 拦截 ScreenCaptureService.onStartCommand
     * 服务启动后挂 2 分钟定时器强制 stopSelf()
     * 修复退出广播未发出导致的服务生命周期泄漏
     */
    private void hookScreenCaptureService(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    SCREEN_CAPTURE_SERVICE,
                    lpparam.classLoader,
                    "onStartCommand",
                    Intent.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Service service = (Service) param.thisObject;
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                try {
                                    service.stopSelf();
                                    XposedBridge.log("[MiaomiaoHook] ScreenCaptureService 强制停止（2min超时）");
                                } catch (Throwable t) {
                                    XposedBridge.log("[MiaomiaoHook] stopSelf 失败: " + t.getMessage());
                                }
                            }, FORCE_STOP_DELAY_MS);

                            XposedBridge.log("[MiaomiaoHook] ScreenCaptureService 已启动，2min后强制停止");
                        }
                    }
            );
            XposedBridge.log("[MiaomiaoHook] Hook1 ScreenCaptureService 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("[MiaomiaoHook] Hook1 注册失败: " + t.getMessage());
        }
    }

    /**
     * Hook 2: 拦截 Thread.sleep，仅针对 TracingMuxer 线程
     * 修复 Perfetto SDK poll timeout=0 导致的忙等待空转
     */
    private void hookTracingMuxerSleep() {
        try {
            XposedHelpers.findAndHookMethod(
                    "java.lang.Thread",
                    null,
                    "sleep",
                    long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!"TracingMuxer".equals(Thread.currentThread().getName())) return;
                            long original = (long) param.args[0];
                            if (original < MIN_SLEEP_MS) {
                                param.args[0] = MIN_SLEEP_MS;
                                XposedBridge.log("[MiaomiaoHook] TracingMuxer sleep 拦截: "
                                        + original + "ms → " + MIN_SLEEP_MS + "ms");
                            }
                        }
                    }
            );
            XposedBridge.log("[MiaomiaoHook] Hook2 TracingMuxer.sleep 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("[MiaomiaoHook] Hook2 注册失败: " + t.getMessage());
        }
    }
}
