package com.vendetta.xposed

import android.content.Context
import android.graphics.Color
import android.content.res.AssetManager
import android.content.res.XModuleResources
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.IXposedHookInitPackageResources
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_InitPackageResources
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
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
data class Author(
    val name: String,
    val id: String? = null
)
@Serializable
data class ThemeData(
    val name: String,
    val description: String? = null,
    val authors: List<Author>? = null,
    val spec: Int,
    val semanticColors: Map<String, List<String>>? = null,
    val rawColors: Map<String, String>? = null
)
@Serializable
data class Theme(
    val id: String,
    val selected: Boolean,
    val data: ThemeData
)

class Main : IXposedHookZygoteInit, IXposedHookLoadPackage, IXposedHookInitPackageResources {
    private lateinit var modResources: XModuleResources
    private val rawColorMap = mutableMapOf<String, Int>()

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modResources = XModuleResources.createInstance(startupParam.modulePath, null)
    }

    fun hexStringToColorInt(hexString: String): Int {
        val parsed = Color.parseColor(hexString)
        // Convert 0xRRGGBBAA to 0XAARRGGBB
        return parsed.takeIf { hexString.length == 7 } ?: parsed and 0xFFFFFF or (parsed ushr 24)
    }

    fun hookThemeMethod(themeClass: Class<*>, methodName: String, themeValue: Int) {
        try {
            themeClass.getDeclaredMethod(methodName).let { method ->
                // Log.i("Hooking $methodName -> ${themeValue.toString(16)}")
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = themeValue
                    }
                })
            }
        } catch (ex: NoSuchMethodException) {
            // do nothing
        }
    }

    override fun handleInitPackageResources(resparam: XC_InitPackageResources.InitPackageResourcesParam) {
        if (resparam.packageName == "com.google.android.webview") return

        // rawColorMap is initialized during handleLoadPackage
        rawColorMap.forEach { (key, value) -> 
            try {
                resparam.res.setReplacement("com.discord", "color", key, value)
            } catch (_: Exception) {
                Log.i("Vendetta", "No color resource with $key")
            }
        }
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName == "com.google.android.webview") return
        
        val catalystInstanceImpl = param.classLoader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl")
        val resourceDrawableIdHelper = param.classLoader.loadClass("com.facebook.react.views.imagehelper.ResourceDrawableIdHelper")
        val soundManagerModule = param.classLoader.loadClass("com.discord.sounds.SoundManagerModule")
        val themeManager = param.classLoader.loadClass("com.discord.theme.utils.ColorUtilsKt")
        val darkTheme = param.classLoader.loadClass("com.discord.theme.DarkTheme")
        val lightTheme = param.classLoader.loadClass("com.discord.theme.LightTheme")

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
        val etag = File(cache, "vendetta_etag.txt")
        val themeJs = File(cache, "vendetta_theme.js")

        lateinit var config: LoaderConfig
        val files = File(param.appInfo.dataDir, "files").also { it.mkdirs() }
        val configFile = File(files, "vendetta_loader.json")
        val themeFile = File(files, "vendetta_theme.json")

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
            // TODO: This is questionable
            if (themeFile.exists()) {
                val themeText = themeFile.readText()
                if (themeText.isNotBlank() && themeText != "{}" && themeText != "null") {
                    val theme = Json { ignoreUnknownKeys = true }.decodeFromString<Theme>(themeText)

                    // Apply rawColors
                    theme.data.rawColors?.forEach { (key, value) -> rawColorMap[key.lowercase()] = hexStringToColorInt(value) }
                    
                    // Apply semanticColors
                    theme.data.semanticColors?.forEach { (key, value) ->
                        // TEXT_NORMAL -> getTextNormal
                        val methodName = "get${key.split("_").joinToString("") { it.lowercase().replaceFirstChar { it.uppercase() } }}"
                        value.forEachIndexed { index, v ->
                            when (index) {
                                0 -> hookThemeMethod(darkTheme, methodName, hexStringToColorInt(v))
                                1 -> hookThemeMethod(lightTheme, methodName, hexStringToColorInt(v))
                            }
                        }
                    }

                    // If there's any rawColors value, hook the color getter
                    if (!theme.data.rawColors.isNullOrEmpty()) {
                        val getColorCompat = themeManager.getDeclaredMethod(
                            "getColorCompat", 
                            Context::class.java, 
                            Int::class.javaPrimitiveType
                        )
                        XposedBridge.hookMethod(getColorCompat, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                param.result = (param.args[0] as Context).resources.getColor(param.args[1] as Int)
                            }
                        })
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e("Vendetta", "Unable to find/parse theme", ex)
        }

        val patch = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val url = if (config.customLoadUrl.enabled) config.customLoadUrl.url else "https://raw.githubusercontent.com/vendetta-mod/builds/master/vendetta.js"
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000

                    if (etag.exists() && vendetta.exists()) {
                        conn.setRequestProperty("If-None-Match", etag.readText())
                    }

                    if (conn.responseCode == 200) {
                        conn.inputStream.use { input ->
                            vendetta.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        val header = conn.getHeaderField("Etag")
                        if (header != null) etag.writeText(header)
                    }
                } catch (e: Exception) {
                    Log.e("Vendetta", "Failed to download Vendetta from $url")
                }

                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(modResources.assets, "assets://js/modules.js", true))
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(modResources.assets, "assets://js/identity.js", true))
                if (config.loadReactDevTools)
                    XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(modResources.assets, "assets://js/devtools.js", true))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val themeString = try { themeFile.readText() } catch (_: Exception) { "null" }
                    themeJs.writeText("this.__vendetta_theme=$themeString")

                    XposedBridge.invokeOriginalMethod(loadScriptFromFile, param.thisObject, arrayOf(themeJs.absolutePath, themeJs.absolutePath, param.args[2]))
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
