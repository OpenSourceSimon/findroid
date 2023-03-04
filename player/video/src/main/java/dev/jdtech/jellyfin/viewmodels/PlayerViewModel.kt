package dev.jdtech.jellyfin.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MimeTypes
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.models.ExternalSubtitle
import dev.jdtech.jellyfin.models.JellyfinEpisodeItem
import dev.jdtech.jellyfin.models.JellyfinItem
import dev.jdtech.jellyfin.models.JellyfinMovieItem
import dev.jdtech.jellyfin.models.JellyfinSeasonItem
import dev.jdtech.jellyfin.models.JellyfinShowItem
import dev.jdtech.jellyfin.models.PlayerItem
import dev.jdtech.jellyfin.player.video.R
import dev.jdtech.jellyfin.repository.JellyfinRepository
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber

@HiltViewModel
class PlayerViewModel @Inject internal constructor(
    private val application: Application,
    private val repository: JellyfinRepository,
) : ViewModel() {

    private val playerItems = MutableSharedFlow<PlayerItemState>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun onPlaybackRequested(scope: LifecycleCoroutineScope, collector: (PlayerItemState) -> Unit) {
        scope.launch { playerItems.collect { collector(it) } }
    }

    fun loadPlayerItems(
        item: JellyfinItem,
        mediaSourceIndex: Int = 0
    ) {
        Timber.d("Loading player items for item ${item.id}")

        viewModelScope.launch {
            val playbackPosition = item.playbackPositionTicks.div(10000)

            val items = try {
                createItems(item, playbackPosition, mediaSourceIndex).let(::PlayerItems)
            } catch (e: Exception) {
                Timber.d(e)
                PlayerItemError(e)
            }

            playerItems.tryEmit(items)
        }
    }

    private suspend fun createItems(
        item: JellyfinItem,
        playbackPosition: Long,
        mediaSourceIndex: Int
    ) = if (playbackPosition <= 0) {
        prepareIntros(item) + prepareMediaPlayerItems(
            item,
            playbackPosition,
            mediaSourceIndex
        )
    } else {
        prepareMediaPlayerItems(item, playbackPosition, mediaSourceIndex)
    }

    private suspend fun prepareIntros(item: JellyfinItem): List<PlayerItem> {
        return repository
            .getIntros(item.id)
            .filter { it.mediaSources != null && it.mediaSources?.isNotEmpty() == true }
            .map { intro -> intro.toPlayerItem(mediaSourceIndex = 0, playbackPosition = 0) }
    }

    private suspend fun prepareMediaPlayerItems(
        item: JellyfinItem,
        playbackPosition: Long,
        mediaSourceIndex: Int
    ): List<PlayerItem> = when (item) {
        is JellyfinMovieItem -> movieToPlayerItem(item, playbackPosition, mediaSourceIndex)
        is JellyfinShowItem -> seriesToPlayerItems(item, playbackPosition, mediaSourceIndex)
        is JellyfinEpisodeItem -> episodeToPlayerItems(item, playbackPosition, mediaSourceIndex)
        else -> emptyList()
    }

    private fun movieToPlayerItem(
        item: JellyfinMovieItem,
        playbackPosition: Long,
        mediaSourceIndex: Int
    ) = listOf(item.toPlayerItem(mediaSourceIndex, playbackPosition))

    private suspend fun seriesToPlayerItems(
        item: JellyfinShowItem,
        playbackPosition: Long,
        mediaSourceIndex: Int
    ): List<PlayerItem> {
        val nextUp = repository.getNextUp(item.id)

        return if (nextUp.isEmpty()) {
            repository
                .getSeasons(item.id)
                .flatMap { seasonToPlayerItems(it, playbackPosition, mediaSourceIndex) }
        } else {
            episodeToPlayerItems(nextUp.first(), playbackPosition, mediaSourceIndex)
        }
    }

    private suspend fun seasonToPlayerItems(
        item: JellyfinSeasonItem,
        playbackPosition: Long,
        mediaSourceIndex: Int
    ): List<PlayerItem> {
        return repository
            .getEpisodes(
                seriesId = item.seriesId,
                seasonId = item.id,
                fields = listOf(ItemFields.MEDIA_SOURCES)
            )
            .filter { it.sources.isNotEmpty() }
//            .filter { it.locationType != VIRTUAL }
            .map { episode -> episode.toPlayerItem(mediaSourceIndex, playbackPosition) }
    }

    private suspend fun episodeToPlayerItems(
        item: JellyfinEpisodeItem,
        playbackPosition: Long,
        mediaSourceIndex: Int
    ): List<PlayerItem> {
        // TODO Move user configuration to a separate class
        val userConfig = repository.getUserConfiguration()
        return repository
            .getEpisodes(
                seriesId = item.seriesId,
                seasonId = item.seasonId,
                fields = listOf(ItemFields.MEDIA_SOURCES),
                startItemId = item.id,
                limit = if (userConfig.enableNextEpisodeAutoPlay) null else 1
            )
            .filter { it.sources.isNotEmpty() }
//            .filter { it.locationType != VIRTUAL }
            .map { episode -> episode.toPlayerItem(mediaSourceIndex, playbackPosition) }
    }

    private fun JellyfinItem.toPlayerItem(
        mediaSourceIndex: Int,
        playbackPosition: Long
    ): PlayerItem {
        val mediaSource = this.sources[mediaSourceIndex]
        val externalSubtitles = mutableListOf<ExternalSubtitle>()
        for (mediaStream in mediaSource.mediaStreams) {
            if (mediaStream.isExternal && mediaStream.type == MediaStreamType.SUBTITLE && !mediaStream.deliveryUrl.isNullOrBlank()) {

                // Temp fix for vtt
                // Jellyfin returns a srt stream when it should return vtt stream.
                var deliveryUrl = mediaStream.deliveryUrl!!
                if (mediaStream.codec == "webvtt") {
                    deliveryUrl = deliveryUrl.replace("Stream.srt", "Stream.vtt")
                }

                externalSubtitles.add(
                    ExternalSubtitle(
                        mediaStream.title ?: application.getString(R.string.external),
                        mediaStream.language.orEmpty(),
                        Uri.parse(repository.getBaseUrl() + deliveryUrl),
                        when (mediaStream.codec) {
                            "subrip" -> MimeTypes.APPLICATION_SUBRIP
                            "webvtt" -> MimeTypes.TEXT_VTT
                            "ass" -> MimeTypes.TEXT_SSA
                            else -> MimeTypes.TEXT_UNKNOWN
                        }
                    )
                )
            }
        }
        return PlayerItem(
            name = name,
            itemId = id,
            mediaSourceId = mediaSource.id,
            mediaSourceUri = mediaSource.path,
            playbackPosition = playbackPosition,
            parentIndexNumber = if (this is JellyfinEpisodeItem) parentIndexNumber else null,
            indexNumber = if (this is JellyfinEpisodeItem) indexNumber else null,
            externalSubtitles = externalSubtitles
        )
    }

    private suspend fun BaseItemDto.toPlayerItem(
        mediaSourceIndex: Int,
        playbackPosition: Long
    ): PlayerItem {
        val mediaSource = repository.getMediaSources(id)[mediaSourceIndex]
        val externalSubtitles = mutableListOf<ExternalSubtitle>()
        for (mediaStream in mediaSource.mediaStreams!!) {
            if (mediaStream.isExternal && mediaStream.type == MediaStreamType.SUBTITLE && !mediaStream.deliveryUrl.isNullOrBlank()) {

                // Temp fix for vtt
                // Jellyfin returns a srt stream when it should return vtt stream.
                var deliveryUrl = mediaStream.deliveryUrl!!
                if (mediaStream.codec == "webvtt") {
                    deliveryUrl = deliveryUrl.replace("Stream.srt", "Stream.vtt")
                }

                externalSubtitles.add(
                    ExternalSubtitle(
                        mediaStream.title ?: application.getString(R.string.external),
                        mediaStream.language.orEmpty(),
                        Uri.parse(repository.getBaseUrl() + deliveryUrl),
                        when (mediaStream.codec) {
                            "subrip" -> MimeTypes.APPLICATION_SUBRIP
                            "webvtt" -> MimeTypes.TEXT_VTT
                            "ass" -> MimeTypes.TEXT_SSA
                            else -> MimeTypes.TEXT_UNKNOWN
                        }
                    )
                )
            }
        }
        return when (mediaSource.protocol) {
            MediaProtocol.FILE -> PlayerItem(
                name = name,
                itemId = id,
                mediaSourceId = mediaSource.id!!,
                playbackPosition = playbackPosition,
                parentIndexNumber = parentIndexNumber,
                indexNumber = indexNumber,
                externalSubtitles = externalSubtitles
            )
            MediaProtocol.HTTP -> PlayerItem(
                name = name,
                itemId = id,
                mediaSourceId = mediaSource.id!!,
                mediaSourceUri = mediaSource.path!!,
                playbackPosition = playbackPosition,
                parentIndexNumber = parentIndexNumber,
                indexNumber = indexNumber,
                externalSubtitles = externalSubtitles
            )
            else -> PlayerItem(
                name = name,
                itemId = id,
                mediaSourceId = mediaSource.id!!,
                playbackPosition = playbackPosition,
                parentIndexNumber = parentIndexNumber,
                indexNumber = indexNumber,
                externalSubtitles = externalSubtitles
            )
        }
    }

    sealed class PlayerItemState

    data class PlayerItemError(val error: Exception) : PlayerItemState()
    data class PlayerItems(val items: List<PlayerItem>) : PlayerItemState()
}
