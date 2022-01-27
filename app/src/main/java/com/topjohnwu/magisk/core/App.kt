package com.topjohnwu.magisk.core

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import com.topjohnwu.magisk.DynAPK
import com.topjohnwu.magisk.core.utils.*
import com.topjohnwu.magisk.di.ServiceLocator
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.internal.UiThreadHandler
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import top.canyie.pine.Pine
import top.canyie.pine.PineConfig
import top.canyie.pine.callback.MethodHook
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.RuntimeException
import java.lang.StringBuilder
import java.util.*
import kotlin.system.exitProcess

open class App() : Application() {

    constructor(o: Any) : this() {
        val data = DynAPK.Data(o)
        // Add the root service name mapping
        data.classToComponent[RootRegistry::class.java.name] = data.rootService.name
        // Send back the actual root service class
        data.rootService = RootRegistry::class.java
        Info.stub = data
    }

    init {
        // Always log full stack trace with Timber
        Timber.plant(Timber.DebugTree())
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            Timber.e(e)
            exitProcess(1)
        }
    }

    companion object {
        fun le(str : String) {
            Log.e("MagiskApp", str)
        }

        fun le(str: String, e: Throwable) {
            Log.e("MagiskApp", str, e)
        }
    }

    override fun attachBaseContext(context: Context) {
        PineConfig.debug = true
        PineConfig.debuggable = true

        Pine.hook(Runtime::class.java.getDeclaredMethod("exec", Array<String>::class.java),
                object : MethodHook() {
                    @SuppressLint("LogNotTimber")
                    override fun afterCall(callFrame: Pine.CallFrame?) {
                        callFrame!!
                        le("After Runtime.exec, args=${(callFrame.args[0] as Array<String>).contentToString()}, result=${callFrame.result}")
                        callFrame.result?.apply {
                            callFrame.result = ProxyProcess(callFrame.result!! as Process)
                        }
                    }
                })

        Shell.enableVerboseLogging = true;
        Shell.setDefaultBuilder(Shell.Builder.create()
            .setFlags(Shell.FLAG_MOUNT_MASTER)
            .setInitializers(ShellInit::class.java)
            .setTimeout(20))
        Shell.EXECUTOR = DispatcherExecutor(Dispatchers.IO)

        // Get the actual ContextImpl
        val app: Application
        val base: Context
        if (context is Application) {
            app = context
            base = context.baseContext
        } else {
            app = this
            base = context
        }
        super.attachBaseContext(base)
        ServiceLocator.context = base

        refreshLocale()
        AppApkPath = if (isRunningAsStub) {
            DynAPK.current(base).path
        } else {
            base.packageResourcePath
        }

        base.resources.patch()
        app.registerActivityLifecycleCallbacks(ForegroundTracker)
    }

    class ProxyOutputStream(val base : OutputStream) : OutputStream() {
        private var buffer = ByteArrayOutputStream()
        override fun write(b: Int) {
            base.write(b)
            buffer.write(b)
        }

        override fun write(b: ByteArray?) {
            base.write(b)
            buffer.write(b)
        }

        override fun write(b: ByteArray?, off: Int, len: Int) {
            base.write(b, off, len)
            buffer.write(b!!, off, len)
        }

        override fun flush() {
            base.flush()
            le("STDIN: write: \"${buffer.toString("utf-8")}\"")
            buffer = ByteArrayOutputStream()
        }

        override fun close() {
            flush()
            base.close()
        }
    }

    class ProxyInputStream(val name : String, val base : InputStream) : InputStream() {
        private var buffer = ByteArrayOutputStream()

        fun flush() {
            le("$name: read: \"${buffer.toString("utf-8")}\"")
            buffer = ByteArrayOutputStream()
        }

        override fun read(): Int {
            val r = base.read()
            buffer.write(r)
            if (r.toChar() == '\n') flush()
            return r
        }

        override fun read(b: ByteArray?): Int {
            val size = base.read(b)
            if (size > 0) {
                buffer.write(b)
                flush()
            }
            return size
        }

        override fun read(b: ByteArray?, off: Int, len: Int): Int {
            val size = base.read(b, off, len)
            if (size > 0) {
                buffer.write(b!!, off, size)
                flush()
            }
            return size
        }

        override fun close() {
            base.close()
            flush()
        }
    }

    class ProxyProcess(val base: Process) : Process() {
        override fun getOutputStream(): OutputStream {
            le("getOutputStream")
            return ProxyOutputStream(base.outputStream)
        }

        override fun getInputStream(): InputStream {
            le("getInputStream")
            return ProxyInputStream("STDOUT", base.inputStream)
        }

        override fun getErrorStream(): InputStream {
            le("getErrorStream")
            return ProxyInputStream("STDERR", base.errorStream)
        }

        override fun waitFor(): Int {
            try {
                val r = base.waitFor()
                le("waitFor: $r")
                return r
            } catch (e: Throwable) {
                le("waitFor", e)
                throw e
            }
        }

        override fun exitValue(): Int {
            try {
                val r = base.exitValue()
                le("exitValue: $r")
                return r
            } catch (e: Throwable) {
                le("exitValue", e)
                throw e
            }
        }

        override fun destroy() {
            try {
                base.destroy()
                le("destroy: success")
            } catch (e: Throwable) {
                le("destroy", e)
                throw e
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        RootRegistry.bindTask = RootService.createBindTask(
            intent<RootRegistry>(),
            UiThreadHandler.executor,
            RootRegistry.Connection
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (resources.configuration.diff(newConfig) != 0) {
            resources.setConfig(newConfig)
        }
        if (!isRunningAsStub)
            super.onConfigurationChanged(newConfig)
    }
}

@SuppressLint("StaticFieldLeak")
object ForegroundTracker : Application.ActivityLifecycleCallbacks {

    @Volatile
    var foreground: Activity? = null

    val hasForeground get() = foreground != null

    override fun onActivityResumed(activity: Activity) {
        foreground = activity
    }

    override fun onActivityPaused(activity: Activity) {
        foreground = null
    }

    override fun onActivityCreated(activity: Activity, bundle: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
