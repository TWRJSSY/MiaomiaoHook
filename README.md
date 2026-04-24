# 喵喵记账 Fixer

修复喵喵记账通知栏快捷记账功能触发后，`TracingMuxer` 线程空转导致超大核异常占用的 LSPosed 模块。

## 问题描述

喵喵记账的通知栏快捷记账（`ScreenCaptureTileService`）在触发屏幕共享后，退出逻辑存在 race condition，导致 `ScreenCaptureService` 生命周期泄漏。Perfetto SDK 内部的 `TracingMuxer` 线程随之以 `poll timeout=0` 持续空转，被 Android 调度器分配到超大核后造成满载。

实测现象（SM8850，喵喵记账 v5.1.1）：
- autoBill 进程 CPU 占用 **105%**
- 超大核 **100% @ 2438MHz**
- 机身温度持续 **63–72°C**

**触发链：**

```
通知栏快捷按钮长按
  → ScreenCaptureTileService
    → ScreenCaptureService 启动屏幕共享
      → OcrBillViewModel 调用 libautoIdentify.so 做 OCR
        → Perfetto SDK 启动 TracingMuxer 线程
          → do_sys_poll timeout=0，忙等待空转
            → CloseScreenCaptureReceiver 退出广播未发出
              → 超大核持续 100%
```

## Hook 策略

| | 目标 | 作用 |
|---|---|---|
| Hook 1 | `ScreenCaptureService.onStartCommand` | 服务启动后挂 2 分钟定时器，超时强制 `stopSelf()`，补上缺失的退出逻辑 |
| Hook 2 | `Thread.sleep`（仅 TracingMuxer 线程） | 拦截 sleep < 500ms 的调用，强制最小间隔 500ms，消除忙等待 |

## 环境要求

- Root（Magisk / KernelSU / APatch + Zygisk）
- LSPosed（API 93+，已验证至 101）
- Android 10+

## 构建

```bash
git clone https://github.com/twrjssy/MiaomiaoHook.git
cd MiaomiaoHook
./gradlew assembleRelease
```

APK 产物位于 `app/build/outputs/apk/release/`。

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
[MiaomiaoHook] ScreenCaptureService 已启动，2min后强制停止
[MiaomiaoHook] TracingMuxer sleep 拦截: 0ms → 500ms
```

## 说明

本模块为临时缓解方案，根本修复需喵喵记账官方修正 `ScreenCaptureTileService` 的退出逻辑。如喵喵记账后续版本已修复此问题，本模块可卸载。

## License

Copyright 2026 听闻人间十三月

Licensed under the [Apache License, Version 2.0](LICENSE).
