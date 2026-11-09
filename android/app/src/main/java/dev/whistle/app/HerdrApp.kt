package dev.whistle.app

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.whistle.app.security.AppLock
import dev.whistle.app.security.PrefsLockStore
import dev.whistle.app.data.HerdrDb
import dev.whistle.app.data.HerdrRepository
import dev.whistle.app.data.MachineStore
import dev.whistle.app.protocol.BridgeClient
import dev.whistle.app.protocol.Machine
import dev.whistle.app.protocol.Uploader
import dev.whistle.app.protocol.DeviceKeys
import dev.whistle.app.protocol.PairInvite
import dev.whistle.app.protocol.PairResult
import dev.whistle.app.protocol.Pairer
import dev.whistle.app.protocol.RequestSigner
import dev.whistle.app.security.KeystoreDeviceKeys
import dev.whistle.app.ui.broadcast.Broadcaster
import dev.whistle.app.ui.snippets.SnippetRepository
import dev.whistle.app.ui.chat.DraftStore
import dev.whistle.app.ui.chat.PrefsDraftBacking
import dev.whistle.app.ui.chat.PromptOutbox
import dev.whistle.app.ui.chat.RepoOutboxBackend
import dev.whistle.app.protocol.ConnState
import dev.whistle.app.widget.OwnedClients
import dev.whistle.app.widget.RefreshOps
import dev.whistle.app.widget.RefreshWorker
import dev.whistle.app.widget.SnapshotStore
import dev.whistle.app.widget.WidgetUpdater
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** The app's object graph (no DI framework). Created lazily: nothing opens until first use. */
class AppContainer(context: Context) {
    private val ctx = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: HerdrDb by lazy { HerdrDb.open(ctx) }
    val okHttp: OkHttpClient by lazy { OkHttpClient() }
    val machines: MachineStore by lazy { MachineStore(ctx.getSharedPreferences("herdr", Context.MODE_PRIVATE)) }

    val repo: HerdrRepository by lazy {
        HerdrRepository(db, { id, cursor ->
            // Screens only ask for machines that exist; a removed one gets an unroutable stand-in.
            val m = machines.get(id) ?: Machine(id, id, "http://0.0.0.0:0")
            BridgeClient(m, okHttp, cursor, scope, signer = signer)
        }, scope)
    }

    /** Prompts sent from the phone, kept across chat pages (outlives any screen). */
    val outbox: PromptOutbox by lazy { PromptOutbox(scope, RepoOutboxBackend(repo)) }

    /** Composer drafts per pane (chat, share, changes); outlive any screen. */
    val drafts: DraftStore by lazy {
        DraftStore(PrefsDraftBacking(ctx.getSharedPreferences("herdr-drafts", Context.MODE_PRIVATE)), scope)
    }

    /** User snippets (Room-backed). */
    val snippets: SnippetRepository by lazy { SnippetRepository(db.snippets()) }

    /** Broadcast prompts through [outbox] (shared pending/outcome state). */
    val broadcaster: Broadcaster by lazy { Broadcaster(outbox) }

    /** Uploads only over a machine whose current connection was welcomed (verified when paired). */
    val uploader: Uploader by lazy {
        Uploader(okHttp, signer) { m ->
            kotlinx.coroutines.withTimeoutOrNull(UPLOAD_CONNECT_WAIT_MS) { repo.sessionReady(m.id).first { it } } != null
        }
    }

    /** This phone's per-machine signing keys (Android Keystore). */
    val deviceKeys: DeviceKeys by lazy { KeystoreDeviceKeys() }
    val signer: RequestSigner by lazy { RequestSigner(deviceKeys) }
    val pairer: Pairer by lazy { Pairer(okHttp, deviceKeys) }

    /** The app lock (process-wide; starts locked). */
    val appLock: AppLock by lazy {
        AppLock(PrefsLockStore(ctx.getSharedPreferences("herdr-security", Context.MODE_PRIVATE)), SystemClock::elapsedRealtime)
    }

    val snapshots: SnapshotStore by lazy { SnapshotStore(ctx.getSharedPreferences("herdr-widget", Context.MODE_PRIVATE)) }
    val widgetUpdater: WidgetUpdater by lazy { WidgetUpdater(ctx, repo, machines, snapshots, scope) }

    /** Set by MainActivity while started: the UI owns the connections then. */
    private val foregroundCount = java.util.concurrent.atomic.AtomicInteger()
    val foreground: Boolean get() = foregroundCount.get() > 0
    fun enterForeground() { foregroundCount.incrementAndGet() }
    fun exitForeground() { foregroundCount.updateAndGet { maxOf(0, it - 1) } }

    /** [RefreshWorker] side effects; closing only stops what the (non-foreground) worker started. */
    fun refreshOps() = object : RefreshOps {
        private val owned = OwnedClients(
            { machines.machines.value.map { it.id } },
            { repo.connect(it).connection.value != ConnState.Offline },
            { repo.connect(it).start() },
            { repo.connect(it).stop() },
        )
        override fun isForeground() = foreground
        override fun openClients() = owned.open()
        override suspend fun awaitSynced(timeoutMs: Long) = widgetUpdater.awaitSynced(timeoutMs)
        override suspend fun publish(allSynced: Boolean) = widgetUpdater.publishFromCache(allSynced)
        override fun closeClients() = owned.close()
    }

    /** Foreground: one connection per machine (spec: no background service in v1). */
    fun connectAll() {
        machines.machines.value.forEach { repo.connect(it.id).start() }
        widgetUpdater.start()
    }

    fun disconnectAll() = repo.close()

    /** Called from the (foreground) UI, so the machine is connected right away. */
    fun saveMachine(m: Machine) {
        machines.upsert(m)
        repo.disconnect(m.id) // the address may have changed: build a fresh client
        repo.connect(m.id).start()
    }

    fun removeMachine(id: String) {
        val old = machines.get(id)
        machines.remove(id)
        repo.disconnect(id)
        old?.keyAlias?.let { runCatching { deviceKeys.delete(it) } }
    }

    /** The machine an invite pairs: the existing one with that address (keeps its cache), else a new id. */
    fun pairingTargetId(invite: PairInvite): String =
        machines.machines.value.firstOrNull { it.baseUrl.trimEnd('/') == invite.baseUrl }?.id ?: machines.newId()

    /** Stores a successful pairing and reconnects the machine signed; drops the key of an earlier pairing. */
    fun completePairing(machineId: String, invite: PairInvite, r: PairResult): Machine {
        val old = machines.get(machineId)
        val m = (old ?: Machine(machineId, invite.displayName, invite.baseUrl)).copy(
            baseUrl = invite.baseUrl,
            deviceId = r.deviceId,
            keyAlias = r.keyAlias,
            bridgeKey = r.bridgeKey,
            deviceName = r.deviceName,
        )
        saveMachine(m)
        old?.keyAlias?.takeIf { it != r.keyAlias }?.let { runCatching { deviceKeys.delete(it) } }
        return m
    }
}

/** How long an upload waits for its machine's connection to be welcomed before "not connected". */
private const val UPLOAD_CONNECT_WAIT_MS = 10_000L

class HerdrApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // App lock backstop: the activities report start/stop themselves (ActivityLock); the process
        // lifecycle (700 ms late, rotation excluded) only covers what they might miss.
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = container.appLock.onForeground()
                override fun onStop(owner: LifecycleOwner) = container.appLock.onBackground()
            })
        }.onFailure { android.util.Log.w("HerdrApp", "lock lifecycle unavailable", it) }
        runCatching { RefreshWorker.schedule(this) }.onFailure { android.util.Log.w("HerdrApp", "refresh scheduling failed", it) } // KEEP: no-op when scheduled; absent WorkManager (Robolectric) must not crash
    }
}

val Context.container: AppContainer get() = (applicationContext as HerdrApp).container
