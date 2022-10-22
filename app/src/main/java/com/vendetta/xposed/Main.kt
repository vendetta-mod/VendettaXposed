package com.vendetta.xposed

import com.vendetta.xposed.BuildConfig
import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.content.pm.ApplicationInfo
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.net.URL

class Main : IXposedHookLoadPackage {
    @SuppressLint("PrivateApi", "BlockedPrivateApi")
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.discord") return

        val cache = File(param.appInfo.dataDir, "cache")
        val modules = File(cache, "modules.js")
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

        val vendetta = File(cache, "vendetta.js")

        XposedBridge.hookMethod(loadScriptFromAssets, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    vendetta.writeBytes(URL(if (BuildConfig.BUILD_TYPE.equals("debug")) "http://localhost:4040/vendetta.js" else "https://raw.githubusercontent.com/vendetta-mod/builds/master/vendetta.js").readBytes())
                } catch(e: Exception) {}
                loadScriptFromFile.invoke(param.thisObject, modules.absolutePath, modules.absolutePath, param.args[2])
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    loadScriptFromFile.invoke(param.thisObject, vendetta.absolutePath, vendetta.absolutePath, param.args[2])
                } catch(e: Exception) {}
            }
        })
    }
}
