package com.vendetta.xposed

import android.content.res.AssetManager
import android.content.res.XModuleResources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.net.URL

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

        XposedBridge.hookMethod(loadScriptFromAssets, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    vendetta.writeBytes(URL(if (BuildConfig.BUILD_TYPE == "debug") "http://localhost:4040/vendetta.js" else "https://raw.githubusercontent.com/vendetta-mod/builds/master/vendetta.js").readBytes())
                } catch(_: Exception) {}

                modResources.assets.list("js")?.forEach {
                    // The last `true` signifies that this is loaded synchronously, this is
                    // important since these scripts need to load before Discord.
                    XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(modResources.assets, "assets://js/$it", true))
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    loadScriptFromFile.invoke(param.thisObject, vendetta.absolutePath, vendetta.absolutePath, param.args[2])
                } catch(_: Exception) {}
            }
        })
    }
}
