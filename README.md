# 喵喵记账 Fixer

修复喵喵记账超大核异常占用的 LSPosed 模块，覆盖两条独立触发链。

## 问题描述

喵喵记账存在两个独立的 CPU 满载触发源，实测 SM8850 超大核持续 100% @ 2438MHz，机身温度 63–72°C。

### 触发链一：快捷记账屏幕共享

通知栏快捷按钮长按触发屏幕共享后，退出逻辑存在 race condition，`ScreenCaptureService` 生命周期泄漏，Perfetto SDK 内部的 `TracingMuxer` 线程以 `poll timeout=0` 持续空转。

```
通知栏快捷按钮长按
  → ScreenCaptureTileService
    → ScreenCaptureService 启动屏幕共享
      → OcrBillViewModel 调用 libautoIdentify.so 做 OCR
        → Perfetto SDK 启动 TracingMuxer 线程
          → do_sys_poll timeout=0 忙等待
            → CloseScreenCaptureReceiver 退出广播未发出 → 超大核持续满载
```

### 触发链二：无障碍服务常驻死循环

喵喵记账无障碍服务开机常驻，`Thread-18` 持续调用 `AccessibilityNative.handlerData`，native 层无任何节流，纯 CPU 自旋。

```
开机常驻 SelectToSpeakService
  → Thread-18 循环调用 AccessibilityNative.convertData
    → libautoIdentify.so handlerData 无限空转 → 超大核持续满载
```

## Hook 策略

| | 目标 | 作用 |
|---|---|---|
| Hook 1 | `ScreenCaptureService.onStartCommand` | 服务启动后挂 2 分钟定时器，超时强制 `stopSelf()`，补上缺失的退出逻辑 |
| Hook 2 | `Thread.sleep`（仅 TracingMuxer 线程） | 拦截 sleep < 500ms，强制最小间隔 500ms，消除忙等待 |
| Hook 3 | `AccessibilityNative.convertData`（仅 Thread-18） | 每次调用后强制 sleep 50ms，将无限速循环限制为每秒最多 20 次 |

## 环境要求

- Root（Magisk / KernelSU / APatch + Zygisk）
- LSPosed（API 93+，已验证至 101）
- Android 10+

## 构建

```bash
git clone https://github.com/twrjssy/MiaomiaoHook.git
cd MiaomiaoHook
./gradlew assembleDebug
```

## 使用

1. 安装 APK
2. LSPosed → 模块 → 启用**喵喵记账 Fixer** → 作用域勾选**喵喵记账**
3. 重启手机

## 验证

Hook 生效后，LSPosed 日志中可以看到：

```
[MiaomiaoHook] 已注入: com.youqi.miaomiao
[MiaomiaoHook] Hook1 ScreenCaptureService 注册成功
[MiaomiaoHook] Hook2 TracingMuxer.sleep 注册成功
[MiaomiaoHook] Hook3 AccessibilityNative.convertData 注册成功
```

## 说明

本模块为临时缓解方案，根本修复需喵喵记账官方修正 `ScreenCaptureTileService` 退出逻辑及 `AccessibilityNative` 节流机制。如喵喵记账后续版本已修复此问题，本模块可卸载。

## License

Copyright 2026 听闻人间十三月

Licensed under the [Apache License, Version 2.0](LICENSE).
