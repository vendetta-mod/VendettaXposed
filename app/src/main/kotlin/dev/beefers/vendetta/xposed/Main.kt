package dev.beefers.vendetta.xposed

import java.io.File
import java.lang.Exception
import kotlinx.coroutines.*
import android.content.res.AssetManager
import android.content.res.XModuleResources
import android.util.Log
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.engine.cio.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.*
import io.ktor.http.*

class Main : IXposedHookZygoteInit, IXposedHookLoadPackage {
    private lateinit var resources: XModuleResources

    // Assign module resources in process zygote
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        resources = XModuleResources.createInstance(startupParam.modulePath, null)
    }

    // Hook function responsible for loading Discord's package
    // Also, Kotlin has `with`? How does this language have literally everything?!
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) = with(param) {
        // Stop executing when we aren't in Discord's React Native context
        val catalystInstance = try { classLoader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl") } catch(e: ClassNotFoundException) { return }

        val cache = File(appInfo.dataDir, "cache").also { it.mkdirs() }
        val bundle = File(cache, "vendetta.js")
        val etag = File(cache, "vendetta_etag.txt")

        val scope = MainScope()
        val httpJob = scope.async(Dispatchers.IO) {
            try {
                val client = HttpClient(CIO) {
                    install(HttpTimeout) { requestTimeoutMillis = 1000 }
                    // TODO: This should follow proper User Agent format
                    install(UserAgent) { agent = "VendettaXposed on ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})" }
                }
                val response: HttpResponse = client.get("https://raw.githubusercontent.com/vendetta-mod/builds/master/vendetta.js") {
                    headers { if (etag.exists() && bundle.exists()) append(HttpHeaders.IfNoneMatch, etag.readText()) }
                }

                if (response.status.value == 200) {
                    bundle.writeBytes(response.body())
                    if (response.headers.contains("Etag")) etag.writeText(response.headers["Etag"] as String)
                }

                return@async
            } catch (e: Exception) {
                Log.e("Vendetta", "Failed to download Vendetta")
            }
        }

        val loadScriptFromAssets = catalystInstance.getDeclaredMethod("jniLoadScriptFromAssets", AssetManager::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        val loadScriptFromFile = catalystInstance.getDeclaredMethod("jniLoadScriptFromFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)

        // TODO: Loader config

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam): Unit = runBlocking {
                Log.i("Vendetta", "beforeHookedMethod called")

                Log.i("Vendetta", "Exfiltrating Metro modules")
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets,param.thisObject, arrayOf(resources.assets, "assets://js/modules.js", true))
                Log.i("Vendetta", "Executing identity snippet")
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(resources.assets, "assets://js/identity.js", true))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                Log.i("Vendetta", "afterHookedMethod called")

                try {
                    scope.launch(scope.coroutineContext) {
                        Log.i("Vendetta", "Waiting for HTTP")
                        httpJob.await()

                        Log.i("Vendetta", "Executing Vendetta")
                        XposedBridge.invokeOriginalMethod(loadScriptFromFile, param.thisObject, arrayOf(bundle.absolutePath, bundle.absolutePath, param.args[2]))
                    }
                } catch(_: Exception) {}
            }
        }

        XposedBridge.hookMethod(loadScriptFromAssets, hook)
        XposedBridge.hookMethod(loadScriptFromFile, hook)

        return@with
    }
}