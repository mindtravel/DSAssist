# 模型和语音运行库

仓库里不放下面这几样：它们有几十 MB。clone 之后跑一次：

```bash
tools/models/fetch-models.sh
```

脚本从上游下载，逐个核对 sha256，按 App 需要的样子放好。已经有的不会重复下载（缓存在 `.models/`，不入库）。只想知道缺什么：`tools/models/fetch-models.sh --check`。

| 放到哪 | 是什么 | 上游 | 大小 | sha256 |
| --- | --- | --- | --- | --- |
| `app/libs/sherpa-onnx-arm64.aar` | 语音运行库（sherpa-onnx 2.0.1） | [sherpa-onnx v2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) 的 `sherpa-onnx-2.0.1.aar` | 47.2 MB | `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7` |
| `app/src/main/assets/kws-en/` | 唤醒词 “Hi Reduction” 的英文关键词模型 | [kws-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models) 的 `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2` | 16.8 MB | `f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a` |
| `app/src/main/assets/kws/` | 结束词“完毕”的中文关键词模型（mobile 版） | 同上，`sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile.tar.bz2` | 31.1 MB | `b812a043aef628a6915f89cb9a94e55f8e87e89ff904b516f822d7e0a3e6de2b` |
| `app/src/main/assets/vad/silero_vad.onnx` | 判断有没有人在说话的 Silero VAD v4 | 同上，`asr-models` 里的 `silero_vad_v4.onnx` | 1.7 MB | `a35ebf52fd3ce5f1469b2a36158dba761bc47b973ea3382b3186ca15b1f5af28` |
| `app/src/main/assets/tts-aishell3/` | CPU 普通话朗读，包含权重、词典及数字、日期、电话规则 | [icefall AISHELL-3 VITS](https://huggingface.co/csukuangfj/icefall-tts-aishell3-vits-low-2024-04-06) | 30.1 MiB 下载 | `ab468db3a3308cdd861495e0db2f25d79418a0c00639f74944c7cdf5dd8c6ec1` |

两个关键词模型解开后，脚本把 encoder、decoder、joiner 改名成 `encoder.onnx`、`decoder.onnx`、`joiner.onnx` 放进 App，并按 `keywords.txt` 写入要认的词。想换别的模型或别的关键词，改脚本开头那几行配置。

中文关键词模型有完整版和 mobile 版，这里用 mobile：体积小五分之一，在 111 段实际录音上认出“完毕”的次数与完整版相同（93 段），比完整版还多。它认的是拼音字母 `w án b ì`，所以“完毕”两个字写在 `keywords.txt` 的 `@` 后面只是标注。
