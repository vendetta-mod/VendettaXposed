package com.vendetta.xposed

import android.content.res.AssetManager
import android.content.res.XModuleResources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.net.URL

@Serializable
data class LoaderConfig(
    val loadFromLocal: Boolean,
    val loadReactDevTools: Boolean
)

class Main : IXposedHookZygoteInit, IXposedHookLoadPackage {
    private lateinit var modResources: XModuleResources

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modResources = XModuleResources.createInstance(startupParam.modulePath, null)
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.discord") return

        val catalystInstanceImpl = param.classLoader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl")

        val loadScriptFromAssets = catalystInstanceImpl.getDeclaredMethod(
            "loadScriptFromAssets",
            AssetManager::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        )

        val loadScriptFromFile = catalystInstanceImpl.getDeclaredMethod(
            "jniLoadScriptFromFile",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }

        val cache = File(param.appInfo.dataDir, "cache").also { it.mkdirs() }
        val vendetta = File(cache, "vendetta.js")

        lateinit var config: LoaderConfig
        val files = File(param.appInfo.dataDir, "files").also { it.mkdirs() }
        val configFile = File(files, "vendetta_loader.json")
        try {
            config = Json.decodeFromString(configFile.readText())
        } catch (_: Exception) {
            config = LoaderConfig(
                loadFromLocal = false,
                loadReactDevTools = false
            )
            configFile.writeText(Json.encodeToString(config))
        }

        XposedBridge.hookMethod(loadScriptFromAssets, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    vendetta.writeBytes(URL(if (config.loadFromLocal) "http://localhost:4040/vendetta.js" else "https://raw.githubusercontent.com/vendetta-mod/builds/master/vendetta.js").readBytes())
                } catch (_: Exception) {}

                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(modResources.assets, "assets://js/modules.js", true))
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(modResources.assets, "assets://js/identity.js", true))
                if (config.loadReactDevTools)
                    XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(modResources.assets, "assets://js/devtools.js", true))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    loadScriptFromFile.invoke(param.thisObject, vendetta.absolutePath, vendetta.absolutePath, param.args[2])
                } catch (_: Exception) {}
            }
        })
    }
}
