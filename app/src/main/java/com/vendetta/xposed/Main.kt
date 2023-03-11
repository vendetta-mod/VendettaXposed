package com.vendetta.xposed

import android.content.Context
import android.graphics.Color
import android.content.res.AssetManager
import android.content.res.XModuleResources
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URL
import java.util.HashMap

@Serializable
data class CustomLoadUrl(
    val enabled: Boolean,
    val url: String
)
@Serializable
data class LoaderConfig(
    val customLoadUrl: CustomLoadUrl,
    val loadReactDevTools: Boolean
)
@Serializable
data class ThemeData<T>(
    val name: String,
    val description: String,
    val version: String,
    val theme_color_map: Map<String, List<T>>,
    val colors: Map<String, T>,
)

class Main : IXposedHookZygoteInit, IXposedHookLoadPackage {
    private lateinit var modResources: XModuleResources

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modResources = XModuleResources.createInstance(startupParam.modulePath, null)
    }

    fun createTempFileFromString(content: String): File {
        val tempFile = File.createTempFile("eval_", ".js")
        tempFile.writeText(content)
        return tempFile
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        val catalystInstanceImpl = param.classLoader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl")
        val resourceDrawableIdHelper = param.classLoader.loadClass("com.facebook.react.views.imagehelper.ResourceDrawableIdHelper")
        val soundManagerModule = param.classLoader.loadClass("com.discord.sounds.SoundManagerModule")
        val darkTheme = param.classLoader.loadClass("com.discord.theme.DarkTheme")

        val loadScriptFromAssets = catalystInstanceImpl.getDeclaredMethod(
            "jniLoadScriptFromAssets",
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

        val getResourceDrawableId = resourceDrawableIdHelper.getDeclaredMethod(
            "getResourceDrawableId",
            Context::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val mResourceDrawableIdMapField = resourceDrawableIdHelper.getDeclaredField("mResourceDrawableIdMap").apply {
            isAccessible = true
        }

        val resolveRawResId = soundManagerModule.getDeclaredMethod(
            "resolveRawResId",
            Context::class.java,
            String::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val cache = File(param.appInfo.dataDir, "cache").also { it.mkdirs() }
        val vendetta = File(cache, "vendetta.js")

        lateinit var config: LoaderConfig
        val files = File(param.appInfo.dataDir, "files").also { it.mkdirs() }
        val configFile = File(files, "vendetta_loader.json")
        val themeFile = File(files, "vendetta_theme.json")
        val processedThemeFile = File(files, "vendetta_processed_theme.json")

        try {
            config = Json.decodeFromString(configFile.readText())
        } catch (_: Exception) {
            config = LoaderConfig(
                customLoadUrl = CustomLoadUrl(
                    enabled = false,
                    url = "http://localhost:4040/vendetta.js"
                ),
                loadReactDevTools = false
            )
            configFile.writeText(Json.encodeToString(config))
        }

        try {
            val theme = Json { ignoreUnknownKeys = true }.decodeFromString<ThemeData<Integer>>(processedThemeFile.readText())
            
            for ((key, value) in theme.theme_color_map) {
                val methodName = "get${key.split("_").joinToString("") { it.toLowerCase().replaceFirstChar { it.uppercase() } }}"

                try {
                    darkTheme.getDeclaredMethod(methodName)?.let { method ->
                        XposedBridge.log("Hooking $key -> $value")
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.result = value[0] // 0 == dark
                            }
                        })
                    }
                } catch (ex: NoSuchMethodException) {
                    // do nothing
                }
            }
        } catch (ex: Exception) {
            XposedBridge.log("Vendetta: Unable to find/parse theme, perhaps does not exist?")
            XposedBridge.log(ex)
        }

        val patch = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val url = if (config.customLoadUrl.enabled) config.customLoadUrl.url else "https://raw.githubusercontent.com/vendetta-mod/builds/master/vendetta.js"
                try {
                    URL(url).openStream().use { input ->
                        vendetta.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (_: Exception) {
                    Log.i("Vendetta", "Failed to download Vendetta from $url")
                }

                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(modResources.assets, "assets://js/modules.js", true))
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(modResources.assets, "assets://js/identity.js", true))
                if (config.loadReactDevTools)
                    XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(modResources.assets, "assets://js/devtools.js", true))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val themeString = try { themeFile.readText() } catch (_: Exception) { "null" }
                    val tempEvalFile = createTempFileFromString("globalThis.__vendetta_theme=" + themeString)
                    
                    XposedBridge.invokeOriginalMethod(loadScriptFromFile, param.thisObject, arrayOf(tempEvalFile.absolutePath, tempEvalFile.absolutePath, param.args[2]))
                    XposedBridge.invokeOriginalMethod(loadScriptFromFile, param.thisObject, arrayOf(vendetta.absolutePath, vendetta.absolutePath, param.args[2]))
                } catch (_: Exception) {}
            }
        }

        XposedBridge.hookMethod(loadScriptFromAssets, patch)
        XposedBridge.hookMethod(loadScriptFromFile, patch)
        XposedBridge.hookMethod(getResourceDrawableId, object: XC_MethodHook() {
            @Suppress("UNCHECKED_CAST")
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = param.args[0] as Context
                val str = param.args[1] as String?
                val mResourceDrawableIdMap = mResourceDrawableIdMapField.get(param.thisObject) as HashMap<String, Int>
                if(str.isNullOrEmpty()) {
                    param.result = 0; return
                }
                val replace = str.replace("-", "_")
                try {
                    param.result = Integer.parseInt(replace); return
                } catch (e: Throwable) {
                    synchronized(param.thisObject) {
                        if(mResourceDrawableIdMap.containsKey(replace)) {
                            param.result = mResourceDrawableIdMap[replace]!!.toInt()
                        }

                        // Hardcode package name to fix resource loading when patched app has modified package name
                        val identifier = context.resources.getIdentifier(replace, "drawable", "com.discord")
                        mResourceDrawableIdMap[replace] = identifier
                        param.result = identifier; return
                    }
                }
            }
        })
        XposedBridge.hookMethod(resolveRawResId, object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = param.args[0] as Context
                val str = param.args[1] as String
                val str2 = param.args[2] as String

                // Hardcode package name to fix resource loading when patched app has modified package name
                val identifier = context.resources.getIdentifier(str, str2, "com.discord")
                if(identifier > 0) {
                    param.result = identifier; return
                }
                throw IllegalArgumentException("Trying to resolve unknown sound $str")
            }
        })
    }
}
