package com.bluetooth.phone.core

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import com.bluetooth.phone.api.PhoneCallState

/**
 * 只负责本地铃声。
 * HFP 支持带内铃声（isInBandRing）时必须静音本地铃声，避免双响；
 * 第二路来电只用单次提示音，不循环轰炸正在通话的乘客。
 */
internal class RingtonePlayer(context: Context) {

    /** 应用上下文，避免持有 Activity。 */
    private val appContext = context.applicationContext

    /** 当前播放的铃声实例。 */
    private var ringtone: Ringtone? = null

    /** 是否正在响铃。 */
    private var ringing = false

    /** 当前铃声是否循环播放。 */
    private var looping = false

    /** 根据最新通话状态开始/停止本地铃声；允许从引擎线程外调用。 */
    @Synchronized
    fun update(state: PhoneCallState) {
        val call = state.incoming ?: state.waiting
        val shouldRing = call != null && !call.isInBandRing
        // 主叫来电循环响铃；等待来电只响一轮。
        val shouldLoop = state.incoming != null && !state.incoming.isInBandRing
        when {
            shouldRing && (!ringing || looping != shouldLoop) -> {
                PhoneLog.audio.d { "本地铃声开始, loop=$shouldLoop" }
                start(shouldLoop)
            }

            !shouldRing && ringing -> {
                PhoneLog.audio.d { "本地铃声停止" }
                stop()
            }
        }
    }

    /** 停止并释放当前铃声。 */
    @Synchronized
    fun stop() {
        ringing = false
        looping = false
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    /** 开始播放系统默认铃声，[loop] 控制是否循环。 */
    @Synchronized
    private fun start(loop: Boolean) {
        stop()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: run {
            PhoneLog.audio.w { "没有可用的系统默认铃声" }
            return
        }
        val tone = RingtoneManager.getRingtone(appContext, uri) ?: run {
            PhoneLog.audio.w { "无法创建铃声实例" }
            return
        }
        tone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        tone.isLooping = loop
        runCatching { tone.play() }.onFailure {
            PhoneLog.audio.w(it) { "铃声播放失败" }
            return
        }
        ringtone = tone
        ringing = true
        looping = loop
    }
}
