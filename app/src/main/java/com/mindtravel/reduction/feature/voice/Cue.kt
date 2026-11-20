package com.mindtravel.reduction.feature.voice

/** The short things the assistant says by itself, without the model: an acknowledgement, and why there is no answer. */
enum class Cue(val text: String) {
    OK("好的。"),

    /** The request did not get through; it is being sent again. */
    RETRYING("网络不太好，正在重试。"),

    /** The request did not get through at all. */
    NO_NETWORK("网络不通，没能回答，请稍后再说一遍。"),

    /** The answer failed for another reason. */
    FAILED("出错了，没能回答。"),

    /** Nothing intelligible was recorded. */
    NOT_HEARD("没听清，请再说一遍。")
}
