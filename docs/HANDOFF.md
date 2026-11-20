# Reduction 交接说明

写给接手这个项目的人。读完这一份，应该知道项目是什么、现在在哪、怎么动手、哪里容易踩坑。功能的完整说明在 [DEVELOPMENT.md](DEVELOPMENT.md)，这里不重复，只指路。

状态截至 2026-11-18，仓库 `main` 分支，提交 `4e4ac5a`。

## 一、这是什么

一个随身的语音助手：对它说话，它记下来、整理进卡片、需要时去查或去做。两个端：

| 端 | 形态 | 入口 |
|---|---|---|
| 手机 | Android App，包名 `com.mindtravel.reduction` | 唤醒词“Hi Reduction”，说完说“完毕” |
| 电脑 | Windows 桌面上的一只桌宠，加一张面板 | 按住空格说话；点桌宠开面板 |

模型和语音都走 DeepSeek 的接口（对话、ASR、TTS、搜索）。唤醒词、结束词、有没有人在说话，这三样在手机本地判断（sherpa-onnx、Silero VAD）。

## 二、最重要的一件事：一份引擎，两个壳

对话、提示词、工具、卡片、待办、sub agent、朗读，是**同一份 Kotlin 源码**。手机直接编译它；电脑端的 `desktop/build.gradle.kts` 把 `app/src/main/java` 里不依赖安卓的文件点名编进去，再配一个本机 HTTP 服务当壳。

界面也是**同一套网页**（`app/src/main/assets`）。手机用 WebView 显示，电脑用桌宠里的 WebView2 显示。页面通过 `window.Android` 调原生；电脑端由 `desktop/src/main/resources/desktop.js` 冒充这个对象，把调用转成对本机服务的请求。

由此得出三条规矩：

1. **给引擎加文件，要去 `desktop/build.gradle.kts` 加一行 include**，否则电脑端编译不过或者悄悄缺功能。新文件不能引用 `android.*`。
2. **给页面加一个原生调用，要改三处**：`ui/bridge/AndroidBridge.kt`（手机）、`desktop/.../Main.kt` 的 `bridge()`（电脑）、`tools/ui-preview/run.js` 的假桥（预览）。
3. **共用的是代码，不是设置。** 两端各有各的设置文件和数据目录。电脑上“某个工具没有”多半是那边的开关没开，不是代码没同步。

## 三、现在的状态

| 项目 | 状态 |
|---|---|
| 仓库 | `main` 与 GitHub `mindtravel/Reduction` 一致，工作区干净 |
| 手机线上版本 | 1.0.65（versionCode 96），构建于 2026-11-18 07:34 |
| 电脑端 | 已按最新代码安装并在运行 |
| 测试 | 240 项单元测试通过，lint 无错误，页面预览脚本无报错 |

**手机线上版本比仓库旧。** 下面两项在仓库里、在电脑上，但不在 1.0.65 里，要再发一版手机才有：

- 工具结果不截断、长结果分片读取（`read_more`）。
- 电脑端面板的改动（与手机无关，列在这里只为说明提交历史）。

发版前注意：发版脚本会跑线上更新检查，见第七节。

## 四、仓库地图

```
app/src/main/java/com/mindtravel/reduction/
  app/AppCore.kt            手机端总装配：谁依赖谁都在这里
  feature/voice/            语音状态机（VoiceMachine 是纯函数）、录音、提示音
  feature/chat/             对话、提示词、工具、卡片存储
    model/SystemPrompt.kt   系统提示词，改了要重新生成基准文本（见第六节）
    model/tools/            助手的工具，一个文件一类
    data/CardRepository.kt  卡片和附件
  feature/memo/             待办：存储、日期解析、工具、闹钟
  feature/sync/             手机与电脑之间的数据交换
  feature/article/          把卡片排成文章、出长图
  ui/bridge/                页面能调的原生方法
app/src/main/assets/        网页界面、文章模板、电脑上运行的脚本（harness/）
desktop/                    电脑端：Main.kt 是服务，pet.cs 是桌宠，install.sh 负责安装
tools/                      发版、取模型、页面预览、出长图
docs/                       本文、DEVELOPMENT.md、THIRD_PARTY.md
```

助手现有的工具：`patch_cards`、`read_card`、`search_cards`、`read_more`、`todo`、`render_article`、`web_search`、`calculator`、`session_status`、`run_agent`。后四个受设置开关控制，`render_article` 要连着电脑才有。

## 五、搭环境、构建、运行

需要 JDK 17、Android SDK（compileSdk 35）。本机路径写在根目录的 `local.env`（不入库），照 `local.env.example` 填。

```bash
tools/models/fetch-models.sh     # 第一次：取本地语音模型，核对校验值
./build.sh                       # 手机安装包：dist/Reduction-v<版本>.apk
desktop/install.sh               # 电脑端：构建、编译桌宠、装图标、重启服务和桌宠
```

电脑端跑在 WSL 里，桌宠是 Windows 程序。`install.sh` 用 Windows 自带的 C# 编译器编译 `pet.cs`，从 NuGet 取 WebView2 的库，编译失败会报错停下。改了电脑端任何东西都重跑它。

三样东西不入库，换机器要自己带过去：`local.env`、`keystore.properties`、`keystore/` 里的签名文件。**签名文件丢了，已装的手机就再也升不了级。**

## 六、验证

| 做什么 | 命令 |
|---|---|
| 单元测试和 lint | `./gradlew :app:testDebugUnitTest :app:lintDebug` |
| 电脑端能否编译 | `./gradlew :desktop:installDist` |
| 页面行为（假桥，无需手机） | `node tools/ui-preview/run.js` |
| 提示词改动后更新基准文本 | `REDUCTION_APPROVE_PROMPTS=$PWD/app/src/test/resources ./gradlew :app:testDebugUnitTest --tests '*SystemPromptTest*'` |
| 出一张文章长图 | `node tools/article/render.mjs 稿子.md -o 图.png --media 附件目录` |

系统提示词是逐字比对的：改了 `SystemPrompt.kt` 不更新基准文本，测试必然失败。更新后要看一眼三个 `system-prompt-*.txt` 的差异是不是你想要的。

**要用真实模型验证功能时，起一个临时实例，不要碰真数据。** 电脑端服务认 `-Duser.home`：

```bash
JAVA_OPTS="-Duser.home=/某个临时目录" desktop/build/install/reduction/bin/reduction 8799
```

往那个临时目录的 `.config/reduction/settings.json` 放一份设置，就可以通过 `http://127.0.0.1:8799/bridge/<方法>`（请求头带 `X-Reduction: 1`）驱动它。这一轮所有“用真实模型验收”都是这么做的。

## 七、发版

```bash
tools/release/publish.sh          # 小版本号加一
tools/release/publish.sh minor    # 中版本号加一
tools/release/publish.sh major    # 大版本号加一
```

它改版本号、跑测试和 lint、构建、从上一版生成补丁、上传、最后替换清单并用 App 自己的更新代码检查线上文件。清单最后才换，之前任何一步失败都不会发出去。

- 更新服务器的地址在 `local.env` 里，三个 `REDUCTION_UPDATE_*`。
- 它需要 `dist/` 里有上一版的安装包来生成补丁。`dist/` 不入库。
- 发完要把版本号文件的改动提交掉（`app/build.gradle.kts`、`build.sh`、`AssetUpdater.kt`、`docs/DEVELOPMENT.md`）。这一步脚本不做。
- 电脑端没有版本，改完跑 `desktop/install.sh` 就是发了。

## 八、数据和日志在哪

| 东西 | 位置 |
|---|---|
| 电脑端的对话、卡片、待办、任务存档 | `~/.local/share/reduction/` |
| 电脑端设置（含 API Key） | `~/.config/reduction/settings.json` |
| 电脑端服务日志 | `~/.local/share/reduction/desktop.log` |
| 手机连着电脑时写过来的运行日志 | `~/reduction-agents/logs/phone.log` |
| 手机每次说话的录音和识别结果 | `~/reduction-agents/recordings/` |
| sub agent 的工作目录 | `~/reduction-agents/<文件夹名>/` |
| 出好的文章长图 | `~/reduction-agents/articles/` |
| 桌宠的位置、面板大小、快捷键配置 | Windows 的 `%LOCALAPPDATA%\Reduction\` |

手机行为不对时先读 `phone.log` 和对应的录音，不要猜。

## 九、容易踩的坑

这些都是真的发生过的。

- **电脑端服务不重启就会用半新半旧的代码。** 重新构建后旧进程还在跑，请求会无声失败，表现为“说完话什么都没弹出”。`install.sh` 现在会重启它；手动构建后要自己重启。
- **页面里放内容的占位符，不要写进它自己的注释。** 文章模板的注释里出现过 `{{body}}`，正文被填进了注释，长图是空的。模板和 `builder.html` 里的占位符只能各出现规定的次数，代码会检查。
- **回传图片的文件名只能是字母、数字、点、横线、下划线。** 中文名会被解析丢掉，助手只拿到路径看不到图。
- **清洗 HTML 的库会去掉它不认识的地址和元素。** `media:` 这种自定义地址、SVG 的箭头标记都被它去掉过。附件地址要在清洗前换成真地址；画箭头直接画三角形。
- **改卡片页脚本时不要用“从某处删到某处”的批量删除。** 有一次多删了十几个函数。删完要核对函数清单，跑预览脚本。
- **工具的回答只能是文字。** 要给助手看图，图得放在紧跟其后的一条消息里；机制已经有了（`ChatTool.pictures()`），新工具照着用。
- **对话模型要能看图。** `deepseek-flash` 能；`deepseek-v4-pro` 实测看不到图，会说“图片不可见”。
- **验证界面时只渲染程序自己的窗口，不要截屏幕。** 截屏会把用户屏幕上别的东西带进来。
- **写用户的卡片之前先读、先备份、写时核对没被改过。** 用户可能正在同时编辑。

## 十、用户的工作方式

这个项目是用户和编程助手一起做的，下面是合作里已经定下来的习惯。

- **文档写成现状，不写变更史。** 不出现“不再”“改成”“新增”这类说法。
- **验收要用真的东西。** 真模型、真渲染出来的图、真跑过的命令。没验证的部分要明说。
- **不要自作主张加复杂度。** 用户否决过两次：给长录音做磁盘缓存；给卡片做历史留底。他的原话是“自己实现一堆 bug”。
- **确定性的事做成直接工具，不派 sub agent。** 出长图就是这么定的。
- **密钥、服务器地址、个人路径不进仓库。** 都在 `local.env` 和 `keystore.properties` 里。
- **图要有设计，不是把东西摆正。** 文章配图返工了四次，最后定的是黑白、反白色块、粗直线箭头、大号编号。
- 用户的话常是语音转写的，会有同音错字和半截句子，按意思理解。

## 十一、没做完的和没验证的

**没在真机上验证过的**（代码有测试，但没有在手机上跑过）：

- 手机发起的数据同步。电脑一侧用模拟数据验证过，手机一侧通过 SSH 调电脑服务的那段没跑过。
- 输入框的拍照和相册选择、剪贴板粘贴图片。
- 手机上往卡片里挂 PDF 并渲染成页面，尤其是大文件的速度和内存。
- 待办的早晨提醒通知。

**没法由程序验证、需要人按一下的**（电脑端）：

- 按住空格说话、按住 Ctrl+空格口述。全局按键钩子收不到程序模拟的按键。
- 面板拖边改大小、F11 全屏。

**有意留着的限制：**

- 每轮在设备上比较递增窗口和最近 20 条消息的预计输入费用，发送较省的一份。选中最近 20 条后，下一轮从这 20 条继续增长。对话里的图只留在内存里，重启后助手看不到。
- 录音和视频能挂在卡片上播放，助手读不到内容。
- 文章长图只能在电脑上出；手机上只有预览。预览和长图是同一个页面，但手机屏幕上的预览不会和电脑出的图逐像素相同。
- 同一张卡片两端都改过时，整张取后改的，不做逐行合并。
- 「sub agent 任务」卡上的目标和结果是从原文里取的第一句话，不是模型写的摘要。

**用户提过、还没定的：**

- 文章里要不要有“某个标记之后的内容不进长图”的约定，好把发布备注留在原稿里。
- 文章配图里的颜色是写死在图里的，换模板强调色时不会跟着变。

## 十二、接手后先做什么

1. 跑一遍第六节的四条验证命令，确认环境是好的。
2. 发一版手机（第七节），把分片读取带上去。
3. 在真机上过一遍第十一节“没在真机上验证过的”四项，先从数据同步开始：连上电脑，在手机上记一条待办，看一分钟内电脑面板的「计划」卡片里有没有。
4. 读 `docs/DEVELOPMENT.md` 的“代码结构”和“电脑端”两节。
