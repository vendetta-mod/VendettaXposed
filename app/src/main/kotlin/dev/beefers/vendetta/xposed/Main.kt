package dev.beefers.vendetta.xposed

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

        // TODO: bundle (down)loading with etag
        // val cache = File(appInfo.dataDir, "cache").also { it.mkdirs() }
        // val bundle = File(cache, "vendetta.js")

        // TODO: loader config

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                Log.i("Vendetta", "beforeHookedMethod called")

                Log.i("Vendetta", "Exfiltrating Metro modules")
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets,param.thisObject, arrayOf(resources.assets, "assets://js/modules.js", true))
                Log.i("Vendetta", "Executing identity snippet")
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(resources.assets, "assets://js/identity.js", true))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                Log.i("Vendetta", "afterHookedMethod called")
            }
        }

        XposedBridge.hookMethod(loadScriptFromAssets, hook)
        XposedBridge.hookMethod(loadScriptFromFile, hook)

        return@with;
    }
}