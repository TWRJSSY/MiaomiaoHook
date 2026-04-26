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

    // 无障碍OCR类路径
    private static final String ACCESSIBILITY_NATIVE =
            "com.mlethe.library.accessibility.auto.AccessibilityNative";

    // 强制停止超时：2分钟
    private static final long FORCE_STOP_DELAY_MS = 2 * 60 * 1000L;

    // TracingMuxer 最小 sleep 间隔
    private static final long MIN_SLEEP_MS = 500L;

    // AccessibilityNative 每次调用后强制节流间隔
    private static final long ACCESSIBILITY_THROTTLE_MS = 50L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PKG)) return;

        XposedBridge.log("[MiaomiaoHook] 已注入: " + TARGET_PKG);

        hookScreenCaptureService(lpparam);
        hookTracingMuxerSleep();
        hookAccessibilityNative(lpparam);
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

    /**
     * Hook 3: 拦截 AccessibilityNative.convertData，仅针对 Thread-18
     * 修复无障碍OCR服务 native 层死循环导致的超大核满载
     * 触发条件：喵喵记账无障碍服务常驻开机，Thread-18持续调用 libautoIdentify.so
     * 策略：每次调用后强制 sleep 50ms，将无限速循环限制为每秒最多 20 次
     * 使用 hookAllMethods 避免方法签名不匹配问题
     */
    private void hookAccessibilityNative(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> clazz = XposedHelpers.findClass(ACCESSIBILITY_NATIVE, lpparam.classLoader);
            XposedBridge.hookAllMethods(clazz, "convertData", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!"Thread-18".equals(Thread.currentThread().getName())) return;
                    Thread.sleep(ACCESSIBILITY_THROTTLE_MS);
                }
            });
            XposedBridge.log("[MiaomiaoHook] Hook3 AccessibilityNative.convertData 注册成功");
        } catch (Throwable t) {
            XposedBridge.log("[MiaomiaoHook] Hook3 注册失败: " + t.getMessage());
        }
    }
}
