package com.vendetta.xposed

import android.annotation.SuppressLint
import android.content.res.AssetManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.net.URL

class InsteadHook(private val hook: (MethodHookParam) -> Any?) : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) {
        param.result = hook(param)
    }
}

class Main : IXposedHookLoadPackage {
    @SuppressLint("PrivateApi", "BlockedPrivateApi")
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.discord") return

        val cache = File(param.appInfo.dataDir, "cache")
        val modules = File(cache, "modules.js")
        if (!modules.exists()) {
            modules.parentFile?.mkdirs()
            modules.writeText("""
                const oldObjectCreate = this.Object.create;
                const win = this;
                win.Object.create = (...args) => {
                    const obj = oldObjectCreate.apply(win.Object, args);
                    if (args[0] === null) {
                        win.modules = obj;
                        win.Object.create = oldObjectCreate;
                    }
                    return obj;
                };
            """.trimIndent())
        }

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

        XposedBridge.hookMethod(loadScriptFromAssets, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                loadScriptFromFile.invoke(param.thisObject, modules.absolutePath, modules.absolutePath, param.args[2])
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val vendetta = File(cache, "vendetta.js")
                vendetta.writeBytes(URL("http://localhost:4040/vendetta.js").readBytes())
                loadScriptFromFile.invoke(param.thisObject, vendetta.absolutePath, vendetta.absolutePath, param.args[2])
            }
        })
    }
}
