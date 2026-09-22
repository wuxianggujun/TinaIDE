# Hex Viewer 设计说明

> 更新日期：2026-06-29
>
> 状态：设计与能力说明。设计目标、模块边界、许可证和“未做的事”属于当前口径；
> 下方逐条的分析能力清单随 `feature/viewer` 持续增补，只作为能力索引，
> 判断某项能力是否存在时以 `feature/viewer/src/main/java/.../viewer/HexBinaryAnalysis*.kt`
> 和 `feature/viewer/src/test/.../HexBinaryAnalysisTest.kt` 为准。

本文记录 TinaIDE Hex Viewer 的当前设计、功能边界和开源致谢。

## 设计目标

TinaIDE 的 Hex Viewer 目标是成为项目内二进制文件的基础查看与 patch 工具，而不是把逆向分析、反汇编和 OLLVM 检测全部塞进一个超大 UI 组件。

当前边界如下：

- `feature/viewer` 负责用户可见的 hex 查看、搜索、选择、导出和 patch 队列。
- 文件读取采用 chunk + LRU 缓存，避免把大文件整体读入内存。
- UI 按字节单元渲染，支持选中、选区、staged patch 高亮和 ASCII 同步。
- 修改先进入 staged patch 队列，用户确认保存后再批量写入文件。
- 逆向分析能力后续应作为二进制分析面板扩展，例如 ELF 字符串、导入导出、熵图、OLLVM 特征提示。

## 已集成功能

- Offset 跳转：支持绝对地址和相对表达式，例如 `0x100`、`+0x20`、`-16`。
- 跳转历史：支持后退和前进。
- 搜索：支持 ASCII / UTF-8 文本、十六进制 pattern，以及 `??` 通配字节。
- 范围选择：支持设置选区起点、终点和清除选区。
- 选区 Inspector：支持把当前选区样本解释为 Hex、ASCII、UTF-8、u8/i8、u16/u32/u64 LE/BE，用于快速判断结构字段、长度值和文本片段。
- 导出：支持 Hex dump、C array、Kotlin `ByteArray`、Base64 和 ASCII。
- Offset 书签：支持从当前 offset 或长按菜单切换书签，书签列表按 offset 排序，支持高亮、跳转和单条移除；Magic signature、重复字节段、熵块、OLLVM/obfuscation findings、analysis signals、ELF risk findings 和 DEX invoke/string/field references 支持单条或批量标记，便于在分析结果、patch 和可疑区域之间来回定位。
- Patch 队列：支持 staged patch、Undo、Redo、明细列表、复制 radare2 `wx` patch 脚本、按 offset 跳转、单条丢弃、Discard 和 Save。
- 二进制分析工作台：主 Hex 内容区保持独立占满剩余空间，搜索面板、footer 工具和分析结果默认不挤占编辑区；宽屏可把分析栏停靠到右侧，窄屏仍使用弹窗展示分析结果。
- 工作台发现项：把 ELF 风险、JNI 注册线索、Native API 导入、DEX native 方法、APK native marker、APK entry name 风险、混淆启发式和 analysis signals 汇总成按严重度排序的可导航列表，支持跳转、批量标记 offset 书签和按发现项生成命令模板。
- Rizin/radare2 命令入口：支持生成当前 offset 导航脚本、选区 dump 脚本、staged patch 脚本、完整工作台脚本、只读分析脚本和反汇编预览脚本，当前只复制脚本文本，不在 App 内直接执行外部逆向工具。
- 动态分析模板：可基于当前 offset 或发现项生成 Frida Hook 模板和 LLDB breakpoint/memory/disassemble 模板，用于授权调试场景下复制到外部工具继续分析。
- Reverse Action Pipeline：发现项统一映射为可复制的逆向动作，包括只读分析、反汇编预览、Frida Hook、LLDB 断点和 JNI 分析报告，UI 只负责展示/复制动作输出，便于后续替换为插件式执行器。
- JNI/APK 分析报告：从当前分析结果生成 Markdown 与 JSON 报告，汇总 DEX native 方法、APK native library、JNI 注册线索、Native API、loadLibrary 候选字符串、ELF 风险和工作台发现项，便于逆向笔记、问题单和后续 AI 分析复用。
- 二进制分析面板：支持文件类型识别、ELF 摘要、字符串预览/完整列表、熵摘要和基础信号提示。

## 二进制分析能力

当前分析层位于 `feature/viewer`，不依赖外部 native 工具，因此可以在普通单元测试中覆盖核心行为。

已支持：

- 文件类型识别：ELF/SO、DEX、APK、ZIP、PNG、JPEG、未知二进制。
- 文件指纹：对完整文件流式计算 SHA-256、SHA-1、MD5 和 CRC32，分析面板展示短 SHA-256 入口，详情弹窗支持复制完整指纹，便于 APK/SO/DEX 样本比对和逆向记录。
- 字节分布：在同一次文件流式扫描中统计 256 个 byte value 的频率，展示唯一字节数、00/FF 占比、可打印 ASCII 占比、控制字节占比和 Top Bytes，用于快速判断填充、文本密度、压缩/加密或混淆倾向。
- 重复字节段：在同一次文件流式扫描中跨缓冲区识别连续相同 byte 的长 run，保留最长段并支持 offset 跳转，用于定位 00/FF padding、对齐空洞和可疑填充区。
- 嵌入签名：在同一次文件流式扫描中用滚动窗口识别 ELF、DEX、ZIP、PNG、JPEG、resources.arsc 和 SQLite 等 magic signature，签名列表支持 offset 跳转，用于快速定位容器内嵌文件头和结构边界。
- DEX 结构摘要：解析 DEX header、checksum/signature、string/type/proto/field/method/class 计数、`string_ids`、`type_ids`、`proto_ids`、`field_ids`、`method_ids`、`class_defs`、`class_data_item`、`code_item` 和 `map_list`；proto 会解析 shorty、返回类型和受限参数 type_list，method 会展示原型签名，class data 会解析 direct/virtual method 的 `method_idx_diff`、access flags 和 `code_off`，并基于 `ACC_NATIVE`、`ACC_ABSTRACT` 与 `code_off` 派生 code/native/JNI/abstract/no-code 执行语义和 native method count，native 方法会生成 analysis signal 跳转到 class data entry；code item 会解析 registers/ins/outs/tries、debug info offset、insns size、首条 opcode 和受限 code units 预览；在受限扫描窗口内解析标准 `invoke-*` / `invoke-*/range` 的 method_id 引用、`const-string*` 字符串引用和 instance/static field access 引用，展示 caller、target/value/field、opcode、instruction offset、code item offset 和目标 id/data offset；支持字符串、类型、原型、字段、方法、类定义、class data 方法、code item、invoke 引用、字符串引用、字段引用与 map 条目过滤、offset 跳转。
- APK/ZIP 条目摘要：解析 ZIP central directory，识别 `AndroidManifest.xml`、`classes.dex`、`lib/**/*.so`、资源和签名条目，展示 compression method、general purpose flags、CRC32、compressed/uncompressed size、local header offset、file data offset、compressed data end offset 和 central directory offset，派生 data range 正常/未知/超出文件/重叠 central directory 状态，支持分类过滤、range/status 关键词过滤以及 local/data offset 跳转；展示 EOCD、central directory offset/size、entry count、comment length 与 ZIP64 locator offset（如存在），用于核对 APK 签名块前后的包结构；对 APK 内二进制 `AndroidManifest.xml` 做受限解析，展示 string pool 数、element 数、root element、package 和 `uses-permission` 摘要；对 APK 内 `resources.arsc` 做受限解析，展示 package count、global string pool、resource package、type/key string pool、type spec 和 type chunk 摘要；对 APK 内 `lib/**/*.so` 做轻量 native 摘要，展示 ABI、ELF 位数、机器架构、压缩/大小/CRC、分析窗口是否截断，并基于 ZIP method 与 file data offset 派生 Android native 装载语义（可直接 mmap、stored 未页对齐、需解压/抽取、未知），支持按 mmap/stored/compressed 等关键词过滤和 load mode 分类过滤，同时扫描 OLLVM / control-flow-flattening / bogus-control-flow / instruction-substitution marker；对 APK 内 `classes*.dex` 做受限解压解析，展示版本、字符串数、方法数、类数和字符串预览；对 APK Signing Block 做 EOCD/central directory 前回看，解析 v2/v3/verity padding 等 pair id、value size、block/pair/value offset，支持过滤和 offset 跳转。
- APK/ZIP local header 校验：读取每个条目的 local header name、method 和 general purpose flags，与 central directory 条目对比，派生一致、未知、名称不一致、method/flags 不一致和多处不一致状态；Archive 列表直接展示 local header 状态，并支持按 local header 名称、`local mismatch`、method/flags 等关键词过滤，用于识别 APK/ZIP 篡改、截断或异常打包结构。
- APK/ZIP entry 名称风险：按 central directory 条目名称派生空名称、重复名称、绝对路径、Windows 盘符路径、路径穿越和反斜杠分隔符风险；Archive 列表直接展示 name risk 状态，并支持按 `duplicate name`、`path traversal`、`absolute path`、`backslash` 等关键词过滤，用于识别异常打包、Zip Slip 风险和伪装条目。
- ELF 摘要：32/64 位、大小端、machine、entry point、Program Header 数量、Section Header 数量。
- ELF Program Header：解析 `PT_LOAD`、`PT_DYNAMIC`、`PT_GNU_RELRO`、`PT_GNU_STACK` 等 segment，展示 VA、file offset、file/mem size、flags 和 align，支持过滤与跳转。
- ELF Section 到 Segment 映射：基于 `PT_LOAD` file range 将有真实文件字节的 section 映射到承载 segment，展示 section VA/offset/size、segment VA/offset/file/mem size 和 R/W/X 权限，支持按段权限过滤与 offset 跳转。
- ELF Section 熵分析：对有真实文件字节的 section 做受限采样，按 Low/Medium/High 复用全文件熵阈值展示 section 熵、采样大小和 offset，支持过滤与跳转，用于定位压缩、加密或混淆相关数据段。
- ELF hardening：基于 ELF type、`PT_GNU_STACK`、`PT_GNU_RELRO`、`DT_BIND_NOW`、`DT_FLAGS` 和 `DT_FLAGS_1` 生成 PIE、NX、RELRO、BIND_NOW 启用状态提示。
- ELF 风险提示：基于 `PT_LOAD`/section flags、可执行栈、RELRO/BIND_NOW、`DT_RPATH`/`DT_RUNPATH` 和 `DT_SONAME` 派生 High/Warning/Info 分级提示，支持按加固、段与节、路径和元数据过滤并跳转证据 offset。
- ELF section：从 `.shstrtab` 和 Section Header 读取 section 名称、类型、flags、VA、file offset 和 size，提供摘要、完整列表、过滤和 offset 跳转。
- ELF 动态符号：从 `.dynsym` 和关联 `.dynstr` 读取 imports、exports 和 JNI 符号，解析 `st_shndx` / file offset 对应的 section 归属，提供摘要、完整列表、名称/地址/section 过滤和可映射符号跳转。
- ELF Dynamic 依赖与 flags：解析 `.dynamic` 里的 `DT_NEEDED`、`DT_SONAME`、`DT_RPATH`、`DT_RUNPATH`、`DT_BIND_NOW`、`DT_FLAGS` 和 `DT_FLAGS_1` 条目，展示依赖库、DT_NEEDED 声明加载顺序、SONAME 运行时身份、RPATH/RUNPATH 搜索路径语义、BIND_NOW flags、entry offset，并支持过滤、按语义关键词搜索和跳转。
- ELF Notes / Build ID：解析 `.note*` section，识别 GNU Build ID，展示 note 名称、type、descriptor offset、descriptor hex/text，并支持 Build ID / GNU / Android 过滤和跳转。
- ELF 地址映射：从 PT_LOAD Program Header 将 entry point、导出符号和 JNI 符号的虚拟地址映射到文件 offset，可映射时支持跳转。
- ELF JNI 注册线索：基于动态符号和字符串提取 `RegisterNatives`、`JNI_OnLoad` / `JNI_OnUnload`、`Java_*` 静态导出、Java class descriptor 和 JNI method signature，支持按 RegisterNatives、入口、静态导出和描述符过滤并跳转证据 offset。
- ELF Native API 导入线索：基于 imported dynamic symbols 对 `dlopen` / `dlsym`、`mprotect` / `mmap`、`ptrace` / `prctl`、文件 IO、socket、OpenSSL / crypto、pthread 和日志 API 做分类提示，支持按类别过滤。
- ELF init_array：解析 `.init_array` 函数指针，展示指针文件 offset、目标 VA 和可映射目标 offset，支持完整列表和跳转。
- ELF relocation：解析 `.rel*` / `.rela*` section，展示 relocation entry offset、重定位目标 VA、可映射文件 offset、目标 section/GOT slot、符号名、符号绑定/类型/导入导出角色、type 名称/编号、type semantic（PLT slot 绑定、GOT 符号地址写入、Relative rebase、Copy relocation、绝对地址、PC-relative fixup）和 addend，支持 PLT/Dynamic 过滤、按语义关键词搜索和跳转。
- ELF PLT/GOT linkage：基于 relocation 和 `BIND_NOW` flags 派生动态链接语义表，展示导入符号、PLT/GOT/Relative 分类、NOW/LAZY/LOAD_TIME/LOCAL 绑定模式、resolution semantic（启动时回写 PLT/GOT、首次调用 Lazy PLT 解析、加载期 GOT 写入、Relative rebase、本地 fixup）、slot VA/file offset 和 relocation entry offset；对 AArch64 `.plt` 常见 `adrp/ldr/add/br` stub 和 x86_64 `.plt` 常见 `jmp *GOT(%rip)` / `push` / `jmp` stub 做轻量识别，展示 stub offset、bytes、GOT slot 关系并支持 stub/slot 跳转；支持 Imports/PLT/GOT/JNI/NOW/LAZY 过滤和按语义关键词搜索。
- ELF Dynamic Linker Steps：基于 Program Header、`DT_NEEDED`、RPATH/RUNPATH、relocation、Linkage、RELRO、init_array 和 JNI 符号派生动态链接器步骤，展示加载依赖、依赖声明顺序、搜索路径摘要、应用重定位、NOW/LAZY 绑定、保护 GNU_RELRO、调用 init_array 和暴露 JNI 入口的顺序，并支持按 Loading/Relocations/Binding/Hardening/Entrypoints 过滤和跳转。
- 字符串提取：扫描前 8MB 的 printable ASCII、UTF-8 多字节、UTF-16LE 和 UTF-16BE 字符串，提供预览、完整列表、查询/编码过滤、结果复制和 offset 跳转。
- 熵计算与熵图：按桶计算 Shannon entropy，生成低 / 中 / 高分级条形图，支持完整熵块列表、等级过滤和 offset 跳转。
- 混淆启发式提示：基于 OLLVM 明确标记、控制流平坦化 / bogus control flow / 指令替换标记、高熵低字符串密度和符号剥离迹象生成风险提示。

未做的事：

- 不在 Hex Viewer 内直接做完整反汇编。
- 不在 Hex Viewer 内直接启动 Rizin、radare2、Frida 或 LLDB；当前只生成可复制脚本/模板，后续应通过插件式执行器接入。
- 不把 OLLVM 结论写死为“已混淆”；当前只作为启发式风险提示。
- 不引入 radare2/r2pipe 作为当前 APK 编译依赖。

## 阶段增量记录（历史参考，2026-06-29）

以下内容是当时那一轮改动的记录，不是独立事实源；上面的“已集成功能”和源码才是当前口径。

- Hex Viewer 工作台布局改为“主内容 + 可停靠分析栏”：主十六进制网格继续使用剩余高度和宽度，分析栏只在宽屏打开时占用右侧固定宽度，窄屏点击分析会自动回退到弹窗，避免操作按钮或分析结果遮住编辑区。
- 新增工作台命令弹窗：从当前 offset、选区和 patch 队列生成 Rizin/radare2 可读脚本，覆盖 `s`、`px` 和 `wx` 这类常用定位/查看/patch 流程；当前阶段只生成脚本，后续再通过插件式执行器接入真实外部工具。
- 新增二进制发现项工作流：把 ELF risk、JNI hint、Native API、DEX native method、APK native marker、APK entry risk、混淆线索和 signals 汇总到右侧栏顶部，支持直接跳转、批量标记和按单个发现项生成只读分析脚本。
- 新增逆向模板：命令弹窗提供 Rizin/radare2 只读分析脚本、`pd` 反汇编预览脚本、Frida Hook 模板和 LLDB breakpoint 模板；模板默认使用当前 offset，从发现项打开时优先使用发现项 offset 和可用符号名。
- 新增 Reverse Action Pipeline：把当前 offset、选区、分析结果和发现项统一映射到一组动作输出，命令弹窗和报告弹窗都从同一套 action content 读取，避免 UI 内分叉生成脚本。
- 新增 JNI/APK 报告弹窗：可复制 Markdown 或 JSON 报告，覆盖 DEX native 方法、APK native library、JNI 注册线索、Native API、loadLibrary 候选字符串、ELF 风险和工作台发现项；当前只复制报告内容，不直接写入项目文件。
- 混淆启发式继续扩展到常见 Android 加壳/保护器线索，例如 360 Jiagu、Bangcle、Ijiami、SecNeo、Legu、DexProtector、UPX、VMProtect、Arxan 以及常见 `libshell` / `libprotect` / `libdexhelper` 字符串。该能力同时覆盖单独打开 `.so` 的 ELF 分析，以及 APK 内嵌 `lib/**/*.so` 的轻量 native 摘要。
- 这类结果仍按“启发式提示”处理，只说明样本里存在相关 marker 或字符串证据，不把结论写死为“已加壳”或“已虚拟化”。

## 开源致谢

Hex Viewer 的交互方向和部分设计取舍参考了以下开源项目：

- r2droid: https://github.com/wsdx233/r2droid

感谢 r2droid 对 Android 端二进制查看、r2 风格操作和移动端 hex 交互设计的探索。TinaIDE 当前实现没有引入 r2droid 作为编译依赖，也没有直接分发 r2droid 的源代码；本项目保留其开源协议文本用于致谢和许可证追踪。

## 许可证保留

r2droid 使用 MIT License。许可证文本保存在：

- `docs/third-party-notices/r2droid-MIT-LICENSE.txt`

如果后续直接复制、修改或分发 r2droid 的实质性代码，应继续保留原 copyright 和 MIT License 文本，并在变更说明中明确标注来源文件和改动范围。

## 后续扩展建议

下一阶段建议在保持 Hex Viewer 基座稳定的前提下继续扩展二进制分析面板：

- ELF / SO 深入解析：继续补充更多 ABI 的 PLT stub 指令序列识别，以及更细粒度的 architecture-specific relocation 类型说明。
- 字符串视图：补充更丰富的上下文标记、文件导出入口和与 AI 分析入口的衔接。
- 熵视图：后续补充选区导出入口。
- OLLVM 特征提示：后续可继续扩展到 CFG 级别的控制流平坦化和 bogus control flow 结构识别。
- AI 分析入口：如需接入，应把选区、字符串或 ELF 元数据交给内嵌 RikkaHub 的宿主入口；当前 TinaIDE 主仓库不再维护自研 AI 工具链。
