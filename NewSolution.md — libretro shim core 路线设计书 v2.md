# NewSolution.md — libretro shim core 路线设计书 **v2**

> **状态：门 0 ~ 门 4 全部通过，风险 #1 已量化并结案。这条路线走通了。**（2026-08-22）
>
> **实施后改掉了本文四条结论，先读这段：**
>
> 1. **§4.4 的 Binder 方案已作废，改 loopback TCP。** 它的地基是从 native 拿
>    JavaVM，而 `JNI_GetCreatedJavaVMs` 到 API 31 才进 NDK——安卓 11 上它在 ART
>    APEX 里的 `libnativehelper.so`，应用的 linker namespace 打不开。那是整节
>    §4.4 的前提，却从没被任何一道门验证过。**实测结论见门 1。**
> 2. **§5.3「`.info` 整个不需要做」是错的。** RA 加载核心时确实不读它，但
>    **存档点能力是从它读的**，没有就连 `retro_serialize_size()` 都不调。而且补
>    上文件还不够，`core_info.cache` 也得失效。**由 shim 自己装**，见 §5.3。
> 3. **§5.2「重启天马G」是错的。** 国内的天马G 是大光圈魔改版，静态扫描，必须
>    走 设置 → 重新加载。**这个失败在日志里长得跟"改写没生效"一模一样。**
> 4. **门 0 的两项残留已答**：SD 卡上 `metadata.pegasus.txt` 所在目录
>    create+rename+delete 全部放行；`auto_overrides_enable`（RA 菜单里叫
>    「自动加载独立配置文件」）**是开的**——§3 末尾那条"我们写的覆盖配置可能
>    从来没生效过"的怀疑，销案。
>
> 代码在 `shim/`（帧链路在 `shim/frame_link.c`）和 `app/.../shim/`。
>
> 目标问题：Android 11 上屏幕捕获架构导致 RetroArch 画面缩到屏幕一角、RA 原始画面与 AI 增强画面同屏并存。3.5 寸机型上这个代价无法接受。
>
> 本文档是**可直接执行的施工书**。所有"待验证"的地方都已折叠进第 7 节的门 0~门 4，按顺序做，每道门有明确判据和失败对策。

---

## 0. v2 相对 v1 改了什么（重启时先读这段）

v1 的整体判断是对的，但有四处会让人白干的错误，一处遗漏的高危风险，以及一条不成立的暂缓理由。全部依据 2026-08-20 的真机日志。

| # | v1 的说法 | v2 的结论 | 依据 |
|---|---|---|---|
| 1 | §3.3 首选读 `shim.cfg`，真核心路径可指向 `/sdcard` | **删除。** `/sdcard` 是 `noexec`，从那里 dlopen 任何 `.so` 都失败。真核心和 shim 都必须在 RA 私有目录里 | Android 挂载策略；日志确认核心在 `/data/data/…/cores/` |
| 2 | 风险 #1「成败取决于整合包是否把核心放共享存储」 | **方向反了。** 核心放共享存储反而必死。实测落在最好的一格：`/data/data/com.retroarch.aarch64/cores/`，SELinux `execute` 放行 | §3 的 `avc: granted` 审计行 |
| 3 | §3.4 IPC 用 abstract unix socket | **改 Binder。** RA 是 `untrusted_app_27`、我们的 APP 是 `untrusted_app`，且 MLS 分类逐应用不同，跨进程 socket 基本没有通过的可能 | §3 的 `scontext=` 字段 |
| 4 | §3.2 只处理 `data == NULL` | **漏了 `RETRO_HW_FRAME_BUFFER_VALID`（`(void*)-1`）。** memcpy 一个 -1 指针 = RA 进程当场 SIGSEGV | libretro.h |
| 5 | 风险清单里没有 | **新增最高危风险：色彩分布漂移。** shim 的像素比现有捕获路径"更干净"，而模型是在"脏"路径上训练的（AGENT.md §12.7） | AGENT.md §12.7 |
| 6 | §7 暂缓因为「本机构建环境零基础」，§4.1 给 PowerShell 命令 | **不成立。** 开发机是 macOS，`setup_toolchain.sh` 已装好 NDK/CMake；shim 不需要 JDK、不需要 ncnn、不需要 Gradle | AGENT.md §6 |
| 7 | §4.2 需要动态生成 `.info` | ~~整个不需要~~ → **v1 是对的，v2 这条判错了。** RA 加载核心时确实不读 `.info`，但**存档点能力是从 `.info` 读的**，没有它 RA 连 `retro_serialize_size()` 都不调。做法不是"动态生成"而是"由 shim 复制真核心那份" | 2026-08-22 实测，见 §5.3 |
| 8 | §3.5 每帧 `AHardwareBuffer_create` | **改成 3 块的池子，创建一次循环用** | 分配开销 |

另外新增了三条 v1 没写的设计要点：拒绝 `GET_CURRENT_SOFTWARE_FRAMEBUFFER`（§4.2）、真核心 dlopen 时机必须提前到 `retro_get_system_info`（§4.3）、全屏覆盖层会挡死 RA 菜单及其解法（§4.7）。

**中途一度考虑过的「改名接管」（shim 复制自己顶替真核心文件名、真核心备份成 `_orig_`）已否决**，理由见第 10 节。

---

## 1. 背景：双画面的根因

"RA 缩角落 + 双画面"**不是 Android 11 的 bug，是现有 MediaProjection 架构的必然产物**：

1. `MediaProjection` 在 API 34 之前只有**整屏镜像**一种模式，镜像内容包含我们自己的全屏覆盖层（AGENT.md §1）。
2. 覆盖层盖住取景区 → 下一帧捕获自己的输出 → 反馈自激；加 `FLAG_SECURE` 排除自身 → SurfaceFlinger 替换为不透明黑块 → 全屏黑屏。两条路都死。
3. 因此项目**主动**改写 RA 配置（`RetroArchConfigManager.applyViewport`），把 RA 缩成角落 1x 取景窗；合成着色器对取景区 `discard` 挖洞（`gl_renderer.cpp` `uProtectSource`）。用户看到的"角落小窗"就是这个洞透出来的 RA 原生画面。
4. API 34+ 已有单应用捕获解决此问题（AGENT.md §11，SINGLE_APP 路径已实现）；Android 11 上该路径不存在。
5. 已实测否决的绕行路线：**虚拟显示承载 RA**（虚拟屏缺 `FLAG_TRUSTED`，活动被静默重定向回 display 0，Shizuku 权限上限即 shell 故一并作废，AGENT.md §11.7）、**主测试机 root 不可用**（AGENT.md §7）。

**推论**：只要帧数据仍从 SurfaceFlinger 镜像来，取景窗就必须存在。要根除双画面，必须让帧**不经过屏幕合成**直接到达推理管线。

### 为什么不是其它路线

| 路线 | 结论 |
|---|---|
| hook RA 渲染 so（PLT/inline hook `eglSwapBuffers` / `vkQueuePresentKHR`） | 技术最贴合，但无 root 时无法把代码送进 RA 进程（SELinux 禁 ptrace、linker namespace 隔离 `LD_PRELOAD`、Vulkan layer 对非 debuggable 应用不可挂载）。仅 root/Zygisk 或重打包 RA 时成立 |
| RetroArch fork（内置增强管线） | 效果等同 shim 但维护成本高（跟随上游 rebase）、用户必须换装模拟器、GPLv3 分发义务重。仅当 shim 走不通时的备胎 |
| RA 自身 slang shader | GLSL/slang 跑不了 ESPCN/深度网络等神经推理，不具备替代资格 |
| Vulkan layer 注入 / 免注入跨进程纹理共享 | 前者仍需注入前提；后者只是传输层，生产者仍需在 RA 进程内产生帧，不构成独立方案 |
| 自己做 libretro 前端（不用 RA） | 效果等同，但要重造存档、金手指、按键映射、BIOS、游戏列表、UI。shim 用极小代价拿到同样的收益，此路线降级为备胎 |

---

## 2. 方案核心：libretro shim core

做一个"假的 libretro 核心" `.so` 放进 RA 的核心目录，RA 加载它；shim 内部 `dlopen` 真实核心并**透传全部 libretro API**，唯独在 `retro_set_video_refresh` 处包一层，把软件渲染核心的 `(data, width, height, pitch)` 原生分辨率 CPU 像素帧通过 IPC 递给 RetroAI-Scaler APP。

**为什么成立**：RA 自己主动加载我们的 `.so`——**不存在"注入"，不需要 root、不需要 ptrace、不需要平台签名**，绕开了 hook 路线的全部前提，且在所有安卓版本上同时成立。

**架构级收益**：shim 帧源跑通后，MediaProjection 整条链路可退役——`uProtectSource` 挖洞、取景窗探测、整数倍吸附、捕获模式判定、RA 视口角落改写**全部作废**。RA 恢复正常渲染，overlay 全屏覆盖增强画面，双画面从根上消失。

**顺带的收益**：

- `video_shader_enable=false` 这个配置键不再需要写——shim 在 RA 的视频管线**之前**截帧，RA 的着色器污染不到我们的输入。少改一个键就少一份还原负担。
- `retro_get_system_info` 透传 ⇒ RA 依然认为这是 VBA-M，**存档路径、核心选项文件、金手指、游戏列表关联全部原封不动**。
- 省掉 SurfaceFlinger 镜像一跳（该开销曾占约 31% CPU）。

**顺带的新风险**：见 §8 的风险 #1（色彩分布漂移），这是 v1 完全没有预见的一条。

---

## 3. 已实测确定的环境事实（2026-08-20，AYANEO Air Mini / Android 11）

这一节是**证据**，不是推测。后面所有设计决策都由它推出。

抓取方式：RA 菜单 → 设置 → 日志 → 打开详细日志；从天马G（Pegasus）正常点一个 GBA 游戏；`adb logcat -d | grep -i RetroArch`。

关键四行：

```
RetroArch: [ENV] Android version (major : 11, minor : 0, rel : 0)
RetroArch: [ENV] Config file: "/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg".
RetroArch: [ENV] Libretro path: "/data/data/com.retroarch.aarch64/cores/vbam_libretro_android.so".
RetroArch: [ENV] Auto-start game "/storage/7C32-2F40/Roms/GBA/塞尔达传说 缩小帽.zip".
```

加上一行 SELinux 审计：

```
avc: granted { execute } for path="/data/data/com.retroarch.aarch64/cores/vbam_libretro_android.so"
  dev="dm-11" ino=837836
  scontext=u:r:untrusted_app_27:s0:c131,c256,c512,c768
  tcontext=u:object_r:app_data_file:s0:c131,c256,c512,c768
  tclass=file app=com.retroarch.aarch64
```

由此得到五条：

| # | 事实 | 后果 |
|---|---|---|
| E1 | 核心在 `/data/data/com.retroarch.aarch64/cores/`，`execute` **放行** | 从该目录 dlopen 可行。**门 0 的核心问题通过** |
| E2 | 前端是 **Pegasus Frontend**（`org.pegasus_frontend.android`），它**显式传了核心路径** `-e LIBRETRO …` | 不能指望 RA 用"上次的核心"。但 Pegasus 的启动行是纯文本、在我们可写的位置 ⇒ **这是激活方式的入口**（§5） |
| E3 | RA 版本 1.21.0（2025-07-12 构建），包名 `com.retroarch.aarch64` | 动态核心加载可用 |
| E4 | RA 的 SELinux 域是 `untrusted_app_27`，MLS 分类 `c131,c256,c512,c768`（逐应用不同） | **跨应用 abstract socket 基本不可能**（域不同 + MLS 分类不同）⇒ IPC 走 Binder（§4.4）。**门 1 定案** |
| E5 | `untrusted_app_27` ⇒ RA 的 targetSdk ≤ 27，因此享有 legacy 存储权限，且被允许从私有目录 execute | ① RA 读 `/sdcard` 自由，可做兜底控制通道；② **整条路线依赖 RA 保持低 targetSdk**，见风险 #6 |

> **E5 值得单独说。** 那行 `avc: granted` 是 `auditallow` 打出来的——Google 在专门审计"从 app 私有目录执行代码"这个行为。`untrusted_app_27` 允许它；targetSdk ≥ 28 的 `untrusted_app` 域**不允许**。也就是说这条路线站在一个我们控制不了的外部前提上。风险不高（RA 自己的核心下载器也依赖同一条，动了会整个崩），但必须记着。

另外从 Pegasus 配置文件确认的：

- 配置路径只有两处：`机身存储/Roms/<机种>/metadata.pegasus.txt` 和 `SD卡/Roms/<机种>/metadata.pegasus.txt`。**不存在其它位置**（配错了 Pegasus 就启动不了游戏）。本机 GBA ROM 在 SD 卡上（`/storage/7C32-2F40/Roms/GBA/`），所以实际生效的是 SD 卡那一份。
- `launch:` 块的实际内容：

```
launch: am start --user 0
  -n com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture
  -e ROM {file.path}
  -e LIBRETRO /data/data/com.retroarch.aarch64/cores/vbam_libretro_android.so
  -e CONFIGFILE /storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg
  --activity-clear-top
```

> **附带发现，与 shim 无关但要单独查：** `CONFIGFILE` 指向 `/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg`——安卓 11 起第三方应用无法访问该目录（AGENT.md §2）。现有的视口覆盖配置写在 `rgui_config_directory` 下靠 `auto_overrides_enable` 生效，理论上不受影响，但**这条链路值得单独确认一次**：如果 Pegasus 传的 cfg 里 `auto_overrides_enable=false`，那我们写的覆盖配置从来就没生效过。这与 shim 路线独立，但它会影响"shim 跑通前"的现状判断。

---

## 4. shim 设计

### 4.1 必须导出的 25 个符号（缺一即被 RA 拒载）

已对照 libretro-common 官方 `libretro.h` 核对，清单是全的。

**A. 回调注册组（6）**

| 符号 | shim 处理 |
|---|---|
| `retro_set_environment` | 保存 RA 的 `env_cb`，**转发前包一层自己的 environ 包装器**（§4.2） |
| `retro_set_video_refresh` | **拦截点**：保存 RA 的 `video_cb`，向真实核心注册包装回调 |
| `retro_set_audio_sample` | 原样转发 |
| `retro_set_audio_sample_batch` | 原样转发 |
| `retro_set_input_poll` | 原样转发 |
| `retro_set_input_state` | 原样转发 |

**B. 生命周期/查询组（11）**：`retro_init`、`retro_deinit`、`retro_api_version`（**返回真实核心的值，勿硬编码**）、`retro_get_system_info`、`retro_get_system_av_info`（记下 `geometry.base_width/base_height/max_width/max_height` 作为帧尺寸上限）、`retro_set_controller_port_device`、`retro_reset`、`retro_run`、`retro_get_region`、`retro_get_memory_data`、`retro_get_memory_size`。

**C. 存档/加载组（8）**：`retro_serialize_size`、`retro_serialize`、`retro_unserialize`、`retro_cheat_reset`、`retro_cheat_set`、`retro_load_game`、`retro_load_game_special`、`retro_unload_game`。可选追加 `retro_serialize_v2`/`retro_deserialize_v2`（RA 按可选符号探测）。

编译后用 `llvm-nm -D` 自查导出符号完整性。**再额外导出一个标识符号 `retroai_shim_magic`**，用于自识别（§5.4 的还原逻辑要用）。

### 4.2 environment callback 包装要点

- **`RETRO_ENVIRONMENT_SET_PIXEL_FORMAT`(10)**：决定输入像素格式——`0RGB1555=0` / `XRGB8888=1` / `RGB565=2`。shim 必须记录并随帧头传给 APP。
- **`RETRO_ENVIRONMENT_SET_HW_RENDER`(14)**：真实核心请求硬件渲染（PS1/N64 GPU 核心）时 video cb 给的是 GPU 纹理，**shim 返回 false 拒绝**并打 log，切勿假意接受。
- **`RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER`(128)：返回 false 拒绝。** ← v1 没有这条。允许的话核心会直接往 RA 提供的缓冲里画，而那块内存可能是 **write-combined** 的——**从 WC 内存 memcpy 出来比普通内存慢一个量级**，会在 `retro_run` 里直接吃掉 1ms+。拒了它，核心用自己的 cached 缓冲，读回是快的。
- `SET_GEOMETRY`(37)、`SET_ROTATION`(2)：运行时改分辨率/旋转，shim 记录并随帧头上报。
- 其余 env 请求**一律原样转发，不要吞**。

**video_refresh 包装回调里的三个分支（顺序不能错）：**

```c
static void shim_video_cb(const void *data, unsigned w, unsigned h, size_t pitch)
{
    if (data == NULL) {
        /* 复用上一帧，跳过递帧 */
    } else if (data == RETRO_HW_FRAME_BUFFER_VALID) {   /* (void*)-1 */
        /* 帧在 GPU 里，我们拿不到。跳过递帧，绝不解引用。 */
        /* 理论上拒绝 SET_HW_RENDER 后不该出现，但某些核心照发。 */
    } else {
        shim_publish_frame(data, w, h, pitch);
    }
    if (g_ra_video_cb) g_ra_video_cb(data, w, h, pitch);   /* 始终转发，别让 RA 冻结 */
}
```

> **`RETRO_HW_FRAME_BUFFER_VALID` 就是 `(void*)-1`。** 漏掉这一分支 = memcpy 解引用 -1 = **RA 进程当场 SIGSEGV**。shim 崩溃即 RA 崩溃，这是第一个实例。

### 4.3 真实核心的定位：按文件名派生，不要配置文件

**规则**：shim 自身文件名去掉第一处 `_shim` 就是真实核心的文件名，在**同目录**下 dlopen。

```
vbam_shim_libretro_android.so   →   vbam_libretro_android.so
snes9x_shim_libretro_android.so →   snes9x_libretro_android.so
```

实现：`dladdr()` 取自身绝对路径 → dirname 得到 RA 的 cores 目录 → 拼出真核心路径 → `dlopen(RTLD_NOW|RTLD_LOCAL)`。

**为什么不用 `shim.cfg`**：

- 配置文件放 `/sdcard` 的话，里面写的路径若指向 `/sdcard` 就必然 dlopen 失败（`noexec`），只能指向 RA 私有目录——那还不如直接从自身文件名推。
- 一个 shim 只能绑一个核心。用文件名派生的话，**复制几份改个名就支持多个系统**，各自独立，RA 那边看到的是几个不同的核心，游戏列表关联、扩展名匹配、存档路径全部照常。
- 少一个要分发、要维护、会写错的文件。

**时机（v1 漏了）**：RA 加载 `.so` 之后**第一个调用的是 `retro_get_system_info`**，那时就必须能回答真实核心的 `library_name` / `valid_extensions` / `need_fullpath`。所以真核心的 dlopen 必须在**首次进入任何导出函数时**惰性完成（用 `pthread_once`），**不能等到 `retro_load_game`**。IPC 连接可以晚到 `retro_load_game`，那是另一回事。

**失败处理**：dlopen 或 dlsym 失败时，`retro_get_system_info` 填一个可读的 `library_name`（如 `"RetroAI Shim (real core missing)"`），`retro_load_game` 返回 false，打清晰的 log。**绝不让 RA 崩溃。**

### 4.4 IPC：Binder，不是 abstract socket

**v1 的 abstract socket 方案作废**，理由见 E4：RA 是 `untrusted_app_27`、我们的 APP 是 `untrusted_app`，SELinux 域不同；且两者的 MLS 分类逐应用不同。跨这两道的 unix socket 基本没有通过的可能，而 Binder 是安卓官方的跨应用通道，一定放行。

**结构**：

```
[RA 进程]                                    [RetroAI-Scaler 进程]
 shim (C)
   └ JNI_GetCreatedJavaVMs() 拿 VM
   └ AttachCurrentThread
   └ 反射 ActivityThread.currentApplication() 取 Context
   └ bindService(Intent("com.retroai.scaler.SHIM_FRAME_SOURCE"))
        ──────── AIDL ────────►      导出的 ShimFrameService
        ◄─── 3 × ParcelFileDescriptor（SharedMemory）+ 1 × 通知 fd ───
   └ 每帧：写共享内存 slot → 往通知 fd 写 8 字节（slot + seq）
```

- **共享内存**：`ASharedMemory_create`（底层是 memfd），fd 经 `ParcelFileDescriptor` 走 Binder 传递。这是被官方支持的路径，不受 MLS 影响。
- **通知信道**：一个 pipe 或 eventfd，同样经 Binder 传过来。**不要每帧发一次 Binder 事务**——虽然 oneway 事务扛得住 60fps，但 pipe 写 8 字节的开销低一个量级且不进 binder 驱动。
- **帧头**（放在每块共享内存的头 64B）：`magic, seq, slot, width, height, pitch, pixel_format, rotation, timestamp_ns`。
- **缓冲策略**：三缓冲环 + 原子 seq；三槽都未归还时**直接丢帧**，绝不阻塞 `retro_run`（游戏帧率优先）。
- **尺寸**：按 `get_system_av_info` 的 `max_width × max_height × 4` 分配，上限保守取 1024×768×4 ≈ 3MB/块。GBA 实际只有 240×160。

**安全底线（每一条都是"shim 崩溃即 RA 崩溃"推出来的）**：

1. APP 未连接时 shim **零开销纯透传**；
2. 任何 I/O 错误立即降级为纯透传并后台重连（退避 1s）；
3. `retro_run` 内**严禁阻塞 syscall**、严禁加可能被别人持有的锁；
4. 所有跨进程等待都要有超时；
5. shim 不用 C++ 异常、不用 STL（§6）。

**兜底控制通道**：由 E5，RA 有 legacy 存储权限、读 `/sdcard` 自由。所以"APP 在 `/storage/emulated/0/RetroAI-Scaler/` 放标记文件、shim 每次加载时读一次"这条极简单向通道随时可用，且完全不受 Binder/SELinux 影响。用它传开关、传卸载指令足够。

### 4.5 APP 侧接入点

行号以 v1 记录的为准，重启时需重新核对。

| 接入点 | 位置 | 说明 |
|---|---|---|
| 帧源启动 | `OverlayService.kt` `onCreate()` / `onStartCommand()` | shim 模式允许**无 MediaProjection intent extra** 启动 |
| 替代 CaptureBridge | `OverlayService.kt` `startCapturePipeline()` | shim 模式下构造 `ShimFrameSource`，复用 start/stop/pause/resume 形状 |
| 看门狗兼容 | `OverlayService.kt` `watchdogRunnable` | `ShimFrameSource` 需暴露 `lastFrameAtMs/renderedFrames/startedAtMs`，否则 4s 首帧超时**误杀服务** |
| 帧喂入 native | `NativeBridge.kt` 新增 `nativeProcessShimFrame(...)` | `native-lib.cpp` 对照现有实现（同一把 `gPipelineMutex` + `ensureEglContextCurrent` + `renderFrame` + `publishStats`） |
| 像素导入 | `hw_buffer_reader.cpp` `bindHardwareBufferToTexture()` | 现有路径 AHardwareBuffer→EGLImage→`GL_TEXTURE_EXTERNAL_OES` |
| 几何短路 | `OverlayService.kt` `pushGeometry()` | shim 模式：源矩形=整帧 `(0,0,w,h)`、`protectSource=false`，跳过 `scheduleAutoDetect()`、`scheduleCaptureModeProbe()`、capture-mode 配置改写；`OutputProfileManager` 的角落/bias 几何全部短路 |

**喂帧路线：走 AHardwareBuffer 复用现有零拷贝导入，不改 shader。** 理由：渲染器与全部 shader 硬编码 `GL_TEXTURE_EXTERNAL_OES` 输入（`gl_renderer.cpp` 三处），新增 `sampler2D` 路线改动面大且撞 `check_shaders.py` 门禁。

做法（**v1 的每帧新建已改**）：

- 在几何确定时**一次性创建 3 块 AHardwareBuffer 循环使用**，不要每帧 `AHardwareBuffer_create`——一次分配是几百 µs 到 ms 级，还会碎片化。
- 格式 `AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM`，usage 只用最保守组合 `CPU_WRITE_OFTEN | GPU_SAMPLED_IMAGE`（Helio 有自定义 usage 格式错乱前科，见 `CaptureBridge.kt:21-24`）。
- lock → 写入（含 1555/565→8888 转换）→ unlock → 走现有路径。240×160 下每帧 memcpy 约 0.1ms 量级。

### 4.6 像素格式（最易花屏处）

三种输入格式，且 **pitch 可能 ≠ width × bpp**（核心常按 2 的幂对齐）。APP 侧统一转 RGBA8888。

| libretro 格式 | 值 | 布局 |
|---|---|---|
| `0RGB1555` | 0 | 16bit，高位空，R5 G5 B5 |
| `XRGB8888` | 1 | 32bit，X8 R8 G8 B8 |
| `RGB565` | 2 | 16bit，R5 G6 B5 |

**5bit/6bit → 8bit 的扩展必须用位复制（`(c << 3) | (c >> 2)`），不是简单左移**——左移会让最大值变成 248 而不是 255，整体偏暗且白点不白。这个错误在静态图上肉眼很难发现，但会系统性地改变输入分布（见风险 #1）。

**逐格式用静态帧比对验证**，这是新增代码里风险最高的部分。

### 4.7 覆盖层与 RA 菜单（v1 漏了）

shim 模式下 overlay 全屏不透明，**RA 的快捷菜单会被完全挡死**——用户存档、退出、切核心都摸不着。现状（角落取景窗）反而没这个问题。

**信号是现成的**：RA 菜单弹出时默认暂停核心（`menu_pause_libretro`），于是 `retro_run` 停了，帧流断了。

- APP 侧：连续 N ms（建议 250ms）没有新帧 → 覆盖层退成全透明；帧恢复 → 重新显示。
- 这条规则和现有看门狗的"无帧即判定管线已死"**必须分开**——前者是正常状态，后者是故障。别复用同一个计时器。
- 如果用户关掉了 `menu_pause_libretro`，退化为悬浮球手动切换。悬浮球本来就有。

---

## 5. 激活方式：改 Pegasus 的 launch 行

这一节回答"小白用户怎么用"。结论是：**用户只需要在 RA 里点一次「安装和还原核心」，之后永远照常从天马G 点游戏。**

### 5.1 为什么是这条路

由 E2，核心是 Pegasus 用 `-e LIBRETRO <绝对路径>` 显式指定的。所以我们只要改那一行文本，让它指向 shim。

这条路的好处：

- **完全可逆**，改回那一个 token 就恢复原状；
- **RA 私有目录里除了多一个文件，什么都没动**——不覆盖、不备份、不改名，没有把真核心弄丢的可能；
- 用户不需要理解"核心"是什么，不需要知道 RA 支不支持传参。

### 5.2 用户视角的完整流程

1. 在我们的 APP 里点【启用 shim 帧源】。APP 把 `vbam_shim_libretro_android.so` 写到 `/storage/emulated/0/RetroAI-Scaler/cores/`。
2. APP 弹一个引导页（配图），告诉用户：打开 RetroArch → 加载核心 → **安装和还原核心** → 选中那个文件。RA 会把它复制进 `/data/data/com.retroarch.aarch64/cores/`。
3. 回到我们的 APP 点【完成】。APP 扫描并改写 Pegasus 的 `launch:` 行。
4. **提示用户在天马G 里走一次「设置 → 重新加载」。**

   > **不是"重启天马G"，这条改过一次（2026-08-22 实测）。** 国内用的天马G 几乎都是**大光圈的魔改版**，它把上游 Pegasus"每次启动重扫"改成了**静态扫描**——理由是每次进 APP 要等 5~6 秒。于是 `Roms` 下的任何改动**不重扫就永远不生效**，而重启 APP 并不触发重扫。
   >
   > 这条一度让人以为 shim 路线不成立：metadata 文件改对了、包名对了、整机重启过了，RA 拿到的仍然是旧核心路径。**判据是眼睛能看到的东西**——把某个游戏的标题临时改成 `[SHIM]xxx`，重扫后标题变了，才说明文件真被读了。查日志查不出这个，因为日志里那行 `[ENV] Libretro path` 只忠实转述 intent，而 intent 来自内存里那份陈旧的扫描结果。
   >
   > 上游 Pegasus 的行为（启动即重扫）**不能拿来当假设**。面向用户的引导文案要按魔改版写。
5. 之后照常从天马G 点游戏。

第 2 步是唯一需要用户操作 RA 的地方，一次性。**这一步无法自动化**——我们写不了 RA 的私有目录，这是安卓的边界，不是设计缺陷。

### 5.3 `.info` 文件**必须**做，由 shim 自己装（2026-08-22 改写）

> **这一节原先的结论是「`.info` 整个不需要做」，那是错的，而且错得很隐蔽。** 原文的三条理由里，前两条是对的（RA 按绝对路径加载时确实不读 `.info`；那个目录我们的 APP 确实进不去），第三条——「代价只是菜单里显示为未知名称」——是错的。真实代价是**存档点整个消失**。
>
> 症状：shim 装好后画面、声音、按键、快捷菜单、退出、电池存档（`memory_size(0)=8192`）全部与真核心逐位一致，**只有快速存档/读档报「核心不支持状态存储」**。
>
> 根因分两层，缺一层都修不好：
>
> 1. **RA 从 1.16 起把存档点能力挂在 `.info` 的 `savestate_support_level` 上。** 查不到 info 条目就按不支持处理，**连 `retro_serialize_size()` 都不会调**。判据就是这个：在 shim 里给 `retro_serialize_size` 加一行日志，一次启动就能把"我们答错了"和"根本没人问"分开——**没有那行日志，故障就在这个文件之外**。
> 2. **补上 `.info` 仍然无效，因为 `core_info.cache`。** RA 在同一个目录里缓存解析结果，而那份缓存是我们这个核心存在之前建的，里面没有它。文件躺在那儿也不会被读。

**实测事实**（Air Mini / RA 1.21.0）：

- info 目录是 **`/data/user/0/com.retroarch.aarch64/info`**，从 `retroarch.cfg` 的 `libretro_info_path` 读出来的。**不是**默认的 `Android/data/<pkg>/files/info`——所以硬编码路径会失败，必须解析配置。
- 那是 **RA 的私有目录**，`adb shell` 都读不了，我们的 APP 更不可能。中途曾以为它在 `/storage/DDE9-B41D/info`（RA 自己 UI 里看到的路径），那个卷在 adb 和我们 APP 的挂载命名空间里都不存在——**每个应用的 `/storage` 视图是独立的**，别拿一个进程看到的路径去另一个进程里找。
- 目录里 287 个 `.info` + 1 个 `core_info.cache`。
- `vbam_libretro.info` 里写着 `savestate = "true"` / `savestate_features = "deterministic"`。

**做法：shim 自己装。** 它跑在 RA 进程里、用 RA 的 UID，RA 能写的地方它都能写——这是这条路线独有的能力，APP 侧永远做不到。实现在 `shim/shim.c` 的 `install_info_file()`：

1. 从自身路径推出包名（不硬编码），读 `retroarch.cfg` 拿 `libretro_info_path`，读不到才退回默认路径；
2. 把**真核心那份 `.info` 逐字节复制**成 `<name>_shim_libretro.info`。复制而不是自己拼，是因为我们要的正是"RA 眼里 shim 就是那个核心"——存档点、游戏列表关联、固件清单，凡是 RA 从这个文件读的东西全部一致；
3. **已存在就绝不覆盖**（幂等，也吃不掉用户真有的文件）；
4. **只在缓存比我们的 `.info` 旧时删缓存**。这个条件让它自我终止：RA 一重建，条件就不成立，之后永远不再动它。缓存本来就可再生，认错文件的代价只是一次启动变慢。

三条都在真核心确认加载成功**之后**才执行——这里失败绝不能被误读成核心加载失败。

> **实测比预期好一档**：删缓存的**当次**存档就恢复了，不用等下一次启动。但引导文案仍按"可能需要再启动一次"写，别把这个当保证。

### 5.4 配置改写的纪律

**完整套用 `RetroArchBackupManager` 的那套机制**，这是踩坑换来的，不要重新发明：时间戳全量快照 → 临时文件 + rename 原子替换 → 服务停止时自动还原 → 启动时发现残留说明上次是崩溃，先还原再备份。

针对 Pegasus 的特殊点：

1. **两处都要扫**：`/storage/emulated/0/Roms/<机种>/metadata.pegasus.txt` 和 `/storage/<SD-UUID>/Roms/<机种>/metadata.pegasus.txt`。SD 卡的 UUID 要枚举（本机是 `7C32-2F40`，不能写死）。

   > 本机实测（2026-08-22）：机身存储上**根本没有 `Roms` 目录**，27 份带 `-e LIBRETRO` 的 metadata 全在 SD 卡上。规则照旧两处都扫，但"两处都有"不是常态。

2. **一个文件里可能有多个 `-e LIBRETRO`，而且分两种。** GBA 那份 328KB、466 个游戏，里面有：collection 级的 `launch:`（第 4 行，管全部游戏），以及**单游戏 override**（`超级机器人大战OG2` 自带一个 gpSP 的 `launch:`）。改写器必须**逐个 token 判断**，只改我们有对应 shim 的那些，不能假设"一份文件一个核心"。

3. **文件是 CRLF 换行**（本机那份 2859 处）。只做行内 token 替换的话字节数正好 +5，干净；**顺手把换行统一了会产生一个 2859 行的 diff**，而这是用户唯一真正感受得到的故障所在的文件。
4. **不要往 Pegasus 文件里写标记行。** Pegasus 的 metadata 格式对 `#` 注释的支持没有确认过，而**写坏了 Pegasus 就启动不了游戏**——这是用户能感知到的最严重故障。改用自识别：

   > **我们的修改是自描述的**：任何形如 `<name>_shim_libretro_android.so` 的路径都是我们写的，还原时去掉 `_shim` 即可。**不依赖任何外部记录**，manifest 丢了也能还原。

   这比 AGENT.md §2 那套标记行更强——那条教训是"改标记串会让旧标记变成孤儿"，而自描述的规则没有这个问题。
5. **只替换那一个 token**，用精确字符串匹配，不要正则重写整行，更不要重排 `launch:` 块。
6. 改写前先确认那个文件里确实有 `-e LIBRETRO`，没有就跳过并记录。
7. **写 SD 卡那一份已实测可写**（2026-08-22）：内置存储和 SD 卡两处的 `create → write → read-back → rename → delete` 全部放行。判据故意用「在同目录建临时文件再删掉」，不是往 `metadata.pegasus.txt` 本身写——**rename 能不能过才是原子替换真正需要的能力**（允许 create 却拒绝 rename 的卷，只会在第一次写坏用户启动文件时才暴露），而为了测「能不能写」去动用户的启动文件，赌的是他们唯一真正感受得到的故障。
8. **改完必须让天马G 重扫**，见 §5.2 第 4 步——魔改版不重扫就永远不生效，而这个失败**在日志里长得跟"改写没生效"一模一样**。

---

## 6. 构建

**开发机是 macOS，工具链已经装好了**（AGENT.md §6 的 `setup_toolchain.sh`）。v1 §4.1 那段 PowerShell 和"本机零基础"的结论是在另一台机器上写的，不适用。

shim 是独立目录 `shim/`，自有 CMakeLists：

- **纯 C**，只链 `log`。若非用 C++ 不可，必须 `-static-libstdc++`——**RA 进程里没有我们的 `libc++_shared.so`**。
- 不需要 JDK、不需要 ncnn、不需要 Gradle。
- ABI 仅 `arm64-v8a`，`ANDROID_PLATFORM=android-26`（`ASharedMemory` 要求 API 26+）。
- NDK 用工程现有的那个版本。

```bash
NDK=$ANDROID_HOME/ndk/<现有版本>
cmake -S shim -B build-shim -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build-shim
$NDK/toolchains/llvm/prebuilt/*/bin/llvm-nm -D build-shim/libvbam_shim_libretro_android.so | grep ' T retro_'
```

最后一行应当数出 **25 个** `retro_*` 符号（外加 `retroai_shim_magic`）。

`shim/` **不要挂进 app 的 CMakeLists**，否则产物会进 APK。独立目录天然没这个问题。

libretro.h 随 `shim/` 目录 vendor 并注明出处。

---

## 7. 门 0 ~ 门 4

按顺序做。**每道门有明确判据；不过就停，不要带着未决问题往下写代码。** 门 0 和门 1 已在 2026-08-20 完成。

### 门 0 — 环境事实 ✅ 已通过（2026-08-20）

**目的**：确认核心目录可达、确认谁指定核心。这是 v1 风险清单里最高的一条。

**结果**：核心在 `/data/data/com.retroarch.aarch64/cores/`，SELinux `execute` 放行；Pegasus 显式传 `-e LIBRETRO`；RA 菜单确认有「加载核心 → 安装和还原核心」。证据见 §3。

**残留两项 ✅ 已答（2026-08-21）**：

- [x] **SD 卡可写**。判据从"写一个字节再改回来"改成"**在同目录建临时文件 → 写 → 读回 → rename → 删**"：那才是 §5.4 原子替换真正需要的能力（允许 create 却拒绝 rename 的卷会在第一次写坏用户启动文件时才暴露），而且**全程不碰 `metadata.pegasus.txt` 本身**——为了测"能不能写"去写用户的启动文件，赌的是他们唯一真正感受得到的故障。内置存储和 SD 卡两份全部 `YES`。
- [x] **`auto_overrides_enable` = 开**。RA 中文菜单里它叫**「自动加载独立配置文件」**，认它的是副标题"启动时加载自定义配置。"（英文 *Load customized configuration at startup*）；底下那条"自动加载重映射文件"是 `auto_remaps_enable`，别认错。**所以现有的视口覆盖配置一直是生效的**，§3 末尾那条附带发现销案。

### 门 1 — IPC 通道 ✅ 已实测通过（2026-08-21，Air Mini / Android 11）

**目的**：确认 shim ↔ APP 用什么通道。

**结果：loopback TCP，双向都通。** abstract / 文件系统 unix socket 仍然否决（E4：域不同 + MLS 分类逐应用不同）；**Binder 方案一并否决**，理由是它的前提拿不到——见下。

**为什么不是 Binder**：§4.4 原方案第一步是 `JNI_GetCreatedJavaVMs()`。该符号**到 API 31 才进 NDK**；安卓 11 上它在 ART APEX 里的 `libnativehelper.so`，而那个库不在 `public.libraries.txt`，应用的 linker namespace 打不开。拿到 VM 之后还要反射 `ActivityThread.currentApplication()`——那一步能过是因为 RA 的 targetSdk ≤ 27 免疫 hidden-API 拦截，于是风险 #6 又多背一条。**整节 §4.4 站在一个从没被验证过的前提上。**

**为什么 TCP 通**：SELinux 对 TCP 检查的是**端口类型上的 `name_connect`，不是对端的域**，也从不查逐应用的 MLS 分类——正是那两样杀死了 unix socket 方案。旁证是 RA 自带的 AI Service（`ai_service_url` 默认就是个 localhost URL）：同一个进程、同一个标签，官方功能。

**怎么测的（判据是"真 RA 进程"，不是玩具 APK）**：玩具 APK 只能复现一个我们猜出来的域。所以让 APP 在 **RA 自己的 netplay 默认端口 55435** 上监听，测试者在 RA 里走 联机 → 连接到联机主机 → `127.0.0.1`。RA 于是做了 shim 要做的那一次 `socket()+connect()`，从生产进程、生产标签发起。

实测：

```
ACCEPTED #1 from 127.0.0.1:48092
  read 24 bytes
  wrote 22 bytes back OK
```

RA 侧报"对方没有提供 RA 可用的版本"——**那正是成功的证据**：它读到了我们回写的字节并把它当版本号解析失败。收发双向都成立，`logcat` 里没有任何 `avc: denied`。

**由此定下的通道**：APP 侧 `ServerSocket` 绑 `127.0.0.1`（**永不绑通配地址**），shim 侧纯 C `socket()/connect()`。**零 JNI、零反射、零 AIDL、零 ASharedMemory**，风险 #5 整条消失。代价两条，都认：多一次内核拷贝（GBA 240×160 RGB565 = 77KB/帧 × 60 = 4.6MB/s，loopback 是 GB/s 级，无所谓）；**APP 要加 `INTERNET` 权限**（RA 已有）——一个离线画质增强 APP 申请网络权限观感不好，只能靠"仅绑回环"和文档解释。

> 端口发现走 E5 的兜底通道：APP 把端口和 token 写进 `/storage/emulated/0/RetroAIScaler/shim/`，shim 加载时读一次。**该目录名无连字符**，跟 `backups/`、`dataset/` 一致。

### 门 2 — 纯透传 shim ✅ 已通过（2026-08-22）

**目的**：只回答三件事——RA 认不认这个 `.so`、真核心 dlopen 得到吗、游戏跑不跑得起来。

**关键纪律：这一步一行 IPC 代码都不要写。** 25 个转发函数 + 按文件名派生 dlopen，就这些。AGENT.md §12.4 那条"先看输出再决定建不建流水线"在 12.2 省过几小时，这里同理。

**做什么**：

1. 写 shim，编译出 `vbam_shim_libretro_android.so`（§4.1、§4.3、§6）。
2. `adb push` 到 `/storage/emulated/0/RetroAI-Scaler/cores/`。
3. 在掌机上：RetroArch → 加载核心 → 安装和还原核心 → 选它。
4. 确认它进了 RA 私有目录（看 RA 的核心列表里多了一项）。
5. `adb pull` SD 卡上的 `Roms/GBA/metadata.pegasus.txt`，把 `-e LIBRETRO` 那个路径的 `vbam_` 改成 `vbam_shim_`，push 回去。**先备份原件。**
6. 重启天马G，点《塞尔达传说 缩小帽》。

**判据**：

```bash
adb logcat -c
# 掌机上从天马G点游戏
adb logcat -d | grep -iE "RetroArch|RetroAI_Shim"
```

- ✅ 日志出现 `[ENV] Libretro path: ".../vbam_shim_libretro_android.so"`；
- ✅ shim 打出"已 dlopen `vbam_libretro_android.so`"；
- ✅ **游戏正常运行，画面、声音、按键、存档全部无异常**——shim 此时是完全透明的，任何差异都是转发写错了。

**2026-08-22 实测：机制全部成立。**

```
[ENV] Libretro path: ".../vbam_shim_libretro_android.so"
RetroAI_Shim: control file readable from inside RetroArch: 'port=55435'
RetroAI_Shim: dlopen real core: .../vbam_libretro_android.so
RetroAI_Shim: real core loaded and all 25 entry points resolved
RetroAI_Shim: core requests pixel format 2 (RGB565)
RetroAI_Shim: load_game: .../塞尔达传说 缩小帽.zip#<压缩包内文件>.gba
```

顺带定下三件事：

- **门 3 的输入格式是 RGB565**，不再是"三种都要支持"的猜测。§4.6 里 5/6bit→8bit 必须位复制（`(c<<3)|(c>>2)`）那条现在是**主路径**，不是边角情况。
- **E5 收口**：RA 进程确实能读 `/storage/emulated/0/RetroAIScaler/shim/` 下的文件，端口/token 的兜底通道成立。
- **zip 内容照常**：RA 传的是 `<zip>#<内部文件>` 形式，说明 VBA-M 的 `need_fullpath=false`、RA 在内存里解包后喂 buffer，那个路径只是信息性的。没有 `.info` 不影响解包判定。

> **调试这道门花掉的时间几乎全在别处**：文件改对了、包名对了、整机重启了，RA 拿到的仍是旧核心路径——根因是天马G 的静态扫描（§5.2 第 4 步）。教训是那条老规矩的又一个实例：**判据要选眼睛能直接看到的东西**。改一个游戏标题看它变不变，比读十遍 `[ENV] Libretro path` 快得多——后者只忠实转述 intent，而 intent 来自内存里那份陈旧的扫描结果，日志再详细也照不见这一层。
>
> 中途我自己还放过一个废检查：`find ... -exec grep -l vbam_libretro_android.so` 搜的是一个**当时已经被我们改掉、因此必然搜不到**的字符串。它不可能失败，也什么都没验证。同 AGENT.md §6 的 `; adb install` 和 §11.7 的 `am start` 漏 `-S`。

**失败对策**：

| 现象 | 大概率原因 |
|---|---|
| RA 报核心加载失败 | 25 个符号缺了（`llvm-nm -D` 复查）、或链了 `libc++_shared` |
| dlopen 真核心失败 | 路径推导错、或 `RTLD_LOCAL/RTLD_NOW` 用错 |
| 游戏黑屏但不崩 | `retro_get_system_info` / `retro_get_system_av_info` 没正确透传 |
| RA 崩溃 | 先查 `retro_set_*` 那六个的转发时序，`env_cb` 是否在 `retro_init` 之前就被调用 |

### 门 3 — 帧到达 APP 且逐像素正确 ✅ 已通过（2026-08-22）

**目的**：确认帧真的过来了，而且**和 RA 画的是同一张图**。

**做什么**：接上 §4.4 的 Binder + 共享内存，APP 侧只做统计和落盘，不渲染。

**判据（三条，缺一不可）**：

1. **fps / 分辨率 / 格式正确**：GBA 应当是 240×160、约 59.7fps、格式与 VBA-M 实际使用的一致。
2. **静态帧逐像素比对**：找一个静止画面，shim 帧落盘 → 和 RA 截图（或现有捕获路径的取景窗采样）对齐后逐像素比。
3. **`retro_run` 没有被拖慢**：对比接 IPC 前后的游戏帧率，差异应当在噪声范围内。有肉眼可见的掉帧就是第 4.4 节那五条底线破了。

**这一步同时要回答风险 #1（色彩分布漂移）**，见 §8。**不要跳过**——它是这条路线上唯一可能让画质变差的东西。

---

**2026-08-22 进展：判据 2 已过，而且参照物换了。**

原计划拿"现有捕获路径的取景窗采样"当参照物。**更好的参照物是 RA 自己的原生分辨率截图**：把 设置 → 视频 → **GPU 截图**关掉，RA 存的就是**着色器之前的核心帧缓冲**，240×160，不经过任何缩放、着色器和 SurfaceFlinger。它比捕获路径干净，而且不需要开投影、不需要探测取景窗。

同一张静止画面，shim 落盘帧 vs RA 截图：

```
identical pixels : 100.0000%      max |diff| : 0
distinct colours : shim 67, RA 67
落在 RGB565 位复制网格上： shim 100.00%   RA 100.00%
```

- **shim 拿到的就是核心原封不动的输出，一位不差。**
- **风险 #2（像素格式）销案**：位复制展开（`(c<<3)|(c>>2)`）与 RA 自己的展开完全一致。用左移的话值就不会落在同一个网格上，这次比对会立刻炸出来——**这正是"5/6bit→8bit 必须位复制"那条纪律第一次有了自动判据，而不是靠肉眼看静态图**。
- **顺带答了 AGENT.md §12.7 的悬案**：shim 帧 100% 落在 RGB565 网格上，而语料的 240 种颜色只有 12 种在网格上。**那个变换是真的，且在捕获链路里**（不是核心的 color correction）。风险 #1 从"怀疑"变成"已确认存在，待量化"——下一步是同一帧走两条路取样，那个差就是要不要重采语料的依据。

**判据 1 和 3 也已通过**（2026-08-22）：

- **判据 1**：`240×160` / `RGB565` / 瞬时 **59.82 fps**——就是 GBA 的 59.7275。
- **判据 3**：开链路与关链路来回切换，**手感没有任何差别**。

> **快进时链路顶到了 263 fps 且没垮**（240×160×2 × 263 ≈ 20MB/s），而手感依旧。这一次意外地把 §4.4 那条设计压满了：APP 跟不上时 TCP 背压堵住的是**发送线程**，环形缓冲丢最旧的帧，`retro_run` 完全不知情。
>
> **这是门 4 的一条约束**：快进时帧会以 4 倍于屏幕刷新率的速度到达，**渲染端不能试图跟上**——按屏幕刷新率取最新帧，其余丢掉。
>
> 顺带记一条统计口径的教训：帧率一开始报的是**整个会话的均值**，而玩家快进跳过片头就让它变成 263——那个数字在任何一个时刻都不曾为真。**速率会合法地变化（快进、暂停、菜单），所以任何记住整个会话的统计量都会说谎。** 现在报的是最近 10 个一秒窗口的均值：够长，能抹平"差一帧就差 1 fps"的抖动（就是它让单个窗口读出 60.76）；够短，玩家离开某个模式后它会忘掉。

> **踩坑记一条**：APP 侧给读设了 5 秒超时，结果第一次跑就 `Read timed out` 断链。**帧流停下来是正常状态不是故障**——RA 菜单一弹核心就暂停，`retro_run` 停，帧就没了，而 §4.7 正要靠这个静默让覆盖层退让。超时把正常状态判成了故障。现在握手阶段仍有超时（防止连上不说话的对端占住 accept），**进入帧循环后无限阻塞**：RA 退出时 socket 自然关闭，服务停止时由 `onDestroy` 从下面把它关掉。

### 门 4 — 全屏增强输出，取景窗消失 ✅ 已通过（2026-08-22）

**目的**：最终判据，"看看效果"。

**做什么**：

1. 接 §4.5 的几何短路：源矩形 = 整帧、`protectSource=false`、跳过探测与配置改写；
2. 覆盖层全屏不透明；
3. 接 §4.7 的菜单可见性规则；
4. 还原 RA 的视口配置（`RetroArchBackupManager` 的还原路径），让 RA 回到正常渲染。

**判据**：

- ✅ 屏幕上**只有一个画面**，全屏，无角落取景窗；
- ✅ 打开 RA 菜单时覆盖层退让，能正常存档退出；
- ✅ 回桌面、切应用、从多任务返回都不炸（AGENT.md §10.3 那条 surface 断连的老问题要回归测）；
- ✅ 与现状对比，画质**不低于**现在。

**2026-08-22 实测：四条全过。** 另外两条计划外的结果：

- **AGENT.md §10.7 那个"从多任务点模拟器自己的卡片返回，增强画面消失"的老问题，在 shim 模式下没有复现。** 不宣称已修复——根因（SurfaceView 缓冲队列被那次转场弄丢）从没被确证过，只能说这条帧源不经过 MediaProjection，于是不吃那一跳。
- **画质与捕获路径"没有区别"**，这是个好结果而不是坏结果：它说明原来那套取景窗探测 + 整数倍吸附确实做对了（§10.1 的纪律有效）。shim 的收益不在锐度，在于取景窗消失、RA 配置一个键都不用写、探测/吸附/模式判定整条链路可以退役。**真正可能看出差别的是非整除比例的机种**（FC/SFC），§10.1 记的那次发糊正是 1080p 上 `sy=4.5` 把 160 量成了 162——这类误差现在在原理上不存在。

> **实施中踩到的坑，值得单独记：** 打开 RA 菜单时画面**冻住**而不是让位。根因是看门狗在**主线程**上直接调 `nativeClearOverlay()`，而 EGL context 在帧线程上——`ensureEglContextCurrent()` 静默失败、擦除变成空操作、最后一帧永久留在屏上。
>
> **AGENT.md §10.3b 一字不差地记着这件事，而这段代码没照做。** 它一直躲过检查，是因为捕获模式下要 10 秒不出帧才会走到，几乎碰不到；shim 把阈值压到 250ms，潜伏 bug 就变成必现 bug。**这是这轮最有价值的副产品**——同一段代码离对捕获用户做同样的事，只差一次足够长的暂停。

**另一处上机前就补掉的洞**：RA 不再被缩到角落，它**全屏画在我们下面**，而输出只覆盖最大整数倍那一块。**每个留透明的像素都会透出 RA 的未增强画面**——双画面以边框形式回来。现在 shim 模式下输出矩形之外是**不透明黑**；透明仍是其它一切场合的默认值，那是整个服务的安全底线。

**门 4 之后**：MediaProjection 路径**保留但降级为回退**——HW 渲染核心（PS1/N64）仍然需要它。两条帧源并存，由核心类型决定走哪条。

---

## 8. 风险清单（按严重度重排）

| # | 风险 | 说明与规避 |
|---|---|---|
| 1 | ~~高~~ → **已结案，无需处理**（2026-08-22） **色彩分布漂移** | shim 拿到的是核心自己的帧缓冲，**没经过 SurfaceFlinger 的色彩管理**。AGENT.md §12.7 记着语料颜色不落在 RGB555 网格上、怀疑就是 SurfaceFlinger，当时的判断是"训练和推理走同一条路径所以分布一致"——**shim 打破了这个前提**。切过去后模型可能变差，且是那种"说不出哪里不对"的变差。**对策**：门 3 必须同一帧走两条路（shim 帧 + 捕获帧）逐像素比。这顺带就是那个悬案的答案——终于有干净参照物了。**实测结论：不需要重采语料，不需要重训。** 对齐后最大差 **3/255**、平均 **0.144/255（0.056%）**、87% 逐通道样本逐位相等——是 AGENT.md §12.6 自定可见阈值（1.3/255）的 1/9。而且那个"颜色不在网格上"的悬案大半是**尺子拿错**：核心输出是 RGB565，当初按 GBA 硬件的 RGB555 去量。详见 AGENT.md §12.7 |
| 2 | **高** 像素格式多样性 | 三种格式 + `pitch ≠ width×bpp`，现有管线只吃过 RGBA8888。5/6bit→8bit 必须位复制不能左移（§4.6）。逐格式静态帧验证 |
| 3 | **高** HW 渲染核心不可拦截 | `SET_HW_RENDER` 核心无 CPU 像素可拿；shim 拒绝，UI 明确告知回落 MediaProjection（双帧源并存）。**并且必须处理 `(void*)-1` 哨兵**，否则 SIGSEGV |
| 4 | **中** shim 崩溃即 RA 崩溃 | §4.4 那五条底线。纯 C、无 STL、无异常、`retro_run` 内无阻塞 syscall |
| 5 | **中** Binder 路线的复杂度 | 从 native 反射拿 Context 是成熟做法但代码丑。门 3 出意外时回看门 1 |
| 6 | **中 · v1 未列** 依赖 RA 保持低 targetSdk | E5：`untrusted_app_27` 才被允许从私有目录 execute。RA 升 targetSdk ≥ 28 则整条路线失效。我们控制不了，但 RA 自己的核心下载器也依赖同一条 |
| 7 | **中** 符号/ABI | 25 个符号缺一即拒载（`llvm-nm -D` 自查）；真核心版本不匹配时 dlopen 失败需可读日志 |
| 8 | **中** Pegasus 配置改写 | 写坏了用户就启动不了游戏。§5.4 的纪律一条都不能省，尤其"只替换一个 token、不重排 `launch:` 块" |
| 9 | **中** Android 16 未知数 | Pocket FIT Elite 上 Binder + ASharedMemory 的行为需实测。但那台是 API 34+，本来就有单应用捕获，shim 在那边是"更好"而非"必需" |
| 10 | **低** RA 菜单被遮挡 | §4.7 的帧流停顿信号 |
| 11 | **低** Helio GPU 怪癖 | AHardwareBuffer 只用最保守 usage 组合，且池化不每帧新建 |
| 12 | **低** APK 打包 | `shim/` 独立目录，产物不进 APK |

---

## 9. 边界与法务

- **一期只支持软件渲染核心**（GBA/FC/SFC 类，本项目主力场景）。HW 核心自动回退现有 MediaProjection 路径，双帧源并存。
- **法务**：shim 走运行时 `dlopen`、不复制 RA 代码，GPLv3（RA）与 MIT（本项目）隔离；`libretro.h` 随 `shim/` 目录 vendor 并注明出处。**拒绝任何重打包 RA 的方案进主线。**
- **纪律**：实施期间不破坏 `RetroArchBackupManager` 的配置备份/还原与标记行机制；结论无论成败按 AGENT.md 惯例写回文档。
- 在本地分支（如 `feature/libretro-shim`）实施。

---

## 10. 不要做的事（已否决，记下来免得重走）

**① 改名接管（shim 复制自己顶替真核心文件名、真核心备份成 `_orig_`）。**

这是在"以为 Pegasus 的传参会覆盖一切"的假设下想出来的：让 shim 用 RA 的 UID 在 cores 目录里把自己复制成 `vbam_libretro_android.so`，真核心备份成 `vbam_orig_libretro_android.so`，这样无论谁指定核心都命中。

**否决理由**：E2 确认核心是 Pegasus 用一行纯文本指定的，而那行文本在我们可写的位置——问题在更外层就解决了。而改名接管要在 RA 私有目录里覆盖文件，**一旦防重入守卫写错就会把真核心永久弄丢**（第二次运行时把已是 shim 的文件备份成 `_orig_`）。白担这个风险没有意义。

> 如果将来遇到某个前端**不传核心路径**、或核心打包在 APK 的 `lib/` 里只读，这条路会重新变得相关。那时防重入守卫的写法是：导出 `retroai_shim_magic`，复制前先 `dlopen` + `dlsym` 探一下，是 shim 就跳过；且 `_orig_` 已存在就绝不覆盖。

**② `shim.cfg` 配置文件。** 见 §4.3——按文件名派生更简单、支持多核心、少一个会写错的文件。

**③ 动态生成 `.info`。** 见 §5.3——Pegasus 传参启动时 RA 根本不读它。

**④ abstract unix socket。** 见 E4 / §4.4。

**⑤ 每帧 `AHardwareBuffer_create`。** 见 §4.5——池化。

**⑥ 允许 `GET_CURRENT_SOFTWARE_FRAMEBUFFER`。** 见 §4.2——WC 内存读回慢一个量级。

---

## 11. 如果这条路整个走不通

回退到 AGENT.md §11.8 的**取景窗压暗 + 数值反解**：把合成着色器里那行 `discard` 换成一层常数黑色（不构成反馈回路），采样侧除以 `1 - α` 反解。α=0.5 掉 1 bit 精度，α=0.75 掉 2 bit。会杀死它的是合成时的 dithering。

那是个纯软件、改动极小的缓解方案，**治标不治本**（取景窗仍在，只是变暗），但它今天就能做。

零成本的替代：捕获发生在 SurfaceFlinger 里，**不在玻璃上**——往取景窗那个角贴一小片哑光贴纸，捕获完全不受影响。
