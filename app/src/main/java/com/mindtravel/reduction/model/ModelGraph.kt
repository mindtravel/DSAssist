package com.mindtravel.reduction.model

import com.mindtravel.reduction.core.audio.AudioGateway
import com.mindtravel.reduction.model.chat.ChatGateway
import com.mindtravel.reduction.model.chat.DeepSeekChatGateway
import com.mindtravel.reduction.model.chat.DeepSeekSearchApi
import com.mindtravel.reduction.model.chat.SearchGateway
import com.mindtravel.reduction.model.voice.DeepSeekAudioApi

/** The remote models: chat, search and speech recognition. */
class ModelGraph(private val http: DeepSeekHttp = DeepSeekHttp()) {

    val chatGateway: ChatGateway = DeepSeekChatGateway(http)
    val searchGateway: SearchGateway = DeepSeekSearchApi(http)
    val audioGateway: AudioGateway = DeepSeekAudioApi(http)
}
