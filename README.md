# VirtualClicker

Android 通用后台自动化/连点器。

目标不是绑定某一款游戏，而是提供一个通用的后台自动化引擎：

- Shizuku 优先
- Root 后端随后加入
- Virtual Display 后台运行目标 App
- Display 定向点击、长按、滑动
- 任务录制与循环
- 用户自行添加任意 Android App

## 当前进度

### V0.1-dev

- [x] Android 原生工程
- [x] Shizuku API 13.1.5
- [x] Shizuku 状态检测
- [x] Shizuku 权限请求入口
- [x] Shizuku UserService 后端
- [x] UserService UID/PID 连通性测试
- [x] 第三方应用列表原型
- [ ] Virtual Display
- [ ] 目标应用启动到 Virtual Display
- [ ] Display 定向输入
- [ ] 任务编辑器
- [ ] Root Backend

## 技术基线

- minSdk 28
- targetSdk 36
- Kotlin 2.4.20
- Android Gradle Plugin 9.4.0
- Gradle 9.6.1
- JDK 17
