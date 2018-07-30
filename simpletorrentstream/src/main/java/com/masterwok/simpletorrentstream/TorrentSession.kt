package com.masterwok.simpletorrentstream

import android.net.Uri
import android.util.Log
import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.alerts.*
import com.masterwok.simpletorrentstream.contracts.TorrentSessionListener
import com.masterwok.simpletorrentstream.extensions.*
import com.masterwok.simpletorrentstream.models.TorrentSessionStatus
import java.lang.ref.WeakReference
import java.net.URLDecoder


// TODO: Add remove torrent method.
// TODO: Implement multiple torrent downloads.
class TorrentSession(
        private val torrentSessionOptions: TorrentSessionOptions
) {
    companion object {
        private const val Tag = "TorrentSession"
        private const val MaxPrioritizedPieceCount = 8
        private const val MinDhtNodes = 10
    }

    val isPaused get() = sessionManager.isPaused

    val isRunning get() = sessionManager.isRunning

    private var torrentSessionListener: TorrentSessionListener? = null
    private var bufferState = TorrentSessionBufferState(bufferSize = MaxPrioritizedPieceCount)
    private val alertListener = TorrentSessionAlertListener(this)
    private val sessionManager = SessionManager()
    private var saveLocationUri: Uri = Uri.EMPTY
    private var largestFileUri: Uri = Uri.EMPTY
    private val dhtLock = Object()


    private class TorrentSessionAlertListener(
            torrentSession: TorrentSession
    ) : AlertListener {

        private val torrentSession: WeakReference<TorrentSession> = WeakReference(torrentSession)

        override fun alert(alert: Alert<*>) {
            try {
                when (alert.type()) {
                    AlertType.DHT_BOOTSTRAP -> torrentSession.get()?.onDhtBootstrap()
                    AlertType.DHT_STATS -> torrentSession.get()?.onDhtStats()
                    AlertType.METADATA_RECEIVED -> torrentSession.get()?.onMetadataReceived(alert as MetadataReceivedAlert)
                    AlertType.METADATA_FAILED -> torrentSession.get()?.onMetadataFailed(alert as MetadataFailedAlert)
                    AlertType.PIECE_FINISHED -> torrentSession.get()?.onPieceFinished(alert as PieceFinishedAlert)
                    AlertType.TORRENT_DELETE_FAILED -> torrentSession.get()?.onTorrentDeleteFailed(alert as TorrentDeleteFailedAlert)
                    AlertType.TORRENT_DELETED -> torrentSession.get()?.onTorrentDeleted(alert as TorrentDeletedAlert)
                    AlertType.TORRENT_REMOVED -> torrentSession.get()?.onTorrentRemoved(alert as TorrentRemovedAlert)
                    AlertType.TORRENT_RESUMED -> torrentSession.get()?.onTorrentResumed(alert as TorrentResumedAlert)
                    AlertType.TORRENT_PAUSED -> torrentSession.get()?.onTorrentPaused(alert as TorrentPausedAlert)
                    AlertType.TORRENT_FINISHED -> torrentSession.get()?.onTorrentFinished(alert as TorrentFinishedAlert)
                    AlertType.TORRENT_ERROR -> torrentSession.get()?.onTorrentError(alert as TorrentErrorAlert)
                    AlertType.ADD_TORRENT -> torrentSession.get()?.onAddTorrent(alert as AddTorrentAlert)
                    else -> Log.d("UNHANDLED_ALERT", alert.toString())
                }
            } catch (e: Exception) {
                Log.e(Tag, "An exception occurred within torrent session callback", e)
            }
        }

        override fun types(): IntArray? = null
    }

    private fun createTorrentSessionStatus(torrentHandle: TorrentHandle): TorrentSessionStatus =
            TorrentSessionStatus.createInstance(
                    torrentHandle
                    , bufferState
                    , saveLocationUri
                    , largestFileUri
            )

    private fun onTorrentDeleteFailed(torrentDeleteFailedAlert: TorrentDeleteFailedAlert) {
        val torrentHandle = torrentDeleteFailedAlert.handle()

        torrentSessionListener?.onTorrentDeleteFailed(createTorrentSessionStatus(torrentHandle))
    }

    private fun onTorrentPaused(torrentPausedAlert: TorrentPausedAlert) {
        val torrentHandle = torrentPausedAlert.handle()

        torrentSessionListener?.onTorrentPaused(createTorrentSessionStatus(torrentHandle))
    }

    private fun onTorrentResumed(torrentResumedAlert: TorrentResumedAlert) {
        val torrentHandle = torrentResumedAlert.handle()

        torrentSessionListener?.onTorrentResumed(createTorrentSessionStatus(torrentHandle))
    }

    private fun onTorrentRemoved(torrentRemovedAlert: TorrentRemovedAlert) {
        val torrentHandle = torrentRemovedAlert.handle()

        torrentSessionListener?.onTorrentRemoved(createTorrentSessionStatus(torrentHandle))
    }

    private fun onTorrentDeleted(torrentDeletedAlert: TorrentDeletedAlert) {
        val torrentHandle = torrentDeletedAlert.handle()

        torrentSessionListener?.onTorrentDeleted(createTorrentSessionStatus(torrentHandle))
    }

    private fun onMetadataReceived(metadataReceivedAlert: MetadataReceivedAlert) {
        val torrentHandle = metadataReceivedAlert.handle()

        largestFileUri = torrentHandle.getLargestFileUri()
        saveLocationUri = torrentHandle.getSaveLocation()

        torrentSessionListener?.onMetadataReceived(createTorrentSessionStatus(torrentHandle))

        sessionManager.download(
                torrentHandle.torrentFile()
                , torrentSessionOptions.downloadLocation
        )
    }


    private fun onPieceFinished(pieceFinishedAlert: PieceFinishedAlert) {
        val torrentHandle = pieceFinishedAlert.handle()

        val pieceIndex = pieceFinishedAlert.pieceIndex()

        if (pieceIndex < bufferState.startIndex || pieceIndex > bufferState.endIndex) {
            // TODO: WHY IS THIS HAPPENING? FIX THIS.
            Log.e("IGNORE", "IGNORING OUT OF RANGE PIECE")
            return
        }

        bufferState.setPieceDownloaded(pieceIndex)

        torrentHandle.setBufferPriorities(bufferState)

        torrentSessionListener?.onPieceFinished(createTorrentSessionStatus(torrentHandle))
    }

    private fun onAddTorrent(addTorrentAlert: AddTorrentAlert) {
        val torrentHandle = addTorrentAlert.handle()

        torrentHandle.ignoreAllFiles()
        torrentHandle.prioritizeLargestFile(Priority.NORMAL)

        bufferState = TorrentSessionBufferState(
                torrentHandle.getFirstNonIgnoredPieceIndex()
                , torrentHandle.getLastNonIgnoredPieceIndex()
                , MaxPrioritizedPieceCount
        )

        torrentHandle.setBufferPriorities(bufferState)

        torrentSessionListener?.onAddTorrent(createTorrentSessionStatus(torrentHandle))

        addTorrentAlert
                .handle()
                .resume()
    }

    private fun onTorrentError(torrentErrorAlert: TorrentErrorAlert) =
            torrentSessionListener?.onTorrentError(
                    createTorrentSessionStatus(torrentErrorAlert.handle())
            )

    private fun onTorrentFinished(torrentFinishedAlert: TorrentFinishedAlert) =
            torrentSessionListener?.onTorrentFinished(
                    createTorrentSessionStatus(torrentFinishedAlert.handle())
            )

    private fun onMetadataFailed(metadataFailedAlert: MetadataFailedAlert) =
            torrentSessionListener?.onMetadataFailed(
                    createTorrentSessionStatus(metadataFailedAlert.handle())
            )

    private fun onDhtStats() {
        synchronized(dhtLock) {
            if (isDhtReady()) {
                dhtLock.notify()
            }
        }
    }

    private fun onDhtBootstrap() {
        synchronized(dhtLock) {
            dhtLock.notify()
        }
    }

    init {
        sessionManager.addListener(alertListener)
        sessionManager.start(torrentSessionOptions.build())
    }

    private fun isDhtReady() = sessionManager
            .stats()
            .dhtNodes() >= MinDhtNodes

    /**
     * Download the torrent associated with the provided [magnetUri]. Abandon
     * downloading the torrent if the magnet fails to resolve within the provided
     * [timeout] in seconds.
     */
    fun downloadMagnet(
            magnetUri: String
            , timeout: Int
    ): Unit = synchronized(dhtLock) {
        // We must wait for DHT to start
        if (!isDhtReady()) {
            dhtLock.wait()
        }

        sessionManager.fetchMagnet(
                URLDecoder.decode(magnetUri, "utf-8")
                , timeout
        )
    }

    /**
     * Set a callback listener to receive torrent session alerts.
     */
    fun setListener(torrentSessionListener: TorrentSessionListener?) {
        this.torrentSessionListener = torrentSessionListener
    }

    /**
     * Stop the torrent session. This is an expensive operation and should not be
     * done on the main thread.
     */
    fun stop() {
        sessionManager.removeListener(alertListener)
        sessionManager.stop()
    }

    /**
     * Pause torrent session.
     */
    fun pause() = sessionManager.pause()

    /**
     * Resume torrent session.
     */
    fun resume() = sessionManager.resume()

}

