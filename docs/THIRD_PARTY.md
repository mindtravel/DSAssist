# 用到的第三方组件

仓库里直接带着或在构建时取用的别人的东西。各自的许可证以其上游为准；下表是整理时的理解，发布前请对照上游核实。

| 组件 | 用在哪 | 上游 | 许可证 |
| --- | --- | --- | --- |
| sherpa-onnx（`app/libs/sherpa-onnx-arm64.aar`，不入库，`tools/models/fetch-models.sh` 取） | 本地唤醒词、结束词、人声检测的运行库 | k2-fsa/sherpa-onnx v2.0.1 | Apache-2.0 |
| 关键词模型（`assets/kws`、`assets/kws-en`，不入库，同上） | 认“完毕”和“Hi Reduction” | sherpa-onnx 发布的 zipformer KWS 模型（wenetspeech mobile、gigaspeech） | Apache-2.0 |
| Silero VAD（`assets/vad/silero_vad.onnx`，不入库，同上） | 判断有没有人在说话 | snakers4/silero-vad | MIT |
| marked | Markdown 渲染 | markedjs/marked | MIT |
| DOMPurify | 渲染前清洗 HTML | cure53/DOMPurify | Apache-2.0 或 MPL-2.0 |
| KaTeX | 数学公式 | KaTeX/KaTeX | MIT |
| PDF.js 4.10.38 | 把卡片里的 PDF 渲染成逐页图片（`vendor/pdfjs`，含字符映射和标准字体） | mozilla/pdf.js | Apache-2.0 |
| mermaid（`docs/mermaid.min.js`） | 架构图里的状态图 | mermaid-js/mermaid | MIT |
| OkHttp | 网络请求 | square/okhttp | Apache-2.0 |
| JSch（mwiede 分支） | SSH | mwiede/jsch | BSD |
| Apache Commons Compress | 解更新包 | apache/commons-compress | Apache-2.0 |
| org.json、kotlinx.coroutines、AndroidX | 基础库 | 各自上游 | 见各自上游 |
| DeepSeek V4 分词表（`deepseek/v4.bpe.gz`） | 手机和电脑在发送前计算文本 token。`tools/models/prepare-tokenizer.py` 从官方压缩包重建词表 | [DeepSeek 离线分词器](https://api-docs.deepseek.com/zh-cn/quick_start/token_usage/) | [MIT](../app/src/main/resources/deepseek/LICENSE.txt) |

随包自带的提示音里，“好的”和几句失败提示是用开源中文语音模型（piper 的 huayan）合成的备用录音；乐音是程序生成的。
