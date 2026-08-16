# AGENT.md — Retro-AI-Scaler 实现说明

给后续开发者/AI 代理看的技术文档。**README 讲功能，这里讲为什么这么实现**，尤其是那些"看起来可以更简单，但实际会炸"的地方。

目标平台：Android 11+ 掌机（Helio G90T 起步），当前实测机型 AYANEO Air Mini（mt6785 / Helio G95，1280×960）。

---

## 1. 架构的硬约束：取景区和输出区不能重叠

`MediaProjection` 镜像的是**整块屏幕，包含本应用自己画的覆盖层**。由此推出：

- 输出盖在取景区上 → 下一帧捕获到自己的输出 → 反馈自激，几帧内画面糊掉；
- 给覆盖层加 `FLAG_SECURE` 想让录屏跳过它 → **SurfaceFlinger 会把该图层在镜像里替换成不透明黑块**（不是跳过）。全屏覆盖层 = 整帧纯黑 = 整机黑屏，且覆盖层置于所有应用之上，用户无法自救。
- 安卓 14 的单应用捕获（`MediaProjectionConfig.createConfigForUserChoice()`）能绕开，但**那是 API 34**，安卓 12/13 都没有。

所以：**RetroArch 缩到屏幕一角以原生分辨率绘制（取景窗），增强画面放在剩余空白区**。着色器对取景窗区域 `discard`，输出区外一律 `discard`。

安全底线（任何改动都不能破坏）：

1. 覆盖层**永不** `FLAG_SECURE`，**永不** `setZOrderOnTop(true)`（后者会盖住悬浮球和菜单，用户无路可退）；
2. 任何异常状态（几何未配置、着色器编译失败、无帧、退出中）一律退化为**全透明**，绝不是黑屏；
3. 通知栏常驻【停止】；崩溃恢复命令 `adb shell am force-stop com.retroai.scaler`，或重启设备（服务不自启）。

### 只在目标应用前台时渲染

覆盖层是全屏 `TYPE_APPLICATION_OVERLAY`，不加限制会盖住桌面和一切应用（触摸能穿透，但什么都看不见，表现为"切不回去"）。

`ForegroundAppMonitor` 用 `UsageStatsManager` 每 400ms 轮询前台应用（需「使用情况访问」特殊权限）。目标应用离开 → 擦成全透明 + `virtualDisplay.setSurface(null)` 挂起镜像。

> **踩坑**：挂起**必须**用 `setSurface(null)`，不能 release VirtualDisplay 再重建——重建出来的 VirtualDisplay 不会再往已有的 ImageReader surface 投帧，一帧收不到，看门狗会判定管线已死并关服务。

---

## 2. RetroArch 配置：两条互斥的代码路径

写配置前必读，否则会像最初那样把画面搞消失。

**主配置 `retroarch.cfg` 在 `Android/data/<pkg>/files/` 下，安卓 11 起任何第三方应用都无法访问**，MANAGE_EXTERNAL_STORAGE 也不覆盖。可写的只有覆盖配置目录 `rgui_config_directory`（默认 `/storage/emulated/0/RetroArch/config`），配合 `auto_overrides_enable=true` 生效。

RA 的覆盖优先级是 **核心 → 内容目录 → 单游戏**，所以要写核心目录下**每一个 .cfg**，只改核心那个会被后面的盖回去。

### `video_scale_integer` 决定另外四个键的含义

| `video_scale_integer` | 行为 |
|---|---|
| `"true"` | `custom_viewport_width/height` 只当**包围盒**，算出能塞进去的最大整数倍，位置由 **`video_viewport_bias_x/y`**（归一化 0~1）决定。**`custom_viewport_x/y` 完全不被读取。** |
| `"false"` | 直接用 `custom_viewport_x/y/width/height` 作原始矩形，坐标在**视频驱动的坐标系**里——GL 是左下原点，Vulkan 不同。 |

**实测教训**：按 GL 约定算 `custom_viewport_x/y` 喂给 Vulkan 驱动（本机 `video_driver=vulkan`），结果整屏纯黑（像素级验证除 HUD 外全是 0）。因此本项目**只写 bias，绝不写 x/y**，并保持整数缩放开启——顺带保证像素级 1:1 采样，这是超分最好的输入。

实际写入的键（`RetroArchConfigManager`）：

```
aspect_ratio_index    = "23"     # 自定义比例，唯一认视口尺寸的模式
video_scale_integer   = "true"
custom_viewport_width/height     # 原生分辨率 × sourceScale
video_viewport_bias_x/y          # 角落位置，无坐标原点歧义
input_overlay_enable  = "false"  # 整合包机型边框是全屏 input overlay，会被采进取景窗
video_shader_enable   = "false"  # RA 着色器在我们之前跑，等于喂给网络一张已滤过的图
```

> 关掉着色器**已验证安全**（单变量测试：只加这一行，画面正常，仅 LCD 网格效果消失）。黑屏的元凶是 `video_scale_integer=false` + `custom_viewport_x/y` 那一组。

### 位置靠测，不靠算

写配置只控制**尺寸和大致角落**；实际采样位置由 `nativeRequestSourceDetection()` 从捕获帧里**量出来**：

1. 先把自己的覆盖层、悬浮球、贴边条、菜单全部撤掉，空 4 帧（否则会拍到自己）；
2. 整屏缩到 320×240 回读，阈值化后做 8 连通域标记；
3. 候选必须是原生分辨率的**某个整数倍**（1~8x 全试），偏差 >35% 淘汰，剩下取面积最大者。

> 这个"整数倍匹配"不是装饰：只按包围盒取最大非黑区域时，会锁到我们自己的悬浮球（1:1 的圆）或天马G 顶部的 FPS 悬浮条。用原生比例做判据能把它们干净地排除。

目标应用切到前台 1.5 秒后自动执行一次（等游戏画出真实帧，避开黑屏）。写配置/还原配置成功后会**清空已锁定的取景窗**——尺寸变了旧的必然错位。

### 备份策略

`RetroArchBackupManager`：`/storage/emulated/0/RetroAIScaler/backups/<时间戳>/config/…` 全量快照，跳过 `.bin/.rom/.idx` 和 >256KB 的文件（config 目录里 29MB 绝大部分是 PS1 BIOS，静态且从不修改）。

- 服务启动时检查，无备份或最新的超过 15 天则新建；最多保留 5 份，**清理只在新建时发生，还原绝不删备份**；
- 还原只动带 `# --- modified by Retro-AI-Scaler ---` 标记行的文件（RA 忽略 `#` 开头），避免把用户这半个月的其它调整一起抹掉；
- 每个文件走**临时文件 + rename** 原子替换，中途被杀不会留下截断文件；
- 服务停止时自动还原（独立线程，`isDaemon=false`）；启动时若发现残留标记文件说明上次是崩溃，**先还原再备份**——顺序反了会把我们自己的修改当成用户原件存进快照。

---

## 3. 渲染管线

`CaptureBridge`（MediaProjection → ImageReader）→ JNI → `HwBufferReader`（AHardwareBuffer → EGLImage → 外部纹理，零拷贝）→ `GlRenderer` 合成 → 全屏透明 SurfaceView。

### 采样坐标：曾经的头号画质杀手

```glsl
// 错误：把 uv∈[0,1] 映射到纹素中心之间（0.5 → size-0.5）
vec2 texel = (uSourceRect.xy + 0.5 + uv * (uSourceRect.zw - 1.0)) / uCaptureSize;
// 正确：uv 跨越整个矩形，uv=(i+0.5)/size 必须精确落在纹素中心 i+0.5
vec2 pos = clamp(uv * uSourceRect.zw, vec2(0.5), uSourceRect.zw - vec2(0.5));
```

两种约定混用会让采样位置从左到右累计漂移满一个纹素，`nativeTexel(0)` 算出 `0.998` 而非 `0.5`——**每次采样都是相邻两个原生像素的 50/50 混合**。所有引擎拿到的输入都是糊的，边缘重建的邻居比较也建立在混合值上。改任何采样代码前先确认这一点。

### 放大引擎

| 引擎 | 原理 | 延迟 | 适用 |
|---|---|---|---|
| **像素边缘重建**（默认） | Scale2x/AdvMAME2x 规则，改写为任意倍率连续求值 + 象限边界 1 输出像素抗锯齿 | 零 | 2D 像素画 |
| GPU 锐化 | 边缘定向锐化 | 零 | 更硬朗的观感 |
| ESPCN Fast / HQ / Ultra | NCNN Y 通道超分 | +1 帧 | PS1、3D、有渐变和抖动的画面 |

**为什么 2D 像素画上小 CNN 打不过比较规则**：像素画不是低分辨率照片，是作者在原生分辨率上画完的成品，**没有"丢失的细节"可恢复**。真正有效的是判断"这串阶梯本该是斜线"并重建它——这是比较问题不是回归问题。ESPCN Fast/HQ 在合成验证集上只比双线性高 1.4~1.7 dB，对像素画不可见。

深度上去之后情况变了（Ultra：48 通道 × 6 层，**+5.9 dB**），说明之前是容量不足而非方法错。但 Ultra 是 3.4 GMAC/帧，只有 ncnn Vulkan 后端才现实。

采样一律用 `sharpUV()`（像素画专用 sharp bilinear：块内纯色、边缘锐利、过渡带压到 1 个输出像素）。网络输出也要按**它自己的 3x 网格**吸附，用普通双线性采会把重建出的锐利细节又抹掉。

### 异步推理 + 帧对配

推理 11~50ms，远超 16.6ms 帧预算，放在独立线程。但合成时**绝不能拿新的 RGB 配旧的重建亮度**（运动画面会出色边），所以 base RGB 被拷进原生分辨率纹理，和送去推理的那一帧配对，推理完成才整体切换（ping-pong 双缓冲）。代价是 ESPCN 通道慢 1 帧，着色器通道零延迟。

### 输出摆放

在取景窗四周的空白带里找**最大整数倍**：`k = min(带宽/原生宽, 带高/原生高)` 取整，选 k 最大者居中。**必须是整数倍**——分数倍（早期"全屏等比"给出 3.975x）意味着一个游戏像素落不到整数个输出像素上，每条像素边界都被插值，画面就是软的。全部按屏幕实时算，不写死。

### CRT 遮罩

**必须在线性光照下相乘**（`pow(2.2)` → 乘 mask → `pow(1/2.2)`）。sRGB 是伽马编码的，直接相乘会让中间调压暗得远超物理应有的程度，这是早期"一开遮罩就发闷"的根本原因。

遮罩按**屏幕像素**索引，不是游戏像素：真实荫罩的间距固定在玻璃上，与显示内容分辨率无关。按游戏像素索引会导致一个三元组需要 ≥6 个输出像素才画得下（4x 时不可能），且换平台/倍率时粗细会变。

三种几何取自 Lottes 的 `dotmask.slang`（Unlicense/公有领域）：光栅 Aperture（3px 竖条）、荫罩 Shadow（6×2 交错）、狭缝 Slot（沿 Y 错切）。扫描线用高斯束流剖面并按 `σ√(2π)` 归一化，保证全白场亮度不变。

---

## 4. 线程与生命周期（崩溃全出在这里）

**EGL context 同一时刻只能绑一个线程。**

- 渲染在 capture 线程，但主线程也会调 `clearOverlay()`（暂停/看门狗/销毁）。`clearOverlay()` 末尾**必须解绑** context，否则 capture 线程之后每帧 `EGL_BAD_ACCESS`，表现为"恢复后再也不出画面"。
- capture 线程退出前必须调 `nativeDetachEglContext()`，否则 context 留在死线程上，主线程的清理全部失败。

**推理线程持有 ncnn::Net，任何释放前必须先 `stopAiWorker()`。**

`loadEspcnModel()` 做了，早期 `unloadEspcn()` 漏了 → 主线程 `delete ncnn::Net` 时推理线程正在 `processLuminance` 里 → `pthread_mutex_lock called on a destroyed mutex` → SIGABRT。Fast/HQ 因窗口窄不易触发，Ultra（单次 50ms+）几乎必中，表现为"从 Ultra 切走就崩溃"。

**菜单窗口不能压在取景窗上**：菜单也会被 MediaProjection 录进去，哪怕只重叠几像素也会被采样放大，看起来像画面里有菜单的鬼影。`positionMenuAwayFromSource()` 把菜单停到取景窗对角。

---

## 5. 模型训练

```bash
uv venv --python 3.12 .venv && uv pip install --python .venv/bin/python torch numpy pillow
.venv/bin/python tools/train_espcn.py --epochs 70 --samples 7000          # 全部
.venv/bin/python tools/train_espcn.py --only ultra                        # 单个
```

产物直接落到 `app/src/main/assets/models/`，**直接写 ncnn `.param`/`.bin`，不经过 onnx2ncnn**：

```
.bin 每个 Convolution = [uint32 tag=0][weights f32][bias f32]
weights 布局 [out][in][kh][kw]，与 PyTorch 一致
ReLU 折进卷积（param 9=1），比独立层快
```

**训练对的构造是关键**：LR **不是** HR 的模糊降采样，而是同一套矢量场景在 1x 下的**独立渲染**。游戏原生帧本身就是原分辨率作画，不是照片降采样；用错配对会让网络学成过锐化。另混入 30% 硬边像素画样本，避免抹平精灵边缘。

通道数**必须是 8 的倍数**：ncnn 的 fp16 packing 布局否则退化到标量路径——实测 12/6 的网络跑 22.9ms，而大得多的 32/16 只要 13.8ms。

| 变体 | 结构 | MAC/px | 3x PSNR（合成集） |
|---|---|---|---|
| fast | 16ch × 1 隐藏层 | 2200 | 27.09（双线性 25.70） |
| hq | 32ch × 1 | 6704 | 27.38 |
| ultra | 48ch × 4 | 88032 | **31.45** |

---

## 6. 构建

```bash
./setup_toolchain.sh                    # SDK/NDK/CMake/ncnn 预编译库
JAVA_HOME=<jdk17+> ./gradlew assembleDebug
```

`app/src/main/cpp/libs/ncnn/` 是 77MB 预编译库，已 gitignore，由 `setup_toolchain.sh` 下载。ncnn 编译时带 Vulkan（`NCNN_VULKAN 1`），CMake 链接 `libvulkan`；加载模型时 `get_gpu_count()` 探测，有 GPU 走 Vulkan，否则退回 CPU。

---

## 7. 已知限制

- **取景窗占掉屏幕一角**，安卓 11 MediaProjection 架构的硬约束。API 34 的单应用捕获可彻底消除；平台签名 + `READ_FRAME_BUFFER` 走 `ScreenCapture.captureLayers` 只截 RA 图层也可以，但需要 OEM 配合。
- AYANEO Air Mini 固件里「系统设置 → Root 脚本」实测**无法执行 .sh**（点运行后一直转圈，脚本从未运行），root 路径在该机型上不可用。
- ESPCN 通道有 1 帧额外延迟；对操作延迟敏感时用像素边缘重建（零延迟）。
- 自动裁切（`frame_cropper.h/.cpp`）代码在但**未接入管线**，取景窗位置由第 2 节的探测流程负责。
- 仅打包 arm64-v8a。
- RA 主配置无法读写（安卓限制），因此不在备份范围内——本工具也从不修改它。

---

## 8. 第三方来源

- **ncnn** — Tencent，BSD 3-Clause，预编译库经 `setup_toolchain.sh` 获取。
- **CRT 遮罩几何** — 思路取自 Timothy Lottes 的 `dotmask.slang` / `energy_conservation_mask.h`（Unlicense / 公有领域），本项目为独立实现。
- **Scale2x / AdvMAME2x** — Andrea Mazzoleni 提出的算法规则，本项目为任意倍率的独立实现。
- 参考用的 RetroArch slang-shaders 仓库（`crt/`）已 gitignore，不随本项目分发。
