package com.streamvault.player.playback

import android.net.Uri

/** Optional protection supplied by the application; ordinary players are unchanged. */
interface PlaybackNetworkPolicy {
    val changes: kotlinx.coroutines.flow.Flow<Long>
    fun checkPlayback(uri: Uri)
}
