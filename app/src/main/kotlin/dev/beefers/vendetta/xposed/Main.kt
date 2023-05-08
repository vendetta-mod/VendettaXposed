package dev.beefers.vendetta.xposed

// Java
import java.io.File

// Kotlin
import kotlinx.coroutines.runBlocking

// Android
import android.content.res.AssetManager
import android.content.res.XModuleResources
import android.util.Log

// Xposed
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

// Ktor
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.reader
import java.lang.Exception

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
        val loadScriptFromAssets = catalystInstance.getDeclaredMethod("jniLoadScriptFromAssets", AssetManager::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        val loadScriptFromFile = catalystInstance.getDeclaredMethod("jniLoadScriptFromFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)

         val cache = File(appInfo.dataDir, "cache").also { it.mkdirs() }
         val bundle = File(cache, "vendetta.js")
         val etag = File(cache, "vendetta_etag.txt")

        // TODO: loader config
        // TODO: make fast.

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam): Unit = runBlocking {
                Log.i("Vendetta", "beforeHookedMethod called")

                try {
                    val client = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 3000 } }
                    val response: HttpResponse = client.get("https://raw.githubusercontent.com/vendetta-mod/builds/master/vendetta.js") {
                        headers { if (etag.exists() && bundle.exists()) append(HttpHeaders.IfNoneMatch, etag.readText()) }
                    }

                    if (response.status.isSuccess()) {
                        bundle.writeText(response.bodyAsText())
                        if (response.headers.contains("Etag")) etag.writeText(response.headers["Etag"] as String)
                    }
                } catch (e: Exception) {
                    Log.e("Vendetta", "Failed to download Vendetta")
                }

                Log.i("Vendetta", "Exfiltrating Metro modules")
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets,param.thisObject, arrayOf(resources.assets, "assets://js/modules.js", true))
                Log.i("Vendetta", "Executing identity snippet")
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(resources.assets, "assets://js/identity.js", true))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                Log.i("Vendetta", "afterHookedMethod called")

                try {
                    Log.i("Vendetta", "Executing Vendetta")
                    XposedBridge.invokeOriginalMethod(loadScriptFromFile, param.thisObject, arrayOf(bundle.absolutePath, bundle.absolutePath, param.args[2]))
                } catch(_: Exception) {}
            }
        }

        XposedBridge.hookMethod(loadScriptFromAssets, hook)
        XposedBridge.hookMethod(loadScriptFromFile, hook)

        return@with;
    }
}