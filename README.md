# 抖音低赞视频过滤器 (DouyinLowLikeFilter)

一个基于 **LSPosed/Xposed** 框架的抖音模块，可以自动过滤推荐流中点赞数低于自定义阈值的视频。

---

## 功能特性


Hook `RecyclerView.Adapter.onBindViewHolder`，隐藏所有漏过数据层的低赞视频（包括本地缓存数据）

---

## 项目结构

```
DouyinLowLikeFilter/
├── app/
│   ├── build.gradle                          # App 级构建配置
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml               # 声明 Xposed 模块
│       ├── assets/
│       │   └── xposed_init                   # Hook 入口类路径
│       ├── java/com/example/douyinlowlikefilter/
│       │   ├── MainActivity.java             # 模块说明界面
│       │   └── MainHook.java                 # 核心 Hook 逻辑
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/
│           │   ├── strings.xml
│           │   ├── themes.xml
│           │   └── arrays.xml                # 模块作用域
│           └── xml/
├── build.gradle                              # 根构建配置
├── settings.gradle                           # 仓库配置（含 Xposed API）
├── gradle.properties
└── gradle/wrapper/
```


## 如何安装使用

1. **安装模块**：将编译好的 APK 安装到手机
2. **激活模块**：
   - 打开 LSPosed 管理器
   - 在「模块」页面找到「抖音低赞过滤」
   - 勾选启用，并选择作用域为 **抖音**
   - 强制停止并重新打开 LSPosed
3. **设置阈值**：
   - 打开抖音 → 我 → 设置 → 通用设置
   - 页面底部找到「低赞视频过滤」选项
   - 点击设置最低点赞数（默认 1000）
4. **重启抖音**：强制停止抖音后重新打开

### 验证模块是否生效

- 将阈值设为极大值（如 99999999）
- 如果推荐流视频全部消失 → Hook 成功
- 如果无变化 → Hook 点可能不适用于当前版本

---

## 版本适配

本模块针对 **抖音 37.9.0** 设计，理论适配一定范围的其他版本。




## 注意事项

- 模块**仅在推荐页生效**，不影响关注页、喜欢页、搜索页等其他页面
- 过滤的是**已加载**的数据，不会影响抖音的推荐算法
- 如果抖音频繁更新，模块可能需要跟着适配
- 模块安装后**无桌面图标**，设置入口在抖音「通用设置」页面
- 如需查看模块说明，可在 LSPosed 模块列表中点击本模块打开

---


---

## License

MIT License - 仅供学习交流使用
