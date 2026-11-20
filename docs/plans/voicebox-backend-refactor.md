# VoiceBox 通用模型后端重构执行清单

状态：实施中。各阶段的本轮验证见第 8 节。

基线：`5b3942f369277f649ac5c129890ad6352bc9c2f6`，`origin/main`，仓库 `Renakoni/Voicebox`。

本文件用于执行重构、记录依赖与验收证据。规划阶段以本清单为交付物，实施阶段按第 5 节的分支与 PR 顺序推进。

## 1. 任务目标与最终目的

将建立在 GPT-SoVITS 上的应用重构为通用的安卓语音工作台 VoiceBox。宿主提供音源管理、任务调度、录音、播放、导出、对外 API 和模型桥接基础设施；具体模型的文本前端、权重解释、推理流程和加速图由各自后端负责。

最终应具备以下结构与行为：

- GPT-SoVITS 与 Ming 是同级后端，通过同一套宿主契约和桥接装载流程工作。
- GPT-SoVITS 内部统一管理 V2、V2 Pro、V2 Pro Plus、V5 Turbo，不为四个版本分别建立宿主特例或四套桥接协议。
- GPT 桥接随应用分发并自动安装。全新安装后，设置中的“模型”页面显示 `GPT-SoVITS · 已安装`；此行为不依赖联网或权重存在。
- Ming 桥接按需下载；安装后与 GPT 使用相同的音源和生成入口。
- “更多模型”改为“模型”。该页面管理后端安装，不展示“模型已就绪”、权重就绪度或额外的“使用此模型”步骤。
- 后端代码包和官方权重分开。Release 中的桥接包不包含官方权重；权重继续属于音源侧，通过官方来源、镜像或本地导入获得。
- 宿主不再要求任何一个模型必须具备 GPT/SoVITS 文件、HuBERT、RoBERTa、GPT 采样参数或 GPT 的语言限制。
- 新后端接入时，新增自身实现、构建产物及目录登记即可，不需要改写宿主任务调度器、音频输出流程或不断添加 `if (isMing)` 一类分支。

成功标准是依赖关系与产物边界真正改变。仅移动目录、包装原有 GPT 服务、在页面中新增一条 GPT 记录，不算完成。

## 2. 范围与约束

- 本轮重构不做旧版升级、旧数据迁移、旧包名兼容、旧接口别名或历史缓存兼容；新版本发布前另立任务处理。
- 工程命名选定 VoiceBox；自有代码的基础 namespace / applicationId 规划为 `dev.renakoni.voicebox`。不同时引入 VoiceTT 等其他名称。
- 第三方源代码、许可证、原始模型名称、远端资源 URL、上游协议要求的字段不做机械品牌替换。例如定制 ORT 的 `session.genie_fp16_weight_storage` 必须按实际运行库支持处理。
- 保持已有推理算法、精度、默认行为和已实现功能；不借本轮重构重新训练、量化、改写模型或开发新的模型能力。
- Ming 原始官方 checkpoint 的下载/转换、额外任务类型、动态 batch 8/QNN 路线不默认为本轮新增功能。需要继续接入的能力单独登记，不得把“结构重构”报告为“全部 Ming 功能已实现”。
- 不增加模型市场、任意第三方插件加载、卸载/自动更新界面、通用工作流编辑器或尚无实际需求的配置 DSL。
- 不更改参考项目 `../ming-android` 和 `E:/H-novel/mian-LightNovelReader`。不重写已有可复用的下载、校验、请求取消和音频处理实现。
- 不触碰预存的未跟踪文件 `all_strings.tsv`，不批量暂存工作区，不提交权重、设备数据、APK、构建目录或签名材料。
- 实施全部走分支和 PR；不向 `main` 直接推送，不自动合并 PR，不把创建 PR 与发布 Release 混为一个操作。

## 3. 已核实的现状

### 3.1 工程及依赖

- `Android/settings.gradle.kts` 当前只有 `:app`。
- 当前生产 Kotlin 共 114 个文件，约 15,012 行；`inference/` 67 个，`checkpoint/` 5 个，`moremodels/` 3 个。原生代码、资源、测试和工具另计。
- `Android/app/build.gradle.kts` 同时承担应用配置、GPT 语言资源准备、共享模型资源打包、ORT 依赖、原生推理库构建和签名。
- 当前 namespace 与 applicationId 均为 `dev.rinstel.genie_tts`；JNI 导出符号、Manifest、ProGuard、测试和文档也引用这个名称。
- `GenieBackendService.kt` 直接创建 `OrtCpuBackend`、`OrtRuntimeFeatureExtractor`、`OrtSessionRuntime`，并直接做 ORT/WebGPU 能力探测；Ming 通过特例分支进入另一条路径。
- `BackendRuntime`、`GenerationRequest`、`GenerationContext`、`GeneratedAudioFile`、`BackendRuntimeCallbacks`、`GenerationRequestGate` 已存在，是契约收敛的起点。

### 3.2 名为通用、实际仍依赖 GPT 的部分

| 位置 | 实际职责或耦合 | 拆分要求 |
| --- | --- | --- |
| `inference/CharacterModel.kt` | 含 `gptFilename`、`sovitsFilename` 和 GPT 文件布局 | 公共描述与 GPT 私有元数据分离 |
| `inference/RequiredModelFiles.kt` | 固定列举 GPT 版本、图名、精度派生文件 | 归 GPT 后端；Ming 不再构造空的 GPT 文件清单 |
| `inference/GenerationRequest.kt` | `maxDecoderSteps`、`SynthesisOptions` 带有 GPT 语义 | 保留公共请求；专有选项由后端验证 |
| `inference/GenerationPipeline.kt` | 使用 T2S、预处理张量、GPT 进度和必填参考文本 | GPT 推理流水线归 GPT，不整体当成宿主流水线 |
| `inference/ModelAssetRepository.kt` | 扫描 GPT 版本目录并追加 Ming 特例 | 分成宿主汇总及各后端资源发现实现 |
| `inference/RuntimeAssetRepository.kt` | 混合 HuBERT/RoBERTa/说话人模型、输出目录和缓存清理 | GPT 资源归 GPT，公共输出与缓存边界归宿主 |
| `OfficialModels.kt` | 通用下载描述与 GPT 官方目录、转换安装混在一起 | 下载类型复用；GPT 目录与转换移入 GPT |
| `ResourcePackageImporter.kt` | 实际仅理解 GPT 的 `RuntimeAssets` | GPT 资源导入归后端；复用底层文件处理 |
| `checkpoint/` | Torch/GPT/SoVITS 权重解释、导入和编辑 | 全部归 GPT 后端；UI 通过能力/操作请求调用 |
| `studio/StudioViewModel.kt` | 直接引用两类模型、GPT 导入器、GPT 资源与参数 | 改用注册后端、音源引用与能力信息 |
| `studio/StudioStore.kt` | 音源以模型目录绑定，全局保存 GPT 参数 | 明确后端与模型引用，按后端保存选项 |
| `inference/LanguageNormalizer.kt` | 语言标签处理混合 GPT 文本脚本限制 | 通用语言标识与模型校验分离 |
| `api/`、`BackendApiContract.kt` | 请求参数直接引用 GPT `SynthesisOptions` | 沿用现有 API 实现入口，移除模型专有校验依赖 |

### 3.3 加速与原生代码

- `ExecutionBackend` 当前只有 CPU 与 CPU + GPU；QNN 是现有 GPT 执行路径内部的混合加速。
- `DeviceAcceleration` 混合 Android 设备识别、运行库文件检查、QNN 实际探测与状态缓存；并受 GPT WebGPU 可用性约束。
- `V2HtpVocoder` 和 `V5DiTRuntime` 各自拥有模型图、形状分桶、执行顺序和缓存重载语义，不能合成宿主里的通用模型算法。
- `QnnSession` 的缓存涉及设备、图、权重、运行库和形状；当前准备阶段允许编译，实际声学生成阶段只加载准备好的 context。搬迁不得丢失该约束。
- `native_decoder_loop.cpp` 与 `qnn_session.cpp` 目前编进同一个原生目标；OpenJTalk、重采样与 QQ SILK 也由应用 CMake 管理。
- GPT 的 JNI 回调和 ORT Java 字段有 R8 keep 规则，纯 Kotlin 编译通过不足以证明重命名正确。

### 3.4 Ming 已有基础与边界

- `moremodels/MingPackage.kt` 已有固定目录元数据、SHA-256 校验、暂存安装、路径/大小校验、完成标记及只读 DEX。
- `MingWorkerService` 使用私有进程、`DexClassLoader` 和 JNI；模型资源独立存放，临时工作目录用链接拼出上游布局。
- `MingBackendRuntime` 已接入现有请求与音频格式，取消会释放 worker，并等待其退出后清理；符号链接清理不跟随到模型目录。
- 当前 Ming 每次请求结束释放 worker。GPT 有预热和会话复用；公共桥接不能强迫 GPT 每句话重启，造成性能回退。
- 现有 Ming 包为约 16.2 MiB 的代码/运行库 ZIP，只有 task JAR 和 5 个 `.so`；约 5.25 GB 的 Android 导出模型资源独立于该包。
- Ming 当前资源发现要求 Android 导出布局；不能宣称已支持直接加载原始官方 checkpoint。
- 本轮查看的阅读器工作树应用源码使用 `indi.renakoni.nextvol`；未定位到先前描述的 Genie 下载桥接实现。后续若需要追溯，可查该仓库历史；本计划不把未核实的历史实现作为前提，当前 Ming 已有经实机验证的下载装载基础。

### 3.5 验证基础

- 上一阶段已记录：Debug 构建、Lint 无错误、33 项针对性单测、4 项 Ming/界面实机测试通过；本轮没有重跑。
- 可复用测试包括请求门闩、生成取消、HTTP API、权重导入、官方模型下载、语言前端、音频读写与重采样、GPT V2/V5 加速、Ming 安装与运行、创建页面和播放。
- 先前实机证据来自 Xiaomi 14 / Android API 34；后续执行前重新确认设备在线情况。
- `RequiredModelFiles` 和资源发现包含 V2；当前 checkpoint 导入实现针对 V2 Pro、V2 Pro Plus 和 V5 Turbo，官方目录也没有覆盖全部四类。V2 的已有模型运行与新增原始 checkpoint 导入必须分开验收，不能仅凭目录存在宣称导入支持完整。
- 当前仓库未发现已跟踪的 `.github/workflows`。不能假设开 PR 后会自动完成 Android 构建或设备验证。

## 4. 目标结构与职责

采用少量明确的构建边界，不按每个类创建模块。以下是最终目录；实施期间允许分 PR 渐进迁移，但过渡依赖必须注明在哪个 PR 移除。

```text
Android/
  app/                          # 页面、前台服务、系统集成、HTTP/Binder 入口
  bridge-api/                   # 从现有契约提取的最小共享类型与后端入口
  core/                         # 装载/安装、调度、音频、存储、设备事实
  model/
    gptsovits/                  # GPT 全系列实现、原生代码、专有资源与构建工具
    ming/                       # Ming 适配、原生桥接及代码包构建工具
docs/
  plans/voicebox-backend-refactor.md
```

源码包分别使用 `dev.renakoni.voicebox`、`.core`、`.bridge`、`.model.gptsovits`、`.model.ming`。目录统一小写 `model/gptsovits` 与 `model/ming`。

- `app -> core -> bridge-api`；两种后端只依赖共享契约和实际需要的公共工具，不反向依赖 `app`。
- 默认分发的应用构建依赖 GPT 桥接产物生成任务；这不等于把 GPT 实现放进宿主编译类路径。
- GPT、Ming 后端实现的 DEX 与专有 native 库通过包装载，不出现在宿主主 DEX/JNI 目录中；宿主只持有共享契约和装载器。
- `core` 不引用两种模型的实现类，不含 `v2`/`v5` 图选择或 Ming 任务转换。
- 通用音频解码、WAV、重采样保留一套。GPT 的 G2P、音素表、RoBERTa/HuBERT 特征和语义 token 处理属于 GPT。
- `backends/ming` 的自有适配与打包工具收敛到 `Android/model/ming`，不保留两套来源；Ming 上游算法继续复用参考项目，并记录可复现的源版本。
- GPT 图模板生成工具、相关补丁与说明按实际消费者归档到 GPT；确实共用的运行库构建工具保留在公共工具区。第三方代码随所属组件迁移并保留原有出处。

### 4.1 公共契约

- 从现有 `BackendRuntime`、请求、回调、结果与取消机制提取；不另外设计第二套功能重叠的合成 API。
- 明确区分后端 ID（`gptsovits`/`ming`）、模型/版本 ID、用户音源 ID。音源存放后端和模型引用，不再靠目录字符串猜模型家族。
- 公共请求包含文本、语言、参考素材、请求标识及执行偏好；GPT decoder 限制、采样范围和专有参数归 GPT。
- 只为实际已有的差异提供能力描述：语言、参考素材要求/数量、可用执行方式、现有可调参数、现有导入方式。禁止先做无限扩展的参数系统。
- 宿主使用后端声明的能力决定控件和请求；不能继续用 `mingSelected` 隐藏所有 GPT 控件，也不能让 Ming 携带一份无意义的 GPT 默认参数。
- 返回统一音频文件和必要元数据、阶段事件及可展示错误；宿主不得要求 GPT 张量形状或 T2S 阶段。
- IPC 只传有界的基础数据、文件引用、请求 ID 与事件；不传 ORT 对象、大权重、整段张量、Android 资源整数 ID 或模型私有 Parcelable 类。
- 共享契约只由宿主父 ClassLoader 提供，桥接包不得重复打包一份，避免跨 ClassLoader 类型不等。
- 模型错误与文本需解除对宿主 `R`/`BuildConfig` 的编译依赖。优先沿用 UI 边界解析方式，使用稳定消息标识及参数；打包前实证验证，不复制整套宿主资源表到桥接中。
- 包契约版本/入口检查用于装载当前代码包；它不是旧应用升级兼容工程。

### 4.2 包、权重与安装

- 两种后端共用清单解析、下载、校验、暂存提交及装载逻辑；模型专有文件白名单来自各自包清单。
- GPT 预装包和 Ming 下载包区别仅在来源（APK asset / 网络），进入相同的验证及安装流程。
- GPT 预装自动完成、无需下载按钮或安装引导；安装完成后才能记录已安装，不把“APK 中有 ZIP”当作实际安装成功。
- 安装失败可重试，安装取消或进程退出不留下可加载的半包；不替换运行中的目录。
- 安装目录、权重目录、用户音源、推理缓存、输出目录职责分开；清缓存不能删掉用户音源、参考音频或权重。
- 代码包仅包含实现、依赖运行库和经审计的非权重资源。不能仅按扩展名判断：ONNX 图可能内嵌学习权重。
- GPT checkpoint 图模板、语言字典、探测图逐项分类；含学习权重的文件必须进入音源/模型资源流。Ming 模型图、tokenizer、reference encoder 与 voice 数据继续不进入桥接包。
- 新的 GPT 预装包由当前源码构建生成，不手工复制旧 ZIP，也不使应用构建依赖未发布的 Release URL。
- 桥接 Release 文件、默认预装内容和宿主目录元数据必须来自同一次产物生成；记录文件大小、SHA-256、ABI、入口、契约版本和源版本。

### 4.3 Worker 与加速

- 复用现有前台服务和请求门闩，保留单个活动生成请求的所有权。
- 以私有 worker 装载当前后端，ORT/WebGPU/QNN 对象在所属运行环境内创建和释放；宿主 UI 进程不初始化 GPT ORT。
- 后端切换时完整释放旧实例和 native 环境，再装载新后端。不同 ORT 版本不得共享 native handle 或错误复用同名 `.so`。
- GPT 保留同一后端内可控的预热/缓存；Ming 可保留当前请求结束释放的策略。统一生命周期契约，不强求相同内部算法。
- 取消、Binder 断开、worker 崩溃、后台回收后允许下一次请求正常运行；旧请求回调不能覆盖新请求。
- 通用层负责设备事实、运行库位置与生命周期协作；后端负责自己的探测、可执行图、精度、形状分桶、回退与缓存键。
- 能力按后端/模型实际探测，不把 GPT WebGPU 成功当作 Ming GPU 成功；不把设备有 NPU 当作所有模型都能使用 NPU。
- GPT V2/V5 既有图配置迁入 GPT，Ming 配置归 Ming。仅迁移已实现路线，不用抽象接口掩盖尚未接入的加速能力。

## 5. PR 拆分与依赖

基准链：`main -> PR1 -> PR2 -> PR3 -> PR4 -> PR5 -> PR6 -> PR7`。

按需要使用 stacked PR：每个子分支以其直接前置分支为 base，PR 正文写依赖、增量范围及该 head 的验证结果。父 PR 合并后再调整后续 base 并复验受影响构建；不在规划阶段承诺全部 PR 同时创建。

每个 PR 必须在提交前通过自身验收。允许 review 中的、已验证父分支上继续构建下一 PR；不允许把已知编译失败或基础生成失败推给后续 PR 修复。仅本地通过的验证要明确记录，不伪称 CI 已验证。

### P0：实施前建立基线（首个实施 PR 的前置工作）

- [x] 确认当前 main/head、工作区改动、构建工具、可用设备和参考源码版本；保留非任务文件。
- [x] 盘点 V2、V2 Pro、V2 Pro Plus、V5 Turbo、Ming 的可用测试模型、参考音频与语言样本。缺少素材标记为缺口，不用其他版本的测试代替。
- [x] 运行现有相关单测与设备验证，建立当前状态，区分历史证据与本次重新验证。
- [x] 保存同设备、同输入、同权重、同执行参数的冷启动/预热生成耗时、RTF、内存峰值、输出长度及听感证据到忽略的本地产物目录。
- [ ] 记录 GPT 预热、缓存命中、QNN 首次编译与重载、Ming 合成/克隆/取消行为。
- [ ] 核实 V2 实际可用路径并建立运行样本；不得把缺少原始 checkpoint 导入能力算作重构新引入的回退。

### PR1：统一自有工程命名

建议分支：`refactor/voicebox-01-namespace`。

- [x] 自有 Kotlin/Java 源码、测试、namespace、applicationId 改为 VoiceBox；同步目录和引用。
- [x] `GenieBackendService` 等自有宿主名称改为 VoiceBox 语义；不改第三方类名和上游资源名称。
- [x] 同步 JNI 导出、C++ 自有 namespace、Manifest、组件名、Intent action、FileProvider、R8 规则、构建参数与实际使用的工具命令。
- [x] 不修改算法、线程配置、图、推理默认参数；不在这一 PR 同时完成全部目录/依赖拆分。
- [ ] 验收（Ming 的 ARM64 装载仍缺设备；其余结果见第 8 节）：Debug/Release 编译与 Lint；新 applicationId 安装启动；原生重采样、OpenJTalk、GPT decoder、Ming 动态加载及 SILK 入口覆盖；至少一次真实生成和播放。
- [x] 机械替换后的剩余旧名称逐项归类；保留确属上游 API、许可证或资源的引用，不能以“全文零命中”破坏依赖。

### PR2：提取契约并确立模型代码归属

建议分支：`refactor/voicebox-02-model-boundaries`，依赖 PR1。

- [x] 建立 `bridge-api`、`core` 和 `model/gptsovits`、`model/ming` 目录/构建边界；每次搬迁同步相关测试。
- [x] 从现有接口提取公共请求、事件、结果、取消、后端入口和实际所需能力；删除重复定义。
- [x] GPT 推理、前端、Torch 导入、官方资源和版本配置移入 GPT；Ming 专有资源与适配移入 Ming。
- [x] 公共下载、音频、请求调度与输出存储归 core；拆开混合职责的 Repository，不复制一份通用代码到每个模型目录。
- [x] 引入显式后端/模型引用与注册汇总，替代宿主对 GPT 目录和 Ming 常量的识别。
- [x] 服务的模型创建、资源发现和校验委托后端。保留现有 UI 功能，避免把未完成 UI 改造伪装成能力消失。
- [x] 如为使此 PR 独立可运行暂时直接链接 GPT provider，明确在 PR4 移除；禁止把该过渡状态当作桥接完成。
- [ ] 验收（Ming 实际生成受 ABI 限制，见第 8 节）：模块依赖无环；公共契约无 GPT/Ming 实现依赖；单测、API、导入及两种模型真实生成；GPT 参数校验、语言处理仍符合基线。

### PR3：复用 Ming 验证公共包安装与 worker 装载

建议分支：`refactor/voicebox-03-bridge-loader`，依赖 PR2。

- [x] 将 Ming 安装器中的公共能力提取为统一包安装服务，保留现有校验、取消、暂存和只读 DEX 行为。
- [x] 定义最小可用包清单与入口；网络包和 APK 内置包通过同一安装路径，复用下载器。
- [x] 提取 worker 绑定、请求关联、进度、结果、失败、取消和释放逻辑；模型 task 转换仍留在对应后端。
- [x] Ming 迁入公共装载路径，清除 `moremodels` 命名及宿主 Ming 专用生成分支。
- [x] 验证共享契约 ClassLoader、native 库解析、消息本地化与 Release R8；不能只在不混淆 Debug 中验证。
- [x] 验收：真实 Ming 合成、参考克隆、播放；取消后继续生成；安装中断/错误哈希/非法路径/不匹配 ABI 或契约拒绝加载；资源链接清理不删权重；当前 Ming 包不包含权重。

### PR4：GPT 全系列成为独立桥接并默认预装

建议分支：`refactor/voicebox-04-gptsovits-package`，依赖 PR3。

- [ ] GPT bridge 独立生成 DEX/native/必要非权重资源；模板和模型权重逐项分界。
- [ ] GPT 原生编译、语言前端准备、ORT 和 checkpoint 模板依赖从应用构建移到对应组件；公共音频/SILK 仍归其真实消费者。
- [ ] 默认应用携带当前构建生成的 GPT 包，首次运行自动验证安装。完全离线且没有权重时，仍能正确完成桥接预装。
- [ ] GPT 通过公共 loader/worker 执行生成、资源准备和模型专有导入；移除过渡的静态实现链接与宿主 ORT 初始化。
- [ ] 保留 GPT 的取消、预热、缓存复用和内存释放语义；切换 GPT/Ming 必须实际释放不再使用的运行环境。
- [ ] APK 宿主主 DEX 不含 GPT/Ming 推理实现；GPT 代码只在预装桥接产物中。宿主可在不提供模型权重的构建环境完成代码产物构建。
- [ ] 验收：四类 GPT 现有模型运行、已支持的官方下载/本地权重导入、单/多参考能力；GPT → Ming → GPT 轮换；冷/热性能与基线比较；预装失败重试、安装时序、缺权重时的音源侧提示。
- [ ] 独立桥接包与预装包校验一致；只生成发布候选物，不在此 PR 自动上传或发布。

### PR5：收敛设备能力与模型加速配置

建议分支：`refactor/voicebox-05-acceleration`，依赖 PR4。

- [ ] 拆分 `DeviceAcceleration`：通用设备信息与后端实际探测分开，运行库句柄留在正确进程。
- [ ] V2/V5 图、分桶、精度、执行顺序、QNN context 准备与回退完整保留在 GPT 后端。
- [ ] 能力查询按后端/模型返回，不依赖 GPT 的全局 CPU/GPU/NPU 探测结果控制 Ming。
- [ ] 缓存加入模型/后端边界，保留图、权重、运行库、设备及形状变更后的失效语义，防止跨后端命中。
- [ ] 配置仅覆盖已经存在的加速路线；不添加“通用 NPU 图编辑器”，不默认为 Ming 开启尚未接入的 QNN 路线。
- [ ] 验收：CPU、WebGPU、可用设备上的 GPT QNN；不支持/缺运行库/探测失败时既有回退；编译缓存重载和失效；实际生成期间不意外编译；取消与内存回收。

### PR6：模型页面与音源/生成界面统一使用后端能力

建议分支：`refactor/voicebox-06-models-ui`，依赖 PR5。

- [ ] “更多模型”改为“模型”，保持位于高级设置上方及现有二级导航。
- [ ] 列表从统一目录读取：GPT-SoVITS 一项、Ming 一项；GPT 四个变体在音源/模型选择中区分，不显示成四个后端安装项。
- [ ] 全新安装的 GPT 显示已安装；Ming 显示下载操作及实际下载进度/结果。页面不推断权重是否就绪。
- [ ] 移除 `mingSelected` 等模型特判，按能力显示语言、参考音频数量、导入和合成参数。
- [ ] 保留现有音源、录音、播放、导出和外部调用流程；GPT 的成对 checkpoint 表单只作为 GPT 导入能力出现。
- [ ] 校验与持久化按后端隔离，切换模型不沿用不适用的参数。模型专有校验不得在 UI 与后端各复制一套规则。
- [ ] 文案沿用已有 i18n 机制，不新增后端安装/权重就绪双状态或额外“启用模型”操作。
- [ ] 验收：全新安装离线显示 GPT；Ming 下载/失败/重试/取消；返回导航；两种模型音源选择、克隆、生成及播放；语言切换与缺资源提示所在位置。

### PR7：最终依赖、产物与端到端验收

建议分支：`refactor/voicebox-07-integration`，依赖 PR6。

- [ ] 清除重构引入的过渡入口、重复实现和无主代码；不顺手清理无关历史功能。
- [ ] 核对源码、原生代码、资源、工具、测试、构建配置与说明的最终归属，避免仓库形成新旧两套结构。
- [ ] 为已确定的模块边界加入小型自动检查：core 不依赖模型实现；宿主 DEX 不夹带桥接实现；桥接不重复包含契约或官方权重。
- [ ] 在干净输出目录重建 Debug 与混淆 Release、预装 GPT 包和 Ming 包，验证代码清单、ABI、版本、SHA、依赖和动态入口。
- [ ] 执行下节矩阵；全部声称支持的能力都附对应证据，任何缺设备/缺模型项明确保留为未验证。
- [ ] 更新开发构建、目录导航、桥接打包和发布候选物操作文档；删除已失效的本项目命令，不改上游项目用法。
- [ ] 每个 PR 描述与最终实现一致，记录实际 head、基线、构建参数、测试结果和必要限制。
- [ ] 只有验收条件满足后才能把本清单状态改为“重构完成”；代码 PR 完成不代表已合并或已发布。

## 6. 验证矩阵与执行纪律

| 范围 | 最低验证 | 主要阶段 |
| --- | --- | --- |
| 命名及 native linkage | Debug + 混淆 Release；安装；JNI/反射入口 | PR1、PR3、PR4、PR7 |
| 模块契约 | 无循环依赖、无模型类型泄漏、无重复契约 DEX、参数及错误跨进程 | PR2、PR3、PR7 |
| 安装和预装 | 离线自动 GPT 安装；Ming 校验安装；失败不激活半包；重复启动不重复解包 | PR3、PR4、PR6 |
| GPT 运行 | V2、V2 Pro、V2 Pro Plus、V5 Turbo 各自实际样本 | P0、PR2、PR4、PR7 |
| GPT 导入 | 当前已支持的官方来源、镜像选择、本地 checkpoint、模板及参考音频 | PR2、PR4、PR7 |
| Ming 运行 | 现有导出资源；CPU/WebGPU；默认参考和实际编码克隆；44.1 kHz 输出 | PR3、PR4、PR7 |
| 调度与生命周期 | 取消、取消后下一任务、worker 死亡、低内存、后台行为、过期回调 | PR2—PR5、PR7 |
| 模型切换 | GPT → Ming → GPT；不同 GPT 版本切换；参数/缓存不串用 | PR4—PR7 |
| 加速 | 有/无加速库；实际探测；图准备、重载、失效、回退 | PR4、PR5、PR7 |
| 音源与音频 | 克隆/编辑、MP3/M4A 解码、播放、导出、QQ SILK 消费者 | PR1、PR2、PR6、PR7 |
| API | 同一请求入口选择两种后端；默认音源、参数校验、取消、输出 | PR2—PR4、PR7 |
| UI | 模型入口、两条并列项目、GPT 预装显示、下载操作、返回、现有语言 | PR6、PR7 |
| 包边界 | 后端代码与官方权重分开，APK 不含 Ming runtime，宿主无 GPT 实现 | PR3、PR4、PR7 |
| 性能 | 相同样本和设备比较冷/热耗时、RTF、内存、缓存和输出质量 | P0、PR4、PR5、PR7 |

执行规则：

1. 搬迁/改名后先编译和跑受影响的现有测试，再进入下一类结构改动。测试随职责迁移，不因为换目录重写整套测试。
2. 新测试针对真实行为边界：预装、动态装载、能力路由、跨模型切换、取消与包内容。不给纯改名和低风险目录搬迁编写镜像测试。
3. 独立 PR 的检查先完成，再开始下一个。测试通过且无新改动时不无理由重复全套重测。
4. 实机测试不得仅判断“生成文件存在”。检查采样率、有效样本、时长、播放、异常截断与明显音频回退；对原本非确定的生成不强制逐字节等同。
5. 性能比较固定设备、权重、文本、参考录音、线程、执行方式及温度条件；冷/热分别记录。出现持续退步先定位 worker 重启、缓存失效、拷贝或执行回退，不能用“重构”解释并跳过。
6. 缺少模型或设备的项目记为未验证；不在测试中静默跳过后将全部功能标为通过。
7. APK 与 ZIP 都检查压缩内容和装载位置。桥接包无需模型权重即可构建；推理测试另行传入模型数据。
8. Windows 路径移动前验证目标位于对应工作区；使用原生路径 API。多行 PR 正文用文件传给 `gh pr create --body-file`，不拼接未经转义的 shell 文本。
9. 签名材料仅按既有构建配置使用，不读取并输出密码。测试资源保留在忽略目录或测试应用数据目录。

## 7. 高风险实施点与进入条件

| 实施点 | 进入下一阶段前必须证明 |
| --- | --- |
| 动态包与 Android 资源 | 入口和错误消息不依赖宿主本次编译生成的 `R` 数值；Release 可正确装载 |
| native / ORT | classloader 与进程归属明确；GPT/Ming 切换没有重复加载冲突或跨运行库 handle |
| GPT 预热与 worker | 既能取消和回收，又保持原有热请求收益；不能机械复制 Ming 每次退出策略 |
| 资源模板 | 图结构、字典与学习权重完成内容级分类；后端 ZIP 不夹带官方权重 |
| importer 与 UI | 模型解释归后端，宿主表单和公共文件传输正常；没有借拆分删掉原有导入能力 |
| NPU | 设备探测与模型图执行分离；缓存命中、失效和准备时序经过实测 |
| 测试覆盖 | V2 运行、各 GPT 版本、Ming 实际样本齐备；已知能力缺口与重构回退区分 |

遇到技术选择未被实证支持时，先在当前 PR 内做最小验证，记录结果再决定；不把未经验证的方案层层扩展。上述项是执行门槛，不是要求新增 UI 或重复请求确认。

## 8. 进度与证据记录

- [x] 核实当前宿主、GPT、Ming、资源导入和加速的主要耦合。
- [x] 核实现有契约、安装机制、构建配置、JNI/R8 与测试资产。
- [x] 明确最终目的、范围、依赖方向、目录、PR 顺序及验收条件。
- [x] P0 基线准备。V2、Ming、GPU/NPU 的未验证项见下表。
- [x] PR1 命名。MuMu 的 ABI 限制见下表。
- [x] PR2 模型边界。Ming 和 V2 的样本限制沿用 P0。
- [x] PR3 公共桥接装载。MuMu 原生 x86_64 与 ARM64 转译结果分别记载。
- [x] PR4 GPT 独立包和默认预装。原版 V2 和原生 ARM64 的限制见下表。
- [ ] PR5 加速职责。
- [ ] PR6 模型页面与能力驱动流程。
- [ ] PR7 综合验收。

实施时每项完成后填写以下记录，不用对话或工作汇报替代证据：

| 阶段 | 分支 / PR | 基准与 head | 验证命令 / 设备 / 样本 | 结果及证据路径 | 未解决项 |
| --- | --- | --- | --- | --- | --- |
| P0 / PR1 | `refactor/voicebox-01-namespace` | 基准 `5b3942f`，head `223a7e5`（[PR #1](https://github.com/Renakoni/Voicebox/pull/1)） | x86_64 Debug、arm64-v8a 混淆 Release（engine-v1 AAR）；两个变体的 Lint；119 项 JVM 单测；MuMu API 32 | `artifacts/refactor-pr1/` 的 `debug.log`、`release.log`、`native-tests.log`、`release-jni.txt`、`benchmark-final.log`、`studio-playback-export.log` | Ming ARM64、WebGPU、QNN 待对应设备 |
| 调研规划 | 规划工作区 | `5b3942f` | 源码与构建配置只读审查 | 第 3 节记载的是实施前的调查 | 已进入实施阶段 |

### P0 本次基线

基线与远端 main 均为 `5b3942f369277f649ac5c129890ad6352bc9c2f6`。参考源 ming-android 为 `7bba07df`，阅读器为 `baac8b72`。构建使用 JDK 22、Gradle 9.4.1、AGP 9.2.1、SDK 37 和 NDK 28.2.13676358。设备为 MuMu 12，ADB 地址 `127.0.0.1:16384`，API 32，首选 ABI 为 x86_64。保留模拟器上的原应用，基线 APK 使用独立的 debug applicationId 后缀 `.baseline`。设备采集文件保存于 Git 忽略的 `artifacts/refactor-p0/`。

| 样本 / 验证 | 本次基线证据 |
| --- | --- |
| JVM / 安装 | `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -PgenieAbi=x86_64` 成功。JUnit XML 共 119 项通过，失败、错误、跳过均为 0。详见 `gradle.log`、`install-isolated.log`。 |
| V2 Pro Plus | 使用已导入的 Roxy 3.0、日语真实参考和固定中文合成句，CPU 默认线程、默认采样，连续两个请求在同一服务运行。进程冷启动首请求 49,690 ms（含首次准备），热请求 14,689 ms，RTF 分别为 13.36 和 3.95。两次均为 32 kHz 单声道、119,040 样本、3.720 s，PCM16 峰值 8348、RMS 1852.5，进程 VmHWM 为 1,993,176 KiB。输入哈希、WAV、热状态、分阶段日志及测量代码见 `input-sha256.txt`、`refactor-{0,1}.{txt,wav}`、`thermal.txt`、`runtime.log` 和独立基线工作树的 `RefactorTiming.kt`。 |
| V2 Pro | 真实 `s1v3.ckpt`、`s2Gv2Pro.pth`，中文参考。导入的四个权重通过预期 SHA-256，图可加载，CPU 合成 32 kHz、170,240 样本。见 `import-v2pro.log`、`v2pro.json`。 |
| V5 Turbo | 真实 `s1v3.ckpt`、`s2Gv5turbo.pth`、独立声码器和中文参考。导入、权重校验、图加载和 CPU 合成通过。输出为 48 kHz 单声道、192,000 样本、4.000 s。见 `import-v5turbo.log`、`v5turbo.json`、`v5turbo.wav`；三个变体的实测音频统计见 `audio.json`。 |
| V2 | 代码入口为 `CharacterModels/v2/<id>/tts_models`。本机锁定清单的 `v2_compatibility_asset` 为空，同一上游版本的 `CharacterModels/v2` 目录返回 HTTP 404，缺完整运行样本。原始 V2 checkpoint 导入属于基线能力缺口。 |
| MuMu 功能测试 | 原生重采样、日英前端、两个 SILK 用例和模型页导航通过。早期 Roxy 图上的生成超时；换用当前模板的音源后，Studio 生成通过，随后测试在 API 32 调用 `FileInputStream.readNBytes` 失败，见 `mumu.log`、`studio-final.log`。PR1 将该测试的四字节读取换为 `DataInputStream.readFully`。 |
| Ming / 加速 | Ming 的 arm64-v8a 包与 MuMu 首选 ABI 不匹配；安装后克隆、取消及创建页用例的前置条件未满足。GPT WebGPU 探测返回 `No supported adapters`；QNN、Ming CPU/WebGPU 和真机听感未复验。模型与桥接来源分别为本地 ming-android 导出资料和 `artifacts/ming-runtime-v1`。 |

### PR1 旧名审查

- `GenieSymbols`、`GenieTextConventions`、语言前端清单与上游脚本路径标识音素表和训练时的文本约定。
- `genie_t2s_*_v1`、`genie_v2proplus_split_v1`、`genie.interface` 及 `genie.multi_reference.*` 是现有模型数据格式。模板生成器、解析器和测试使用相同标识。
- `session.genie_fp16_weight_storage` 是定制 ORT 的运行库配置。许可证、模型来源、`GenieData` 和已公布的 `genie-tts-runtime-assets.zip` 对应上游或已分发资源。

后续发布前另立升级兼容任务；本清单不提前实现其方案。

PR1 的混淆 APK 含 12 个以新包名导出的 JNI 方法。MuMu 的 8 个 native/模型页用例中 5 个通过，3 个因 Ming ABI 或安装前置条件跳过。新 applicationId 下的 Studio 音源创建、重建、合成、实际播放位置推进、暂停、FileProvider 读取与 WAV 导出均通过。为在 API 32 验证播放，测试使用平台支持的四字节读取，并在主线程调用播放控件的语义事件，避免 Compose 等待每帧更新的时间轴空闲时短音频已播完。

### PR2 模块与设备

分支 `refactor/voicebox-02-model-boundaries`，基准 `223a7e5`，head `071110d`（[PR #2](https://github.com/Renakoni/Voicebox/pull/2)）。五个 Gradle 模块构建通过。两种 provider 暂由 app 静态注册，PR4 移除；core 与 bridge-api 的源码均无模型实现或 ORT 引用。官方资源、Torch、模型前端、推理和专有 JNI 随各后端迁移。调度、下载、音频、重采样和输出存储归 core。

- 本地运行 `assembleDebug assembleDebugAndroidTest testDebugUnitTest -PvoiceboxAbi=x86_64`。五个模块的 JUnit XML 共 120 项，失败、错误及跳过均为 0。HTTP 用例覆盖同名模型、默认音源、选项隔离及取消。证据在 `artifacts/refactor-pr2/final-debug.log` 和各模块的测试报告。
- 在 MuMu API 32 分别运行 core 重采样、GPT 日英语言前端及宿主集成用例。Roxy 3.0（V2 Pro Plus）Studio 音源重建、CPU 生成、播放及导出通过，两个 SILK 用例通过。Ming 的两个用例因 ARM64 前置条件跳过。见 `resampler.log`、`languages.log`、`integration.log`。基线工作树也用修正后的 Studio 探针复验通过，见 `artifacts/refactor-p0/studio-playback-export.log`。
- 通过应用 UID 暂存相同的真实 checkpoint 及参考音频。V2 Pro 导入、图加载及 CPU 合成通过，32 kHz 单声道 147,200 样本，PCM16 峰值 8,524。V5 Turbo 同项通过，48 kHz 单声道 178,560 样本，峰值 12,280。报告、哈希与 WAV 在 `artifacts/refactor-pr2/{v2pro,v5turbo}.{json,wav}`。V2 原版仍缺完整样本，Ming、WebGPU 和 QNN 的设备限制同 P0。

### PR3 装载与进程

分支 `refactor/voicebox-03-bridge-loader`，基准 `071110d`。APK 目录登记入口、契约、ABI、上游提交、适配层源码摘要和文件清单。APK asset 与 HTTPS 来源复用流式大小和 SHA-256 校验，再写私有暂存目录。跨线程及进程加锁，完成标记和只读 DEX 随目录一起原子提交。修复损坏的安装会写入新目录。前台服务仍拥有请求，私有 worker 在自己的 DEX/native 环境执行；宿主死亡或取消可释放进程，清理不会沿符号链接进入音源。

| 本地验证 | 结果 |
| --- | --- |
| 构建与包边界 | Debug、混淆 Release、120 项 JVM 测试通过。Release 类定义审计确认宿主无 Ming 实现，候选包无共享契约。ARM64 ZIP 为 16,966,599 B，SHA-256 `01f838f241d61dd023645cd80cc2f9969a0e481ed571a82b1e2e352967f697ec`，含清单、一个 DEX JAR 和五个运行库。见 `artifacts/refactor-pr3/{production-debug.log,release-verified-build.log,dex-audit.json}`。 |
| 安装、错误及清理 | 六项安装器单测覆盖错误哈希、ABI、契约、路径、大小、重复项、取消、重试、保留已存在快照及重启复用；已有下载测试覆盖实际回环 HTTP 及坏镜像回退。MuMu 上的链接清理用例通过，见 `worker-files.log`。 |
| 混淆宿主 | 使用相同 R8 规则、定制 ORT 及独立包名后缀 `.pr3verify`。在 MuMu 安装 ARM64 候选 ZIP，加载独立 DEX，调用共享能力和私有 worker；缺模型错误正常跨进程返回。见 `release-worker-final.log` 及同目录的探针源码、Gradle 初始化脚本。 |
| Ming 实际运行 | 为 MuMu 单独从相同源码构建 x86_64 候选包，使用上游标准 ONNX V1 的 CPU FP32 模型、原参考编码器和真实参考 WAV。独立进程完成固定音色、重新编码参考音频的克隆、播放进度推进、取消及其后的请求。两次语音分别为 44.1 kHz 单声道 123,480 和 91,728 样本。测试包使用单独 applicationId。见 `ming-x86-verified.log`、`audio.json`、`ming-{default,clone}.wav` 和 `x86-assets/bridges/ming.json`。 |
| 设备与分发限制 | 对 MuMu 强制使用 ARM64 转译后，包安装、共享契约和缺模型错误可测；真实 ARM64 模型进入 ORT 后发生 SIGSEGV，原生 ARM 设备仍待复验。GPT 的旧 WebGPU 探测在转译环境出现原生 `No supported adapters` 中止。见 `{ming-arm,arm}-crash.log`。公开 `ming-runtime-v2` URL 尚无 Release；本次设备从本地暂存的候选 ZIP 安装，未上传构建物。 |

### PR4 GPT 独立包与默认预装

分支 `refactor/voicebox-04-gptsovits-package`，基准 `6e9932d`。本地证据目录为 `artifacts/refactor-pr4/`。GPT 的模型、字典、checkpoint、ORT 与 JNI 实现通过与 Ming 相同的固定目录、安装器、DEX 装载器与 worker 运行。Studio 读取后端提供的选项和操作，已安装桥接与含权重的音源分开检查。

| 验收 | 本轮实测 |
| --- | --- |
| 构建与边界 | x86_64 Debug、ARM64 R8 Release、120 项 JVM 测试通过。两种 APK 均只内置各自 ABI 的单个 GPT ZIP；逐文件比较独立包和 APK 中的副本，校验目录 SHA、75 个资产及 DEX 类定义。生产 Release ZIP 为 20,118,302 字节，本次构建 SHA-256 为 `4a138b4a87655f7e51a7b02cb4442847b261c2136e42524c2be3b7145244d3e9`。见 `dex-{debug,release}.json`、`final-{debug,release}.log`。源码提交是构建输入，再次构建会生成对应新目录。 |
| 权重审计 | 检查图 initializer、属性常量和 external data。逐值比对八组大于等于 16 项的浮点数组与位置/时间频率、正余弦表、全 0/1、Hann 窗及 Slaney mel 滤组。GPT 和声码器的训练参数均在外部文件。英语 GRU 和 POS 权重、日语统计词典移到音源资源侧。用真实 NPZ 运行 Kotlin 转换，英语图每个张量均与原图相同，词表、POS 文件和日语五个文件逐项哈希一致；MuMu 日英前端及未知词 oracle 通过。见 `graph-content.json`、`unique-floats.log`、`language-parity.json`、`language-oracles-final.log`。 |
| 安装与混淆 | 独立包名、无音源数据的原生 x86_64 R8 APK 在 MuMu 直接从 APK 预装并复用同一目录；模型资源查询为缺失。独立 GPT DEX 能列出官方音源，以字符串键和参数跨进程返回真实导入错误；用相同 DEX/native 加载路径建立 CPU ORT 图会话。见 `native-proof.log` 和验证源码。ARM64 定制 ORT 在 MuMu 强制转译下触发 native SIGSEGV，见 `minified-arm-crash.log`，原生 ARM64 仍待实机。 |
| 真实音源 | x86_64 MuMu API 32，四个 V2 Pro 权重及 V5 Turbo 导入文件分别通过来源 SHA；独立 worker 输出单声道 PCM，V2 Pro 32 kHz / 131,840 样本，V5 Turbo 48 kHz / 199,680 样本。两种官方音源均在空目录通过网络下载、校验及转换；Studio 克隆、重建、生成、播放、导出通过。见 `{v2pro,v5turbo}.json`、`official-{download,v5}.log`、`audio.json` 和 `studio.log`。原版 V2 运行样本仍缺，见 P0。 |
| 会话与性能 | P0 固定 Roxy 模型、日语参考和中文句，默认 CPU，进程冷启动首请求 24,426 ms，热请求 12,428 ms，RTF 5.87 / 2.99。两次均为 32 kHz、133,120 样本、4.16 s，峰值 8,512；同一 worker PID 20939，VmHWM 1,958,612 KiB；宿主 PID 20866，289,852 KiB。P0 分别为 49,690 / 14,689 ms。缓存与模拟器热状态不能严格复现，两个进程各自峰值不等于同时占用。超时、解码中取消、切换音源、服务销毁及随后的真实生成通过。见 `refactor-{0,1}.txt`、`lifecycle-real-final.log`。 |
| 跨后端 | 使用同源 x86_64 Ming 本地候选，单个前台服务完成 Roxy → Ming → Roxy。三次输出分别为 32 kHz / 65,280、44.1 kHz / 63,504、32 kHz / 62,720 样本且非静音，见 `switch.log`、`switch-audio.log`。 |
| 多参考 | 对同一 V2 Pro Plus 模板使用上游多参考导出器构造可选八槽图，验证真实日语主参考和第二段中文参考经宿主请求、IPC、独立 prompt encoder 与 CPU 合成。结果为非静音 32 kHz WAV，见 `multi.log` 与 `switch-probes/MultiProof.kt`。 |
