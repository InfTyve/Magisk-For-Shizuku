package com.topjohnwu.magisk.core.utils

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.R
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Obtains root access through a Shizuku server that itself runs as root.
 *
 * On devices where the system provides a raw root shell (e.g. an engineering build or a
 * bootloader level root) but no `su` binary for apps, Shizuku can be started with root
 * privileges. Shizuku then hands out root processes to authorized apps through binder,
 * which is enough for the Magisk app to flash the boot partition.
 *
 * The obtained process is wrapped into a regular libsu [Shell] and installed as the
 * process wide "main shell", so every `Shell.cmd(...)` call in the app is transparently
 * executed as root.
 */
object ShizukuRoot {

    const val REQUEST_CODE = 0x5348

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val BINDER_WAIT_MS = 1500L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** True while the main shell is backed by Shizuku. */
    @Volatile
    var isUsingShizuku = false
        private set

    /** True while a Shizuku backed shell is being constructed. */
    @Volatile
    var isInjecting = false
        private set

    /** A Shizuku session is either active or currently being created. */
    val isShizukuSession: Boolean get() = isUsingShizuku || isInjecting

    private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null
    private var binderListener: Shizuku.OnBinderReceivedListener? = null

    /**
     * `Shizuku.newProcess` is still shipped but marked private; it is the only way to spawn a
     * raw process (instead of a user service) through a root Shizuku server.
     */
    private val newProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isRootServer(): Boolean = runCatching { Shizuku.getUid() == 0 }.getOrDefault(false)

    /** Whether a root shell can be provided by Shizuku right now. */
    fun canUseRoot(): Boolean =
        isBinderAlive() && !runCatching { Shizuku.isPreV11() }.getOrDefault(true) &&
            hasPermission() && isRootServer()

    /**
     * Prepares a root shell before the main shell is created.
     *
     * [onReady] is always invoked on the main thread, during which the main shell is either
     * already backed by Shizuku, or the regular `su`/`sh` logic should be used.
     */
    fun prepare(activity: Activity, onReady: () -> Unit) {
        // Disabled by default; the user has to opt in from Settings
        if (!Config.shizukuRoot) {
            onReady()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onReady()
            return
        }
        if (isUsingShizuku) {
            onReady()
            return
        }
        if (!isInstalled(activity)) {
            onReady()
            return
        }
        val done = AtomicBoolean(false)
        val finish = { if (done.compareAndSet(false, true)) mainHandler.post { onReady() } }

        if (isBinderAlive()) {
            handleBinder(activity, finish)
            return
        }

        // Shizuku may still be connecting, wait for a short moment
        val listener = object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                removeBinderListener()
                handleBinder(activity, finish)
            }
        }
        binderListener = listener
        runCatching { Shizuku.addBinderReceivedListenerSticky(listener) }
        mainHandler.postDelayed({
            if (!done.get()) {
                removeBinderListener()
                Timber.d("Shizuku binder not available")
                finish()
            }
        }, BINDER_WAIT_MS)
    }

    private fun removeBinderListener() {
        binderListener?.let { runCatching { Shizuku.removeBinderReceivedListener(it) } }
        binderListener = null
    }

    private fun handleBinder(activity: Activity, finish: () -> Unit) {
        if (!hasPermission()) {
            requestPermission(activity, finish)
            return
        }
        if (!isRootServer()) {
            activity.toast(R.string.shizuku_not_root, Toast.LENGTH_LONG)
            finish()
            return
        }
        Thread {
            val ok = inject(activity)
            if (!ok) Timber.e("Failed to create a root shell through Shizuku")
            finish()
        }.apply { name = "shizuku-shell-init" }.start()
    }

    private fun requestPermission(activity: Activity, finish: () -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE)
                    return
                permissionListener?.let {
                    runCatching { Shizuku.removeRequestPermissionResultListener(it) }
                }
                permissionListener = null

                if (grantResult != PackageManager.PERMISSION_GRANTED || !isRootServer()) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED)
                        activity.toast(R.string.shizuku_not_root, Toast.LENGTH_LONG)
                    finish()
                    return
                }
                Thread {
                    inject(activity)
                    finish()
                }.apply { name = "shizuku-shell-init" }.start()
            }
        }
        permissionListener = listener
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            Timber.e(e)
            runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
            permissionListener = null
            finish()
        }
    }

    /**
     * Creates a root shell with Shizuku and installs it as libsu's main shell.
     * Must be called off the main thread.
     */
    fun inject(context: Context): Boolean {
        if (!Config.shizukuRoot)
            return false
        if (isUsingShizuku)
            return true
        if (!canUseRoot())
            return false

        isInjecting = true
        var shell: Shell? = null
        try {
            // Let Shizuku fork a root shell. Its uid equals the Shizuku server uid (0).
            // Shizuku no longer exposes this helper publicly, but it is still functional.
            val process = ShizukuProcess(
                newProcessMethod.invoke(
                    null,
                    arrayOf("/system/bin/sh"),
                    null,
                    "/"
                ) as Process
            )
            shell = Shell.Builder.create()
                .setFlags(0)
                .setContext(context)
                .setTimeout(30)
                .setInitializers(ShellInit::class.java)
                .build(process)

            if (!shell.isRoot) {
                Timber.e("Shizuku shell is not root")
                return false
            }
            setMainShell(shell)
            Info.isRooted = true
            isUsingShizuku = true
            Timber.i("Root shell obtained via Shizuku (uid=${runCatching { Shizuku.getUid() }.getOrDefault(-1)})")
            return true
        } catch (e: Throwable) {
            Timber.e(e)
            runCatching { shell?.close() }
            return false
        } finally {
            isInjecting = false
        }
    }

    /**
     * Overwrites libsu's cached main shell. [Shell.Builder.build] only caches the shell
     * when it is invoked from `MainShell`, therefore the cache has to be patched manually.
     */
    private fun setMainShell(shell: Shell) {
        val clazz = Class.forName("com.topjohnwu.superuser.internal.MainShell")
        val field = clazz.getDeclaredField("mainShell")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val holder = field.get(null) as Array<Any?>
        synchronized(holder) { holder[0] = shell }
    }
}

/**
 * Wrapper around a process created by Shizuku.
 *
 * libsu probes [Process.exitValue] and expects an [IllegalThreadStateException] while the
 * process is still alive. The exception thrown by Shizuku has to cross a binder boundary and
 * may be remapped to a generic exception, so normalize it here.
 */
private class ShizukuProcess(private val remote: Process) : Process() {

    private var exited = false
    private var exitCode = 0

    override fun getOutputStream(): OutputStream = remote.outputStream

    override fun getInputStream(): InputStream = remote.inputStream

    override fun getErrorStream(): InputStream = remote.errorStream

    override fun waitFor(): Int {
        val code = remote.waitFor()
        synchronized(this) {
            exited = true
            exitCode = code
        }
        return code
    }

    override fun exitValue(): Int = synchronized(this) {
        if (!exited) {
            val code = try {
                remote.exitValue()
            } catch (e: Throwable) {
                throw IllegalThreadStateException("process hasn't exited")
            }
            exited = true
            exitCode = code
        }
        exitCode
    }

    override fun destroy() {
        runCatching { remote.destroy() }
    }
}
