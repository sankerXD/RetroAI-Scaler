<div align="center">

# Retro-AI-Scaler

**安卓掌机上的 RetroArch 实时 AI 画面增强**

[English](README.md) · [简体中文](README.zh-CN.md)

</div>

---

复古游戏是为小屏幕画的。在现代掌机上被拉伸到 1280×960，结果就是发软发糊。
Retro-AI-Scaler 以**原始分辨率**捕获 RetroArch 的画面，重建之后再铺回屏幕上——
实时，60 FPS。

## 功能

### 四种放大引擎

| 引擎 | 适用 | 延迟 |
| --- | --- | --- |
| **像素边缘重建** | 2D 像素画——GBA、GBC、FC、SFC、MD | 零 |
| **GPU 锐化** | 更硬朗的原始观感 | 零 |
| **ESPCN Fast / HQ** | PS1、3D、有渐变和抖动的画面 | +1 帧 |
| **ESPCN Ultra** | 旗舰 SoC，走 GPU 推理 | +1 帧 |

像素边缘重建会判断"这一串阶梯本该是一条斜线"并把它重建成斜线，同时完全不动
平坦区域和单像素细节。ESPCN 系列在亮度通道上跑神经网络，重建倍率可选
1× / 2× / 3× / 4×。

### 精确整数缩放

输出永远是原生分辨率的**精确整数倍**，按你的屏幕实时计算。一个游戏像素正好落在
整数个屏幕像素上，边缘保持锐利，而不是被插值成一团。

### CRT 显示模拟

光栅、荫罩、狭缝三种遮罩几何，外加高斯束流剖面的扫描线。全部在线性光照下运算，
所以打开之后画面不会发暗发闷。

### RetroArch 自动配置

选好机种，点启动。RetroArch 的配置会被自动写入，写之前先备份，停止时自动还原。
取景窗位置靠**实测屏幕**得出，不用你填任何坐标。

### 每平台独立记忆

引擎、AI 倍率、显示效果都按机种分别保存，换平台不用重新调。

## 运行要求

- Android 11 及以上，arm64
- 已安装 RetroArch
- 掌机性能：Helio G90T 起步（ESPCN Ultra 需要旗舰 GPU）

## 上手

1. 安装 APK 并打开。
2. 授予列出的三个权限：**悬浮窗**、**使用情况访问**、**所有文件访问**。
3. 在**游玩机种**里选择你的平台。
4. 点**启动 AI 增强滤镜**，同意录屏授权。
5. 重启 RetroArch 并载入游戏，画面自动增强。

停止用通知栏的【停止】或悬浮球菜单里的按钮，退出时会自动还原 RetroArch 配置。

> 万一出问题，`adb shell am force-stop com.retroai.scaler` 可以清除，
> 直接重启设备也行——服务不会自启动。

## 构建

```bash
./setup_toolchain.sh          # Android SDK / NDK / CMake / ncnn
./gradlew assembleDebug
```

模型训练和实现细节见 [AGENT.md](AGENT.md)。

## 致谢

- [ncnn](https://github.com/Tencent/ncnn) —— Tencent，BSD 3-Clause
- CRT 遮罩几何参考 Timothy Lottes 的公有领域遮罩实现
- Scale2x / AdvMAME2x 边缘规则由 Andrea Mazzoleni 提出

## 许可证

MIT
