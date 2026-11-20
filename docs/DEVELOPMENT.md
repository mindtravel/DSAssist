# Reduction 开发说明

给读代码和改代码的人：功能细节、代码结构、构建、测试和发版。产品介绍见根目录的 `README.md`。当前版本 v1.0.67。

## 交互

- **免按住模式（实验）**：说“Hi Reduction”唤醒，然后直接说话，停顿后自动发送；回答完回到待唤醒，再说话先说“Hi Reduction”。
- 底部中间麦克风：
  - 轻点：切换到文字输入并弹出键盘。
  - 长按：按住说话，松开发送（保留作为兜底）。
- 长按消息菜单出现在手指位置，上滑可直接选择“复制 / 朗读”；助手消息可朗读。
- 耳机输出时，播放/生成中可直接说“Hi Reduction”打断当前回答；手机扬声器播放时暂停唤醒词，避免 TTS 自触发。

## 功能

- 黑白平面图标：白底 + 黑色圆弧标志，无渐变
- 全局黑白 UI：黑底白字、灰阶层次，无彩色强调
- 响应优化：滚动 ASR、唤醒拉起无额外延迟、TTS 首块提前、长按 320ms
- 语音起止在手机本地逐帧判定：神经网络人声检测（Silero VAD）判断有没有人说话；说“完毕”结束一轮（本地关键词检测，不经网络，立刻生效）；中途停顿多久都不会结束，录音多长都没有上限，录音期间一直响着的乐音提醒该说“完毕”；也可点“结束”手动收尾；唤醒后 12 秒没人开口则退出。云端的实时字幕只用来显示
- DeepSeek 文字聊天、流式回复
- DeepSeek ASR 语音输入、TTS 朗读
- 流式朗读 + 连续 AudioTrack 播放 + 预合成下一句，减少短句停顿
- 音频缓存，相同文本和音色复用 PCM
- 联网搜索：DeepSeek Search API
- 计算器：本地精确计算
- 电脑连接：SSH，RSA 密钥登录，host key 固定，持久会话
- 自动更新：连接电脑时检查一次；连接期间电脑侧监听 manifest，版本变化后推送；优先应用 APK 补丁，失败回退完整 APK
- 资源热更新：JS/CSS/HTML 可下载小资源包更新，不重装 APK
- 更新推送依赖 App 与电脑 SSH 连接存活；App 被杀或断线时，下次连接补查
- 播放中打断只认唤醒词“Hi Reduction”；需要耳机输出
- sub agent：super agent 只做对话、任务卡、派发、验收；所有 sub agent 经 run_agent 一个接口派发，在电脑上的沙箱里后台运行，可随时查询进度；它们用手机设置里的 DeepSeek API Key 和模型
- 诊断日志：连接电脑期间，手机每 3 秒把运行日志追加到电脑的 `~/reduction-agents/logs/phone.log`
- 录音留存：每轮说的话、每次按住说话、每次唤醒词触发前 5 秒的音频（听到名字但没有唤醒的那几秒也留）各存成一个 WAV，识别出的文字放在同名的文本文件里；连接电脑期间送到电脑的 `~/reduction-agents/recordings/`，电脑上的文件大小核对无误后从手机删除；没连上时留在手机上，最多 300 MB，满了先丢最早的
- 会话状态：助手可以查看电脑上 Claude、codex、opencode 编程会话各自的状态（session_status）。电脑上的脚本读会话记录，只交回摘要：最后活动时间、在做什么、是否卡住或在等用户、最后一句话的开头；会话原文不离开电脑
- 工具过程展示：每次工具调用在朗读流里占一行，显示助手写的一句摘要、完成时间和状态；点这一行展开工具名、完整的输入输出和用时。派发 sub agent 时摘要会念出来，其它调用不出声
- 备忘卡片：一张卡片是一份 Markdown 文本，收一类信息、随时间累积（比如几个方向的科研想法各一张，再加一张备忘录）；每张有一句用途，助手据此判断口述的内容该改哪一张；助手像编辑文档一样改卡片：写一份 git patch 风格的补丁（patch_cards），一次可以新建、修改、删除多张，整体生效或整体不动；read_card 读全文；平时只拿目录
- 图标与启动动画：图标是三个一样大的圆，中间一个，另外两个在它身后各露出一道弧。启动时中间的圆先在，另外两个从两侧飞入、滑到它身后，拼成图标，然后缩小落进左上角的图标框。系统自带的启动页设为纯黑、不放图标，所以打开应用先看到的就是这段动画
- 唤醒词“Hi Reduction”：一口气说完，或者“Hi”之后停顿再说“Reduction”（停顿最长 3 秒），都能唤醒。单独的“Reduction”、句子里出现的“reduction”不唤醒
- 界面两页：「朗读」是流动的对话，回复全部念出来；「卡片」是固定下来的内容，列表只显示名称，点开后正文铺满整页，顶栏“复制全文”一点即复制，另可编辑、删除
- 耳机开麦：在耳机上单击（播放/暂停键）和唤醒词一样开始一轮录音，手机可以留在口袋里；录音中再单击是说完了，回答播放中单击是打断并开始新的一轮。安卓把这个键交给最近出声的应用，所以刚在别的应用里听过歌时，单击仍是那个应用的播放/暂停。耳机的“语音助手”手势同样有效。这样开始的录音走耳机麦克风
- sub agent 的结果自己回来：任务结束后，手机把结果交给助手，助手用一轮回复汇报；正在说话或回答时等到空下来再报
- 输入框收图片：文字输入栏左边的图片按钮在手机上给出“拍照”和“相册”，在电脑上打开文件选择；也可以把剪贴板里的图片直接粘贴进输入框。图片先排在输入框上方（最多 4 张，可逐张去掉），长边缩到 1600 像素，随下一条消息发出，没有文字也能发。助手拿到的是图片本身；最近的 4 张图在随后的几轮里仍然看得到。每张发来的图同时存成附件，程序分配的编号写在这条消息末尾给助手；助手要把图放进卡片，就用 `patch_cards` 在该放的位置加一行 `![说明](media:编号)`。补丁里出现程序没给过的编号会被整份拒绝。没有进任何卡片的图一小时后清掉。对话里的图只留在内存里：记录里只有那行编号说明，重启后助手看不到画面
- 卡片的附件：编辑卡片时“加附件”收图片（含 SVG，原样保存不压缩）、录音、视频和 PDF，单个不超过 20 MB。程序给每个附件一个编号，卡片正文里是一行 `![说明](media:编号)`，正文本身不含附件内容
  - PDF 挂进来时由页面里的 PDF.js 逐页渲染成 JPEG（长边 1600 像素，最多 200 页），和 PDF 存在一起。卡片里显示的就是这些页面，一页接一页；手机和电脑用的是同一套渲染。从另一台设备同步来的 PDF 连同页面一起过来；缺页面时，打开卡片会当场渲染并存下
  - 助手 `read_card` 时，卡片原文原样返回，后面附一段说明有哪些附件；图片和 PDF 页面作为画面紧跟在工具结果后面给模型（工具结果本身只能是文字，所以画面放在随后的一条“程序补充”消息里，同一轮回复内）。PDF 一次给 4 页，一次读卡最多 6 张画面；看后面的页用 `pages` 参数
  - 录音和视频只能在卡片页播放，助手看不到内容
  - 挂进来的 SVG 文件，助手 `read_card` 时拿到的是它的源码（模型不收 SVG 当图片）；要看画出来的样子，用 `render_article` 出图
- 文章：任何一张卡片都可以排进文章模板，看它发布时的样子，出成发布用的长图。卡片的 Markdown（或 HTML）是唯一的来源，排版只读它，不写回
  - **排版只有一处代码**：`app/src/main/assets/article/build.js` 的 `ArticleBuild.page()`。卡片里的预览、助手的出图工具、命令行出图，用的都是它和同一份模板，所以三处是同一个页面
  - **模板**：自带的是 `article/template.html` 和 `article.css`，版心 540 像素宽，按两倍出图，长图宽 1080 像素；标题取正文第一个 `# ` 标题。有卡片「文章模板/template.html」「文章模板/article.css」时用卡片里的，删掉卡片就回到自带的。模板里 `{{style}}`、`{{body}}` 各一处，`{{title}}` 两处，不满足时退回自带模板并说明
  - **SVG**：正文里直接写的 `<svg>…</svg>`、标成 svg 的代码块、挂进卡片的 .svg 文件，都画成图。图里的脚本和事件属性在渲染前清掉，`foreignObject` 不收。别的语言的代码块里出现的 `<svg>` 照旧是代码。卡片的原文视图和助手回复里同样如此
  - **预览**：卡片阅读时的“预览”按钮；再点“原文”回到正文。打开卡片时总是原文；卡片被改动时预览跟着重排
  - **出图**：`ArticleBuilder` 把库、排版脚本、模板、样式（公式字体内嵌）和稿子连同图片装进一个自足的页面（`article/builder.html`），交给电脑上的 `harness/render-article.sh`，由它用 Chrome 的命令行无头模式画出来。电脑上只需要 Chrome 或 Chromium。图存在 `~/reduction-agents/articles/<卡片名>.png`，旁边是画它用的页面
  - **助手的工具 `render_article`**：`card` 指定卡片，几秒出图，画面随工具结果给助手看（长图给开头一段）；`action=template` 把自带模板写成那两张卡片，已有的不覆盖。助手改版式就是用 `patch_cards` 改这两张卡片再出图。连着电脑时才有这个工具
  - **命令行**：`node tools/article/render.mjs 稿子.md -o 长图.png [--media 附件目录] [--html 页面.html] [--template 模板 --style 样式]`，走的是同一个页面和同一个脚本。同一份稿子两次出图逐字节相同
- 工具结果不截断：任何工具的结果超过 6000 字时，程序把它按行切成片，整份存着，先给助手第一片，末尾注明“长结果 N、共多少字、分几片”。助手用 `read_more` 取其余的：`piece` 按片号（可以写 2-4），或 `find` 按关键词取出同时包含它们的片；一次最多给两片的量，没给完的片会点名。存最近 12 个长结果，App 重启后清空
  - 各工具自己不再截断：读卡片、搜卡片（每个命中、每行全文）、sub agent 的进度和结果、任务存档里的任务卡和结果、会话状态、联网搜索，都是全文
  - 电脑上的 `run-agent.sh status` 返回完整的执行记录和结果文件
- 卡片搜索：助手的 `search_cards` 工具按关键词搜所有卡片的正文，返回卡片名、命中行的原文、行号和所在小节（最近的一个标题）。多个关键词用空格分开，默认一行里全都出现才算，`match=any` 是出现任意一个；`folder` 只搜某个文件夹及其子文件夹；不分大小写；名称里含关键词的卡片另列一行
- sub agent 的结果怎么回到助手手里：电脑上的 `harness/run-agent.sh status` 打印一段文本——`STATE` 行、执行过程的事件、`=====RESULT FILE=====` 之后是结果文件，再之后每张图一段 `=====IMAGE 文件名 类型=====` 加 base64。`AgentHarness.parse` 把它读成 `Progress`（文字和图片分开）；`RunAgentTool.report` 拼出给助手的文字；`AgentWatch` 每 15 秒查一次，任务结束就把它作为一条“【任务 N 已结束】”的消息交给 `Conversation.submit`，图片挂在这条消息上，以 `image_url` 的形式和文字一起发给模型
  - sub agent 把要给人看的图放进 `~/.reduction-result/`（png、jpg、webp、gif，最多 4 张、每张不超过 3 MB），助手拿到的是图片本身。图片只用于这一轮回复，不进对话历史，也不进任务存档
  - 助手自己用 `status` 查到任务结束时，工具的回答只有文字，图片由下一次巡查单独补发一条消息
  - 看图要求对话模型支持图片输入：`deepseek-flash` 支持
- sub agent 任务记录：卡片「sub agent 任务」由程序直接写入，每个任务一节：状态、派发时间、用时、一句目标、一句结果。这两句取自任务卡和结果的第一句有内容的话，不经过模型。助手和界面都改不了它，也删不掉。任务的全过程（任务卡原文、结果全文、改动的文件）存在程序的任务存档里，卡片上不显示，助手平时不看；用户要看某个任务的原文时，助手用 `run_agent` 的 `record` 取出来
- 卡片文件夹：名称里的每个“/”都是一层文件夹，层数不限，比如“产品/开发排期/紧急项”；卡片页按层级画成树，每层逐级缩进，文件夹显示它里面一共有几张卡片，每一层可以单独折叠。打开一张卡片时，它出现在“朗读 / 卡片”那一行的下面，这一行不动：路径接在“卡片”后面同一行（“卡片 › 全部 › 产品 › 开发排期 › 紧急项”）。两个标签只切换页面，不改变各页的状态：点“朗读”去对话，卡片保持打开，再点“卡片”回到它。回列表点路径里的“全部”，点某个文件夹则回到列表并展开它。路径太长时可以横向滑动，默认露出末尾。输入框只有一个，在两个页面下面共用：打了一半的字、待发的图片、正在录的音，切换页面时都留着；编辑卡片时它让出位置。锁定、复制全文、编辑这些按钮在路径这一行下面。
- 卡片里的图片和录音：编辑卡片时点“加图片或录音”，从手机或电脑里选一个；图片会缩到长边 1600 像素。卡片正文里它是一行 `![说明](media:编号)`，阅读时图片直接显示、录音可以播放。没有任何卡片再引用的图片和录音会被清掉。助手看不到它们的内容，改卡片时会原样保留这一行
- 悬浮卡片：在卡片阅读页点“悬浮”，离开应用后这张卡片浮在其它应用上面，平时是一个写着卡片名的小条，点一下展开正文，可以拖到任何位置，点“×”收起；卡片内容变了它跟着变。需要先在系统设置里允许“显示在其他应用上层”，应用会带你到那个页面
- 响度一致：每一段朗读和“好的”都归一到同一响度再播放；“好的”用当前朗读音色合成一次后留在手机上，没网时用随包自带的那份
- 电量提醒：手机或已连接的蓝牙耳机电量降到 20% 时，助手在回答之后提醒一句；充过电再降下来才会再提醒。耳机不上报电量时不提醒
- 输入清洗：助手先去掉语气词、合并重复、拼起零散的句子，再判断意图；有听不懂的部分就复述整体的理解回问一次
- 不静默：提问后请求没发出去会自动重发，共三次，第一次重发时说“网络不太好，正在重试”；三次都不通就说“网络不通，没能回答，请稍后再说一遍”。没听清、出错、麦克风打不开也各有一句。这些话预先备好，没有网络也能播：用当前音色合成后留在手机上，合成不了时用随包自带的录音
- 录音提示：开麦的同时响起一个微弱、缓慢起伏的和弦（C 六九和弦，重心放在中低音：C3、G3 打底，C4、E4、G4 是主体，D5、A5 只留一点亮色；立体声，每个音由左右两根相差极小的弦叠成，并在两耳之间缓缓游走；一道响度的波峰每四秒从低音走到高音），它持续就表示麦克风仍在收音，录音中断时它会自己停下；每次关麦播“好的”（说完、没人说话、取消、出错都一样）
- 待办与计划：底层是一个备忘录插件（`feature/memo`），待办和时间只存在它那里，界面和助手都只通过它读写
  - 用户说的是待办就登记成待办，走助手唯一的 `todo` 工具；带日期的落进时间轴，相对日期（“明天”“三天后”）在写入时按当天日期解析成具体日期
  - 没时间的待办挂在最上面，用户当天第一次开口时由助手提一次，请用户补日期
  - 审阅卡片「计划」由程序从插件写出来，分今天、未来、没日期三块，日期可以不填，这时它在程序里是 `empty`：程序把它视同明天发生，每天早上提醒一次，提醒语是“这些没有标记时间，你要补齐吗？”，直到填上日期。`empty` 的含义只在程序层，助手的提示词和工具说明里没有它；每条显示内容、时间和状态；点一下完成，再点一下重新打开。已完成只移出提醒，记录保留
  - 待办精确到天，日期之外有一个可空的时段：早上、上午、下午、晚上。不填就是当天；时段背后没有钟点。用户说了几点，写在待办的内容里
  - 提醒走系统闹钟，每天早九点一次，只说当天的事：当天到期的，以及过期未完成的（每天一次，直到完成或改期）
  - 精确闹钟和通知都是特殊/运行时权限。缺哪一项，卡片顶部直接写出降级情况（可能延迟、看不到弹窗、被杀后不保证提醒），并给出去授权的入口，不承诺做不到的事
- 流式朗读：首段在第一个逗号处切出先合成，之后按句切，单段最多 200 字，播放时预合成后续片段
- 免按住模式：
  - 前台服务 + 常驻通知
  - 手机麦克风常驻监听“Hi Reduction”
  - 唤醒后自动录音，说“完毕”或点“结束”时结束；录音没有时长上限
  - 麦克风只由唤醒词或在耳机上按一下开启：回答播完、没听清、取消之后都回到待唤醒
  - 通知栏可停止监听
  - 开启时弹出系统的“忽略电池优化”授权；允许后锁屏也不会被系统休眠
  - 麦克风被系统收回时（服务在后台被重启、被别的应用占用），通知栏显示“麦克风被系统收回，点此恢复监听”，回到 App 即恢复

卡片平时在后台整理。用户想回顾某块内容时，助手用 `open_card` 打开对应卡片，回复中的卡片链接也可以直接打开它。编辑时保留草稿，保存或退出编辑后可以从回复再次打开。

## 安装

1. 把 `Reduction-v1.0.67.apk` 传到手机。
2. 允许安装未知应用，点 APK 安装。
3. 首次打开填入 DeepSeek API Key，模型选 `deepseek-flash`，选音色，允许麦克风权限。

APK 约 38MB，只包含 `arm64-v8a`，适用于现代安卓手机。最低 Android 8.0（API 26）。

## 免按住模式怎么用

1. 设置 → 打开“免按住模式（实验）” → 保存。
2. 系统会请求通知权限，允许。
3. 通知栏出现“正在监听‘Hi Reduction’”。
4. 说“Hi Reduction”，听到乐音后直接说话。
5. 停顿约 1.5 秒后自动识别并回答。
6. 回答完自动回到唤醒词监听；继续说话先说“Hi Reduction”。回答播放中说“Hi Reduction”可以打断并开始新的一轮。
7. 通知栏“停止监听”可以关闭常驻监听，同时在设置里会关闭该开关。

国产系统的后台策略普遍激进，需要设置（以荣耀 MagicOS 为例）：

- 设置 → 应用 → 应用启动管理 → Reduction → 关闭自动管理 → 允许自启动、关联启动、后台活动
- 设置 → 电池 → 耗电管理 → 允许后台高耗电/不限制
- 最近任务里锁定 App，关闭省电模式

否则系统可能在锁屏或一段时间后杀掉常驻服务。

## TTS 连续播放


## Markdown 与数学

- 助手回复按 Markdown 渲染，支持标题、列表、代码块、表格、链接。
- 裸网址会显示为域名/短路径链接，完整 URL 放在 href/title 中，不直接展示。
- 数学公式使用 KaTeX 渲染，支持 `$...$`、`$$...$$`、`\(...\)`、`\[...\]`。
- TTS 会先剥离 Markdown 标记、代码块、数学块和网址，再朗读纯文本。
- UI 预览和模拟测试：`tools/ui-preview/README.md`。

## 已知限制

- 播放中打断只在检测到耳机/蓝牙耳机输出时启用；手机扬声器播放时唤醒词会暂停，避免 TTS 自触发，可点麦克风或使用耳机打断。
- 打断检测触发后，当前回答可能只显示了一部分；这是预期行为。
- 语音结束后用最新滚动 ASR 结果发 LLM；手动“结束”会补一次最终整段 ASR。滚动 ASR 会重复识别增长中的整段音频，流量/费用高于流式接口。
- 没说“完毕”时，在手动“结束”时收尾；录音不会自己因时长而结束。
- 唤醒词使用**手机麦克风**常驻；设置里开启“耳机麦克风（实验）”后，唤醒后的录音会尝试走蓝牙耳机 SCO，失败自动回退手机麦克风。
- 蓝牙耳机麦克风需要授予“附近的设备/Bluetooth”权限；不同荣耀/耳机组合的 SCO 行为可能不同。
- 普通 App 无法做系统级锁屏热词；如果 App 进程被系统杀掉，唤醒会失效。
- 荣耀 MagicOS 的后台策略比较激进，必须做电池白名单，仍可能需要真机调。
- 唤醒词准确率受距离、噪声、口音影响；误唤醒可以在设置里关闭免按住模式。
- DeepSeek ASR 不是流式接口，不能返回逐字结果；这里用 1 秒间隔的滚动整段识别。
- DeepSeek TTS 只有 `stella`、`tide` 两个音色。
- 联网搜索和计算器默认开启；搜索会产生 DeepSeek Search API 的额外费用。
- 音频缓存存在 App 缓存目录，系统可能在空间不足时清理。
- API Key 存在手机本地，请使用单独的低额度 Key。
- SSH 默认是公钥登录；首次连接会信任并固定 host key 指纹，未做人工指纹确认。
- PC 命令默认是 raw shell，需要二次点击确认；自动更新只在连接电脑期间监听。
- opencode 写入当前是任务级确认；严格 per-tool 审批还需要 opencode 权限客户端支持。

## 对话上下文与缓存计价

- 每轮聊天先在本机给两份输入计价。一份从上轮选中的第一条消息开始增长，另一份取含本次提问在内的最近 20 条用户或助手消息。以一个缓存命中 token 的价格为单位，预计费用为 `命中数 + 50 × 未命中数`。费用相同就沿用递增窗口，短窗口胜出就从这 20 条继续增长。每轮只发送选中的输入。
- 设备保存上轮成功的初始输入前缀和窗口起点，重启后可接着比较。工具调用、参数和结果仅在本轮使用。聊天页与设备同步保留最近 100 条，模型窗口可超过 100 条。诊断日志的 `Context budget` 行列出两个候选的输入量、预计命中率、费用和选择结果。
- 文字使用随包的 DeepSeek V4 分词器。命中估算计入能复用的上轮输入或先前观察到的公共前缀，图片按每张最多 1024 token 估算。服务端缓存会过期，实际命中量以响应的 `usage.prompt_cache_hit_tokens` 和 `usage.prompt_cache_miss_tokens` 为准。[官方分词说明](https://api-docs.deepseek.com/zh-cn/quick_start/token_usage/) · [上下文硬盘缓存](https://api-docs.deepseek.com/zh-cn/guides/kv_cache/)

## 电脑连接

手机通过 SSH 连到你自己的电脑：派发 sub agent、回传日志和录音都走这条连接。

1. 电脑上运行 OpenSSH Server。手机和电脑不在同一个网络时，自己解决可达性：内网穿透、反向隧道或组网工具都可以。
2. App 里点右上角电脑图标，“复制公钥”，把公钥加入电脑上 `~/.ssh/authorized_keys`。
3. 填电脑的地址、端口和 SSH 用户，点“连接测试”。首次连接会固定电脑的 host key，之后对不上就拒绝连接。

## 代码结构

四层结构，只允许向下调用、向上发事件。架构图：`docs/interaction-states-simple.html`；agent 分工与判断准则：`docs/agent-principles.md`。

```text
ui/                    UI 层：只画状态、只发意图
  activity/            MainActivity（Android 宿主）
  bridge/              AndroidBridge（JS facade）、VoiceBridge、ChatBridge、
                       SettingsBridge、PcBridge、UpdateBridge、SystemBridge、JsEmitter
  di/                  AppGraph（界面自己的接线，挂接到 AppCore）
  overlay/             CardOverlay（浮在其它应用上面的卡片）

app/                   应用核心：随进程存活，不依赖界面
  AppCore              组装硬件、模型、功能三层；唤醒词直接由它应答
  UiLink               核心眼里的界面：在就转发事件、请求授权；不在就丢弃事件、拒绝需要点按的请求
  ReductionApp           进程启动时创建核心

feature/               功能层：决定什么时候做什么；状态机在这里
  voice/               VoiceMachine（免按住状态机，纯函数：状态 + 事件 → 新状态 + 要做的事）
                       VoiceRuntime（执行器：照清单办事，把结果作为事件送回）、VoiceState
                       AckCue（提示音播放与超时）、SpokenCues（预先备好的几句话：“好的”和各种没能回答的原因）
                       TurnListener、TurnRecorder、TurnEndpointer、EndWords（一轮用户语音）
                       ReplyStreamer、SentenceSplitter、SpeechTextExtractor（流式朗读）
                       SpeechController、PushToTalkSession、RecordingController
  voice/wake/          WakeWordService、WakeWordDetector、PausedPhrase（带停顿的唤醒词）、WakeWordBus
  chat/model/          ChatSession、ChatEngine、ChatCallbacks、ToolCallAccumulator
  chat/model/tools/    ToolRegistry、ChatToolFactory、AgentHarness、RunAgentTool 与 AgentRuns（沙箱里的 sub agent；任务编号由手机分配和记住）、
                       SessionStatusTool（电脑上的会话状态）、CardTools（备忘卡片）、WebSearchTool、CalculatorTool、
                       AgentLedger（把任务记录按固定版式写成卡片）
  chat/ui/             Conversation（对话本身：历史、请求、说的和打字的都走它）、ChatController、ReplyObserver、
                       AgentWatch（sub agent 结束后把结果送进对话）
  chat/model/          SystemPrompt（系统提示）
  chat/data/           HistoryRepository、CardRepository、CardPatch（卡片补丁的解析与应用）
  pc/ui/               PcController
  update/ui/           UpdateController
  settings/data/       SettingsRepository

model/                 模型层：输入进、结果出，无状态
  DeepSeekHttp         HTTP / SSE 传输与错误映射
  ModelGraph           远端模型组装点
  chat/                ChatGateway、DeepSeekChatGateway、ChatStreamParser、
                       SearchGateway、DeepSeekSearchApi、ChatModel（消息与请求类型）
  voice/               DeepSeekAudioApi（ASR / TTS）、RollingAsr、
                       TtsSynthesizer、TtsCache、VoiceDetector（Silero VAD）
  wake/                KeywordSpotterModel（sherpa-onnx：英文模型认唤醒词，中文模型认结束词）

hardware/              硬件层：设备与系统的薄封装，不含策略
  audio/               PcmCapture、WavRecorder、WavEncoder、HeadsetMicController、
                       AudioQueue（唯一的播放队列）、AudioTrackSink（唯一的输出流）、PcmPlayer（朗读入队）、WavPcm、
                       RecordingArchive（等待送往电脑的录音）、Loudness（响度归一）、MediaButtons（耳机播放键）
  power/               DeviceBattery（手机与蓝牙耳机电量）
  pc/                  SshPcClient、SshKeyStore、PinnedHostKeyRepository、PcGateway
  update/              UpdateClient、UpdateInstaller、AssetUpdater、BsPatch
  web/                 HotAssetPathHandler

core/                  各层共用的接口与基础类型
  audio/AudioGateway   语音识别 / 合成接口
  events/EventSink     事件出口接口
  host/Hosts           权限 / 音频输出 / 更新 Host 接口
  settings/            AppSettings、SettingsGateway
```

前端是 UI 层的一部分，`index.html` 只保留 DOM，CSS 在 `assets/css`：

```text
assets/js/app.js                       Native 事件入口与 DOM 事件绑定
assets/js/core/bridge.js, state.js
assets/js/features/chat/chat.js, history-store.js   显示对话；历史和请求都在 App 里
assets/js/features/cards/cards.js       卡片页：名称列表、整页阅读与编辑
assets/js/features/voice/voice.js      渲染 onVoiceState；按住说话
assets/js/features/settings/settings.js, settings-store.js
assets/js/features/pc/pc.js
assets/js/ui/ui.js, markdown.js, splash.js（启动动画落进图标框）
```

免按住语音的桥接协议：

| 方向 | 名称 | 含义 |
| --- | --- | --- |
| App → JS | `onVoiceState(state)` | 状态变化：`IDLE` `PREPARING` `LISTENING` `TURN_ACK` `REPLYING` |
| App → JS | `onVoicePartial(text)` | 聆听中的实时字幕 |
| App → JS | `onReplyBegin(id, text)` | 一次新的问答开始了（说的或打字的），界面画出用户气泡和回复占位 |
| JS → App | `submitText(text)` | 提交打字或按住说话得到的文字 |
| App → JS | `onChatAborted(id)` | 回复被唤醒词打断 |
| JS → App | `voiceFinish()` / `voiceCancel()` | 结束本轮 / 退出免按住对话 |
| JS → App | `voiceState()` | 页面加载时读取当前状态 |
| JS → App | `speak(id, text)` / `stopSpeaking()` | 朗读一条消息 / 停止朗读 |

关键约束：

- 语音状态只有 `VoiceMachine` 一份，是纯函数；每次转换在日志里是一行 `Voice 状态 + 事件 -> 新状态 [要做的事]`。JS 不计时、不排队、不决定迁移。
- 对话核心不依赖界面：唤醒、录音、请求、朗读都在 `AppCore` 里，熄屏或界面被回收时照常工作；界面回来后读取当前状态和历史。
- 所有声音（提示音、录音中的乐音、朗读）都排进 `AudioQueue`，写入同一条输出流：先入队的先响，后一段的首个采样紧接前一段的末个采样。没有第二个播放器。
- 会打断声音输出的操作（蓝牙耳机在听歌模式和通话模式之间切换）只发生在乐音响起之前和“好的”播完之后，不在任何声音播放期间。
- `VoiceMachineTest` 逐条检查每个转换，并用任意事件顺序验证：每次开始的录音恰好以一个“好的”结束，空闲时一定在听唤醒词。
- 回复文本由 App 同时送给界面和朗读，JS 不参与朗读分句。
- 回复只有一种：要念出来的话。App 把它整条朗读，界面原样显示；需要留下来看的证据由助手写进卡片。每个工具都多带一个 `summary` 参数，由助手填写，是界面上那一行摘要的来源。系统提示在 `feature/chat/model/SystemPrompt.kt`，改动后用 `REDUCTION_APPROVE_PROMPTS=app/src/test/resources` 跑 `SystemPromptTest` 更新参考文本，diff 即审阅对象。
- 下层不 import 上层：`hardware/` 与 `model/` 不依赖 `feature/`、`ui/`。
- sub agent 经 `assets/harness/run-agent.sh` 在电脑上的 Bubblewrap 沙箱里运行。电脑需要 `bwrap` 和 Python 3。沙箱验收可运行 `python3 tools/agent-check/directories.py`。
- 会话状态由 `assets/harness/session-status.py` 在沙箱之外提取，只读脚本开头 `SOURCES` 列出的三处会话记录；电脑需装有 `python3`。
- SSE 中 `content: null` 不会变成字符串 `"null"`。
- 工具参数只接受字符串片段，缺 id/name 会直接失败。
- 协议层不把 `JSONObject` 传给业务层。
- 资源热更新包的 `assetsMinVersionCode` 必须不低于 98（当前桥接协议的起始版本），否则不会被使用。
- 设置升级会自动从旧版本迁移，不会丢 API Key。

测试：

```bash
./gradlew testReleaseUnitTest          # Kotlin 单测
cd tools/ui-preview && node run.js     # 前端截图 + 免按住状态渲染检查
```

发版（需要在 `local.env` 里填好更新服务器，见 `local.env.example`）：

```bash
tools/release/publish.sh          # 小版本号加一，例如 1.0.62 -> 1.0.63
tools/release/publish.sh minor    # 中版本号加一：1.1.0
tools/release/publish.sh major    # 大版本号加一：2.0.0
tools/release/publish.sh 1.4.0    # 指定版本号
```

它改版本号、跑测试和 lint、构建、从线上版本生成补丁、上传、最后用 App 自己的更新代码验证线上文件。清单 `manifest.json` 最后才替换，之前任何一步失败都不会发布出去。更新服务器只需要能静态提供三个文件：APK、补丁、`manifest.json`。不填更新地址构建出来的 App 不检查更新。

单测覆盖 SSE null、工具调用分片、工具循环、消息序列化、计算器、结束词检测、朗读文本清洗、朗读分句、提示音保证、工具摘要、会话状态脚本、录音留存等。

## 电脑端

电脑上的 Reduction 是同一个对话核心：对话、提示词、工具、卡片、sub agent、朗读，编译的就是手机端那份代码（`desktop/build.gradle.kts` 直接引用 `app` 里不依赖安卓的源文件）。它在本机起一个只听 `127.0.0.1` 的小服务，界面是手机端那套网页，由桌宠的面板来画；在任意浏览器里打开 `http://localhost:8780` 也是同一个界面。

```bash
desktop/install.sh           # 装成应用：构建、取音频工具、放桌面图标、设开机自启
desktop/install.sh --remove  # 去掉桌面图标和开机自启（数据保留）
./desktop.sh                 # 不安装，只在终端里构建并启动：http://localhost:8780
```

- **桌宠**：登录 Windows 后，桌面右下角出现一个小黑洞，中间是图标里那三个圆，始终在最上层。它身后的屏幕被真的弯曲：越靠近它，背景被拉得越弯，并被拖着绕圈。闲着时它呼吸、偶尔眨眼、两翼不时绕中间转一圈；听你说话时张开两翼发亮，想的时候两翼绕着转、背景拧得更紧，说话时中间跟着声音鼓动；服务没起来时它是睡着的。
  - **按住空格说话**，松开发送，在哪个窗口里都行，不用把鼠标移到它身上，也不用开任何窗口。松开后它头顶的回复框依次显示“识别中…”、听到的那句话、回答；回答同时从扬声器出来。轻敲空格仍是普通的空格：按下不到 0.35 秒就松开，或者空格没松开就按了别的键，都原样交给当前窗口；和 Ctrl、Alt、Shift、Win 一起按时不拦。右键菜单里的“按住空格说话”可以关掉它。
  - **按住 Ctrl+空格是语音输入法**：说的话整理成书面文字（理顺语序、去掉口头语和重复、补标点，不回答其中的问题），放进剪贴板，同时显示在回复框里，“复制”按钮显示“已复制”。它不往任何窗口里打字：贴在哪里由用户 Ctrl+V 决定；在回复框里改过之后再点“复制”，剪贴板里就是改后的。它不进对话，没有历史、卡片和工具。轻敲 Ctrl+空格仍是原来的快捷键。
  - **回复框**是桌宠头顶的一张小卡片：文字可以选中和编辑，“复制”一键复制全文，大小随内容伸缩，也可以拖右下角改大小。它出现时不抢键盘；鼠标停在上面、正在编辑或改过内容时不会自己消失，点 × 关掉。
  - **快捷键写在一个配置文件里**：`%LOCALAPPDATA%\Reduction\keys.txt`，一行一条 `动作 = 按键`，等号后留空就是没有快捷键；右键菜单“快捷键…”用记事本打开它，保存后立即生效，看不懂的行会在回复框里指出来。动作有五个：`talk`（按住说话）、`dictate`（按住口述）、`panel`（开关面板）、`close`（收起最前面的面板或回复框，只在它们在最前面时生效，其余时候这个键照常）、`quiet`（停下朗读）。默认是 Space、Ctrl+Space、Ctrl+Shift+Space、Esc，`quiet` 没有键。另有 `full`（面板全屏与卡片大小之间切换，默认 F11，只在面板在最前面时生效）。
  - **点黑色圆圈本体**弹出面板（对话、卡片、设置），再点收起。周围的折射画面不触发点击。面板是桌宠旁边的一张卡片：没有标题栏和边框，圆角，不占任务栏。默认 520×720；按住任意一条边或一个角拖动可以改大小，改过的大小记在 `%LOCALAPPDATA%\Reduction\panel-size.txt`，下次照用，最小 340×420。右上角的按钮或 F11 让它占满屏幕（任务栏留着），再按回到卡片大小；全屏时按 Esc 先回到卡片。它没有自己的拖动条：拖桌宠，它跟着走，全屏时不跟。页面由 WebView2（Windows 自带的网页内核）画在桌宠自己的窗口里，不启动浏览器；收起只是藏起来，再开是即时的。卡片里的链接在系统默认浏览器里打开。`install.sh` 从 NuGet 取 WebView2 SDK（版本和校验值写在脚本开头），把三个库放在桌宠旁边。
  - 拖到哪儿放哪儿，位置会记住；右键有菜单（让它安静、按住按键说话、快捷键…、收起桌宠）。
- **子代理目录权限**在当前端的设置中长期保存。从新任务起生效。可以为多个根设「只读」或「读写」，更具体的子目录规则优先。只读任务和关闭修改开关时，授权目录只读。用户目录须显式授权，Git worktree 的外部元数据目录也适用同样的规则。
- **手机和电脑共享对话、卡片和待办**（`feature/sync`）：两边各存一份，各自离线可用；手机连着电脑时由手机发起交换——把自己的数据发给电脑上的服务，电脑并入后回答合并结果，手机再并入。手机这边有改动后约 2.5 秒交换一次，另外每分钟一次，电脑上的改动就是这样到手机的。
  - 对话按每句话的编号取并集，按说出的时间排序；一边清空，另一边同样清空。
  - 卡片逐张比较，后改的为准；删除会传过去；锁定状态跟着卡片走；卡片里的图片和声音按原编号补齐，每次交换最多传 6 MB。程序写的卡片（「计划」「sub agent 任务」）各写各的，不传。
  - 待办逐条比较，后改的为准；删除会传过去；两边各自发出过同一个编号时以电脑的编号为准，另一条换新编号。提醒记录是各设备自己的。
  - 设置（API Key、各开关、项目路径）是各设备自己的，不传。
- **声音和麦克风是电脑自己的**：朗读从系统扬声器出来，按住麦克风按钮时录的是系统默认麦克风，都由服务直接接音频系统（`pacat`），不经过窗口。在 WSL 里，这就是 Windows 的默认播放和录音设备。
- 第一次打开在设置里填 DeepSeek API Key；也可以用环境变量 `DEEPSEEK_API_KEY`。设置存在 `~/.config/reduction/settings.json`，只有本人可读。
- 打字提问，或按住麦克风按钮说话，松开发送。
- 卡片、对话历史、任务记录存在 `~/.local/share/reduction/`，和手机上的是两份；运行日志在同一处的 `desktop.log`。
- sub agent 直接在这台电脑上运行，沙箱和手机派发时是同一个脚本。
- 唤醒词、耳机手势、悬浮卡片是手机才有的，电脑端不提供。

弯曲是显卡做的：Windows 把桌面的画面直接从显卡里交出来（桌面复制，不含桌宠自己），桌宠只取自己身后那一小块，由一段像素着色器按引力透镜的方式重排。背景变化当帧跟上，桌宠自己每秒 60 帧。代价是截图和屏幕共享里看不到桌宠。`ReductionPet.exe --snapshot 文件.png --pose thinking` 把它画在一张假的网格背景上存成图片，用来检查样子。

安装做了什么：音频工具从系统的软件源下载后解到 `~/.local/share/reduction/audio/`，不装进系统；Windows 这边在 `%LOCALAPPDATA%\Reduction\` 放一个图标和一个启动器，在桌面和“启动”文件夹各放一个快捷方式。面板由桌宠用 WebView2 来画，数据放在 `%LOCALAPPDATA%\Reduction\panel\`，与平时的浏览器无关。找不到音频工具时，声音和录音退回由窗口代劳。

页面和服务之间只有两条通路：页面对服务的调用（`POST /bridge/<方法>`，就是手机页面对 App 的那些调用），和服务发给页面的事件流（`GET /events`，包括要播放的语音）。带不上页面专用请求头的请求一律拒绝，别的网站指挥不了它。

`node tools/desktop-check/run.mjs` 用一个假的模型服务把电脑端从头到尾走一遍：填 Key、打字提问、朗读、工具调用、卡片、按住说话、刷新后历史还在；装了音频工具时再走一遍系统扬声器和麦克风（会真的放出一声很轻的短音）。不花钱，不需要 Key。

## 从源码构建

需要 JDK 17、Android SDK（compileSdk 35、build-tools 35.0.0）。本机特有的东西写在根目录的 `local.env` 里（不入库），照 `local.env.example` 填：

```bash
cp local.env.example local.env     # 填 JAVA_HOME、ANDROID_HOME
./build.sh                         # dist/Reduction-v<版本>.apk
```

本地语音用的运行库和模型不入库，clone 之后取一次：

```bash
tools/models/fetch-models.sh       # 下载并核对 sha256，放到 App 需要的位置
```

要哪些东西、从哪来、怎么换成别的模型，见 [tools/models/README.md](../tools/models/README.md)。唤醒词是 `Hi Reduction`，结束词是“完毕”。

签名：自己生成一个签名文件，路径和口令写进根目录的 `keystore.properties`（不入库；字段是 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`）。没有这个文件时构建出来的是未签名的包。升级安装必须始终用同一个签名文件。

## 下一步

- 蓝牙耳机麦克风常驻唤醒
- 更低延迟：TTS WebSocket 流式合成
- 唤醒词误唤醒/功耗调优
