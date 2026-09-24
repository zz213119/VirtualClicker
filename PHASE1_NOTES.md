# Phase 1 — Virtual Display + Launch (集成说明)

## 加进 build.gradle.kts (:app)

```kotlin
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // 已有的 Shizuku 依赖若已是这个版本可跳过
}
```

`build.gradle.kts` 顶层需要启用 AIDL:
```kotlin
android {
    buildFeatures {
        aidl = true
    }
}
```

## 文件放置

- `app/src/main/aidl/.../IVirtualDisplayService.aidl`
- `app/src/main/java/.../service/VirtualDisplayUserService.kt`
- `app/src/main/java/.../core/VirtualDisplayManager.kt`

## 调用方式（示例，放进你现有验证 UserService 连通性的地方）

```kotlin
lifecycleScope.launch {
    if (!VirtualDisplayManager.ensureBound()) {
        // 提示用户先在 Shizuku 里授权
        return@launch
    }
    val displayId = VirtualDisplayManager.createDisplay("vc_display_1", 1080, 1920, 320)
    if (displayId < 0) {
        // 创建失败，看 Logcat tag VDUserService
        return@launch
    }
    val ok = VirtualDisplayManager.launch("com.miHoYo.Yuanshen", displayId)
    // ok == true 说明 am start 已下发，App 是否真的稳定运行在虚拟屏上
    // 还要靠你人工在设备上确认（比如用 `dumpsys SurfaceFlinger --list` 或截图虚拟屏）
}
```

## 已知风险 / 需要你在真机上验证的点

1. **`VIRTUAL_DISPLAY_FLAG_PUBLIC` 的权限门槛**：shell UID 通常够用，但 Android 16 + iQOO 定制 ROM
   可能加了额外限制。如果 `createVirtualDisplay` 抛权限异常，把 Logcat 完整贴给我，我按报错换方案
   （比如去掉 `PUBLIC` 只保留 `OWN_CONTENT_ONLY`，或者改用 `DisplayManagerGlobal` 反射路径）。
2. **`am start --display` 需要目标 Activity 支持多屏**：部分 App（尤其原神这类全屏游戏）可能强制
   `resizeableActivity=false` 或检测到非默认屏就自己 finish。这个只能实测，如果失败把
   `am start` 的完整输出贴给我。
3. **`resolveLaunchActivity` 在部分厂商 ROM 上可能被裁剪**：如果 `cmd package resolve-activity`
   返回空，先用 `launchAppExplicit` 手动传包名+Activity（可以用 `adb shell dumpsys package <pkg> | grep -A1 MAIN`
   自己查出来传进去，验证链路，再回头修 resolve 逻辑）。
4. 这版**不做任何截图/画面显示**，虚拟屏渲染目标是一次性 `ImageReader`，纯粹为了让 App 进程
   合法运行在后台。等这条链跑通，下一步再决定是要接 `Presentation`/`VirtualDisplay` 的实时预览
   还是直接进任务编辑器（点击/滑动注入）。

## 下一步（你确认这版能跑通之后）

- InputEngine：通过 Shizuku 的 `InputManager.injectInputEvent` 反射，对指定 `displayId` 注入
  点击/长按/滑动（普通 `input tap` shell 命令不支持指定 displayId，需要走反射或
  `uiautomator` 的 `Display` 参数）。
- 任务循环骨架（存储在 Room 或纯 JSON，先不做 UI）。

把这批代码合进去、跑一次 `createDisplay` + `launch` 之后,不管成功还是报错,把结果和 Logcat
发我,我按实际情况继续改下一版。
