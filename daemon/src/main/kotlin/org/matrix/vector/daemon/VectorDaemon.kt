package org.matrix.vector.daemon

import android.app.ActivityManager
import android.app.ActivityThread
import android.content.Context
import android.ddm.DdmHandleAppName
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.ServiceManager
import android.os.SystemProperties
import android.system.Os
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.env.CliSocketServer
import org.matrix.vector.daemon.env.Dex2OatServer
import org.matrix.vector.daemon.env.LogcatMonitor
import org.matrix.vector.daemon.ipc.BRIDGE_TRANSACTION_CODE
import org.matrix.vector.daemon.ipc.ManagerService
import org.matrix.vector.daemon.ipc.SystemServerService
import org.matrix.vector.daemon.utils.applyNotificationWorkaround

private const val TAG = "VectorDaemon"
private const val ACTION_SEND_BINDER = 1

object VectorDaemon {
  private val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
    Log.e(TAG, "Caught fatal coroutine exception in background task!", throwable)
  }

  // Dispatchers.IO: Uses the shared background thread pool.
  // SupervisorJob(): Ensures one failing task doesn't kill the whole daemon.
  val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
  val bridgeServiceName = "activity"

  /**
   * Whether a `system_server` was already serving the moment this daemon started, which is how
   * Zygisk injecting into a booted system rather than into a fresh fork shows up on our side. The
   * boot it missed then has to be replayed, and it has to be met under another name.
   *
   * Latched once during [main] and never revisited: by the time anything reads it a system_server
   * is running either way, and what matters is which of the two we were started into.
   */
  var startedAfterSystemServer = false

  /**
   * The name the module looks the daemon up under while system_server is specialized. `serial` is
   * free at boot, because system_server registers it only much later, so a binder found there can
   * only be ours. On a booted system the real service holds it, and a lookup returning that is
   * indistinguishable from a successful rendezvous, so a name of our own is used instead.
   *
   * Derived rather than stored: the two were separate fields until a restart moved one and left
   * the other behind, and the daemon then waited under a name nothing would ever ask for.
   */
  val proxyServiceName: String
    get() = if (startedAfterSystemServer) "serial_vector" else "serial"

  @JvmStatic
  fun main(args: Array<String>) {
    if (!FileSystem.tryLock()) kotlin.system.exitProcess(0)

    var systemServerMaxRetry = 1
    for (arg in args) {
      if (arg.startsWith("--system-server-max-retry=")) {
        systemServerMaxRetry = arg.substringAfter('=').toIntOrNull() ?: 1
      }
    }

    // `activity` belongs to system_server, and at boot we are started beside a zygote that has yet
    // to fork one. checkService, not getService: the latter blocks for seconds on an absent name
    // before Android R, and absent is the boot path, where the module is already polling for the
    // proxy we have not registered yet.
    startedAfterSystemServer = ServiceManager.checkService(bridgeServiceName) != null

    Log.i(
        TAG,
        "Vector daemon started: afterSystemServer=$startedAfterSystemServer, " +
            "proxy=$proxyServiceName")
    // The hash is here rather than in the version the manager prints: Home should stay readable,
    // but every saved bug report should say exactly which commit produced the daemon that wrote it.
    Log.i(
        TAG,
        "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
            "commit ${BuildConfig.VERSION_HASH}")

    Thread.setDefaultUncaughtExceptionHandler { _, e ->
      Log.e(TAG, "Uncaught exception in Daemon", e)
      kotlin.system.exitProcess(1)
    }

    // Setup Main Looper
    Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
    @Suppress("DEPRECATION") Looper.prepareMainLooper()

    // Squat on the proxy service name immediately, which creates the early IPC channel of
    // FrameworkService for our Zygisk module during system_server specialization.
    SystemServerService.registerProxyService(proxyServiceName)

    // Start Environmental Daemons
    LogcatMonitor.start()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Dex2OatServer.start()
    CliSocketServer.start()

    // Preload Framework DEX in the background
    scope.launch { FileSystem.getPreloadDex(ConfigCache.state.isDexObfuscateEnabled) }

    // Initializes system frameworks inside the daemon process
    ActivityThread.systemMain()
    DdmHandleAppName.setAppName("org.matrix.vector.daemon", 0)

    // Wait for Android core services
    waitForSystemService("package")
    waitForSystemService("activity") // current bridgeServiceName
    waitForSystemService(Context.USER_SERVICE)
    waitForSystemService(Context.APP_OPS_SERVICE)

    applyNotificationWorkaround()

    // Read this before `sendToBridge`, which leaves the main thread at euid 1000: the config
    // database lives under a directory only root can enter, so the first process to open it has
    // to do so while we still have root. On a successful injection a binder thread opens it for
    // us during specialization, but when the injection fails nothing else has, and the daemon
    // used to die here on an unreadable preference.
    val isVerboseLog = ManagerService.isVerboseLogEnabled()

    // Setup IPC channel for applications by injecting DaemonService binder
    sendToBridge(VectorService.asBinder(), false, systemServerMaxRetry)

    if (!isVerboseLog) {
      LogcatMonitor.stopVerbose()
    }

    Looper.loop()
    throw RuntimeException("Main thread loop unexpectedly exited")
  }

  private fun waitForSystemService(name: String) = runBlocking {
    while (ServiceManager.getService(name) == null) {
      Log.i(TAG, "Waiting system service: $name for 1s")
      delay(1000)
    }
  }

  // The bridge is setup in `system_server` via Zygisk API
  @Suppress("DEPRECATION")
  private fun sendToBridge(
      binder: IBinder,
      isRestart: Boolean,
      restartRetry: Int,
  ) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "sendToBridge MUST run on the main thread!"
    }

    Os.seteuid(0)

    runCatching {
          var bridgeService: IBinder?
          if (isRestart) Log.w(TAG, "system_server restarted...")

          while (true) {
            bridgeService = ServiceManager.getService(bridgeServiceName)
            if (bridgeService?.pingBinder() == true) break
            Log.i(TAG, "`$bridgeServiceName` service not ready, waiting 1s...")
            Thread.sleep(1000)
          }

          // Setup death recipient to handle system_server crashes
          val deathRecipient =
              object : IBinder.DeathRecipient {
                override fun binderDied() {
                  Log.w(TAG, "System Server died! Clearing caches and re-injecting...")
                  bridgeService.unlinkToDeath(this, 0)
                  clearSystemCaches()
                  SystemServerService.binderDied() // Cleanup old references
                  // Re-claim the service name immediately to ensure that when system_server
                  // restarts, our proxy is already there for the Zygisk module to find.
                  ServiceManager.addService(proxyServiceName, SystemServerService)
                  ManagerService.guard = null // Remove dead guard
                  Handler(Looper.getMainLooper()).post {
                    sendToBridge(binder, true, restartRetry - 1)
                  }
                }
              }
          bridgeService.linkToDeath(deathRecipient, 0)

          // Try sending the Binder payload (up to 3 times)
          var success = false
          for (i in 0 until 3) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
              data.writeInt(ACTION_SEND_BINDER)
              data.writeStrongBinder(binder)
              success = bridgeService.transact(BRIDGE_TRANSACTION_CODE, data, reply, 0) == true
              reply.readException()
              if (success) break
            } finally {
              data.recycle()
              reply.recycle()
            }
            Log.w(TAG, "No response from bridge, retrying...")
            Thread.sleep(1000)
          }

          if (success) {
            Log.i(TAG, "Successfully injected Vector IPC binder for applications.")
            // Only the name we invented for a late injection: `serial` is a real service name,
            // and system_server registering it is what takes our proxy out of servicemanager.
            if (startedAfterSystemServer) SystemServerService.unregisterProxyService()
          } else {
            Log.e(TAG, "Failed to inject VectorService into system_server")
            // Restarting is a recovery only when our module is in system_server and the
            // handshake went wrong, because the replacement is specialized by the same hooked
            // zygote and gets another try. When no module reached system_server in the first
            // place the hooks are not there, so every restart takes the user's screen down and
            // arrives back here; the retry budget then spends itself on a row of them.
            //
            // `systemServerRequested` separates the two: the module latches it from inside
            // `postServerSpecialize`, which runs before system_server starts a single service,
            // while the loop above only transacts once `activity` answers a ping. So a live
            // `activity` with no handshake behind it means no module, not a race.
            when {
              !SystemServerService.systemServerRequested ->
                  Log.e(TAG, "No Vector module reached system_server; leaving it running")
              restartRetry > 0 -> restartSystemServer()
            }
          }
        }
        .onFailure { Log.e(TAG, "Error during injecting DaemonService", it) }
    Os.seteuid(1000)
  }

  private fun clearSystemCaches() {
    Log.i(TAG, "Clearing ServiceManager and ActivityManager caches...")
    runCatching {
          // Clear ServiceManager.sServiceManager
          var field = ServiceManager::class.java.getDeclaredField("sServiceManager")
          field.isAccessible = true
          field.set(null, null)

          // Clear ServiceManager.sCache
          field = ServiceManager::class.java.getDeclaredField("sCache")
          field.isAccessible = true
          val sCache = field.get(null)
          if (sCache is MutableMap<*, *>) {
            sCache.clear()
          }

          // Clear ActivityManager.IActivityManagerSingleton
          field = ActivityManager::class.java.getDeclaredField("IActivityManagerSingleton")
          field.isAccessible = true
          val singleton = field.get(null)
          if (singleton != null) {
            val mInstanceField =
                Class.forName("android.util.Singleton").getDeclaredField("mInstance")
            mInstanceField.isAccessible = true
            synchronized(singleton) { mInstanceField.set(singleton, null) }
          }
        }
        .onFailure { Log.w(TAG, "Failed to clear system caches via reflection", it) }
  }

  /**
   * Brings the framework down and up without rebooting the device.
   *
   * `system_server` is forked from the *primary* zygote, so restarting that is what restarts the
   * framework — on a 64/32 device the primary init service is still called `zygote` (it runs
   * app_process64) and `zygote_secondary` is the 32-bit one. Restarting the secondary leaves
   * system_server running, which is right for [restartSystemServer]'s own purpose and wrong for
   * this one; they are separate functions for that reason.
   *
   * Everything on screen dies with it. The caller is expected to have said so first.
   */
  fun softReboot() {
    Log.w(TAG, "Soft reboot: restarting the primary zygote")
    SystemProperties.set("ctl.restart", "zygote")
  }

  fun restartSystemServer() {
    Log.w(TAG, "Restarting system_server...")
    val restartTarget =
        if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && Build.SUPPORTED_32_BIT_ABIS.isNotEmpty()) {
          "zygote_secondary"
        } else {
          "zygote"
        }
    SystemProperties.set("ctl.restart", restartTarget)
  }
}
