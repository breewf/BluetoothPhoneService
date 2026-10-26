package com.example.bluetoothphone.core

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import com.example.bluetoothphone.api.PhoneCallState

/**
 * 只负责本地铃声。
 * HFP 支持带内铃声（isInBandRing）时必须静音本地铃声，避免双响；
 * 第二路来电只用单次提示音，不循环轰炸正在通话的乘客。
 */
internal class RingtonePlayer(context: Context) {

    private val appContext = context.applicationContext
    private var ringtone: Ringtone? = null
    private var ringing = false
    private var looping = false

    /** 根据最新通话状态开始/停止本地铃声。 */
    fun update(state: PhoneCallState) {
        val call = state.incoming ?: state.waiting
        val shouldRing = call != null && !call.isInBandRing
        // 主叫来电循环响铃；等待来电只响一轮。
        val shouldLoop = state.incoming != null && !state.incoming.isInBandRing
        when {
            shouldRing && (!ringing || looping != shouldLoop) -> start(shouldLoop)
            !shouldRing && ringing -> stop()
        }
    }

    /** 停止并释放当前铃声。 */
    fun stop() {
        ringing = false
        looping = false
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    /** 开始播放系统默认铃声，[loop] 控制是否循环。 */
    private fun start(loop: Boolean) {
        stop()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        val tone = RingtoneManager.getRingtone(appContext, uri) ?: return
        tone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        tone.isLooping = loop
        runCatching { tone.play() }.onFailure { return }
        ringtone = tone
        ringing = true
        looping = loop
    }
}
