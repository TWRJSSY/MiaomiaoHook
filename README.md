# 喵喵记账 Fixer

修复喵喵记账超大核异常占用的 LSPosed 模块，覆盖两条独立触发链。

## 问题描述

喵喵记账存在两个独立的性能缺陷，均可导致 SM8850 超大核持续 100% @ 2438MHz，机身温度 63–72°C，功耗异常。

\---

### 触发链一：快捷记账屏幕共享退出后服务泄漏

**触发现象：**

喵喵记账在通知栏提供了快捷记账按钮，长按后进入屏幕共享模式，由 `ScreenCaptureService` 负责录制屏幕并调用 `libautoIdentify.so` 做 OCR 识别。

退出时，官方设计依赖 `CloseScreenCaptureReceiver` 接收自定义广播来停止服务。然而，`ScreenCaptureService` **没有实现 `MediaProjection` 的停止回调**，当用户通过系统通知栏的"停止屏幕共享"退出时，系统层面的停止事件无人监听，自定义广播从未发出，`ScreenCaptureService` 无法感知退出，持续在后台运行。

服务内部的 Perfetto SDK（本应只用于开发调试）残留了 `TracingMuxer` 线程，该线程以 `poll timeout=0` 持续空转，被 Android 调度器识别为高负载线程后迁移至超大核并拉至最高频率，造成满载。

**根本原因：** 未实现 `MediaProjection.Callback.onStop()`，依赖自定义广播退出的设计无法覆盖系统层面的停止场景。

```
通知栏快捷按钮长按
  → ScreenCaptureTileService
    → ScreenCaptureService 启动屏幕共享
      → Perfetto SDK 启动 TracingMuxer 线程
        → 用户通过系统通知停止屏幕共享
          → MediaProjection.onStop() 无人实现，广播未发出
            → ScreenCaptureService 继续运行
              → TracingMuxer poll timeout=0 空转 → 超大核满载
```

\---

### 触发链二：无障碍服务 OCR 无限轮询

**触发现象：**

喵喵记账注册了无障碍服务 `SelectToSpeakService`，用于监听屏幕内容变化，实现自动识别账单功能。

正确实现应使用 `AccessibilityService.onAccessibilityEvent()` 回调，由系统在屏幕内容变化时主动推送事件，应用只需在回调里处理即可。

然而喵喵记账选择了自己起 `Thread-18` 线程，在**没有任何退出条件和节流的死循环**里持续调用 `AccessibilityNative.convertData()`，后者再调用 native 层 `libautoIdentify.so` 的 `handlerData` 做 OCR 图像识别。

OCR 识别属于计算密集型操作，无限速调用相当于每秒做几十次 AI 推理。在小核上由于算力有限，表现尚不明显；一旦 Android 调度器将 Thread-18 迁移至超大核，算力提升反而让循环跑得更快，形成正反馈，最终锁死在超大核满载。

该问题**开机即存在**，与快捷记账功能无关，只要无障碍服务开启就持续触发。

**根本原因：** 未使用 `onAccessibilityEvent()` 事件驱动模型，用无限轮询替代系统回调，且 native 层无任何节流。

```
开机常驻 SelectToSpeakService
  → Thread-18 死循环（无退出条件，无节流）
    → AccessibilityNative.convertData()
      → libautoIdentify.so handlerData（OCR推理，计算密集）
        → Android调度器将Thread-18迁移至超大核
          → 算力提升 → 循环更快 → 正反馈 → 超大核满载
```

\---

## Hook 策略

||目标|作用|
|-|-|-|
|Hook 1|`ScreenCaptureService.onStartCommand`|服务启动后挂 2 分钟定时器，超时强制 `stopSelf()`，补上缺失的退出逻辑|
|Hook 2|`Thread.sleep`（仅 TracingMuxer 线程）|拦截 sleep < 500ms，强制最小间隔 500ms，消除忙等待|
|Hook 3|`AccessibilityNative.convertData`（仅 Thread-18）|每次调用后强制 sleep 50ms，将无限速循环限制为每秒最多 20 次，打断正反馈|

## 环境要求

* Root（Magisk / KernelSU / APatch + Zygisk）
* LSPosed（API 93+，已验证至 101）
* Android 10+

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
\\\\\\\\\\\\\\\[MiaomiaoHook] 已注入: com.youqi.miaomiao
\\\\\\\\\\\\\\\[MiaomiaoHook] Hook1 ScreenCaptureService 注册成功
\\\\\\\\\\\\\\\[MiaomiaoHook] Hook2 TracingMuxer.sleep 注册成功
\\\\\\\\\\\\\\\[MiaomiaoHook] Hook3 AccessibilityNative.convertData 注册成功
```

## 说明

本模块为临时缓解方案，根本修复需喵喵记账官方：

* 实现 `MediaProjection.Callback.onStop()` 正确处理系统层退出
* 使用 `onAccessibilityEvent()` 事件驱动替代无限轮询

如喵喵记账后续版本已修复上述问题，本模块可卸载。

## License

Copyright 2026 听闻人间十三月

Licensed under the [Apache License, Version 2.0](LICENSE).

