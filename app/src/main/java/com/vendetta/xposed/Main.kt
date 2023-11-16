package com.vendetta.xposed

import android.app.AndroidAppHelper
import android.content.Context
import android.graphics.Color
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.XModuleResources
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
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

@Serializable
data class CustomLoadUrl(val enabled: Boolean, val url: String)

@Serializable
data class LoaderConfig(val customLoadUrl: CustomLoadUrl, val loadReactDevTools: Boolean)

@Serializable
data class Author(val name: String, val id: String? = null)

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
data class Theme(val id: String, val selected: Boolean, val data: ThemeData)

@Serializable
data class SysColors(
    val neutral1: List<String>,
    val neutral2: List<String>,
    val accent1: List<String>,
    val accent2: List<String>,
    val accent3: List<String>
)

class Main : IXposedHookZygoteInit, IXposedHookLoadPackage, IXposedHookInitPackageResources {
    private lateinit var modResources: XModuleResources
    private val rawColorMap = mutableMapOf<String, Int>()

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modResources = XModuleResources.createInstance(startupParam.modulePath, null)

    }
    fun hexStringToColorInt(hexString: String): Int {
    val parsed = Color.parseColor(hexString)
    return parsed.takeIf { hexString.length == 7 } ?: parsed and 0xFFFFFF or (parsed ushr 24)
    }


    fun hookThemeMethod(themeClass: Class<*>, methodName: String, themeValue: Int) {
        try {
            themeClass.getDeclaredMethod(methodName).let { method ->
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

    fun sysColorToHexString(context: Context, id: Int): String {
        val clr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.getColor(context, id)
        } else 0

        return String.format("#%06X", 0xFFFFFF and clr)
    }

    override fun handleInitPackageResources(resparam: XC_InitPackageResources.InitPackageResourcesParam) {
        if (resparam.packageName.contains(".webview")) return

        rawColorMap.forEach { (key, value) ->
            try {
                resparam.res.setReplacement("com.discord", "color", key, value)
            } catch (_: Exception) {
                Log.i("Vendetta", "No color resource with $key")
            }
        }
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        val catalystInstanceImpl = param.classLoader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl")
        val themeManager = param.classLoader.loadClass("com.discord.theme.utils.ColorUtilsKt")
        val darkTheme = param.classLoader.loadClass("com.discord.theme.DarkTheme")
        val lightTheme = param.classLoader.loadClass("com.discord.theme.LightTheme")

        val loadScriptFromAssets = with(catalystInstanceImpl.getDeclaredMethod(
            "jniLoadScriptFromAssets",
            AssetManager::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        )) {
            isAccessible = true
            this
        }

        val loadScriptFromFile = with(catalystInstanceImpl.getDeclaredMethod(
            "jniLoadScriptFromFile",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        )) {
            isAccessible = true
            this
        }
        val cache = File(param.appInfo.dataDir, "cache").also { it.mkdirs() }
        val vendetta = File(cache, "vendetta.js")
        val etag = File(cache, "vendetta_etag.txt")
        val themeJs = File(cache, "vendetta_theme.js")
        val syscolorsJs = File(cache, "vendetta_syscolors.js")

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
            if (themeFile.exists()) {
                val themeText = themeFile.readText()
                if (themeText.isNotBlank() && themeText != "{}" && themeText != "null") {
                    val theme = Json { ignoreUnknownKeys = true }.decodeFromString<Theme>(themeText)

                    theme.data.rawColors?.forEach { (key, value) -> rawColorMap[key.lowercase()] = hexStringToColorInt(value) }

                    theme.data.semanticColors?.forEach { (key, value) ->
                        val methodName =
                            "get${key.split("_").joinToString("") { it.lowercase().replaceFirstChar { it.uppercase() } }}"
                        value.forEachIndexed { index, v ->
                            when (index) {
                                0 -> hookThemeMethod(darkTheme, methodName, hexStringToColorInt(v))
                                1 -> hookThemeMethod(lightTheme, methodName, hexStringToColorInt(v))
                            }
                        }
                    }

                    if (!theme.data.rawColors.isNullOrEmpty()) {
                        val getColorCompat = themeManager.getDeclaredMethod(
                            "getColorCompat",
                            Context::class.java,
                            Int::class.javaPrimitiveType
                        )
                        XposedBridge.hookMethod(getColorCompat, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                @Suppress("DEPRECATION")
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
                val url =
                    if (config.customLoadUrl.enabled) config.customLoadUrl.url else "https://raw.githubusercontent.com/vendetta-mod/builds/master/vendetta.js"
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

                XposedBridge.invokeOriginalMethod(
                    loadScriptFromAssets,
                    param.thisObject,
                    arrayOf(modResources.assets, "assets://js/modules.js", true)
                )
                XposedBridge.invokeOriginalMethod(
                    loadScriptFromAssets,
                    param.thisObject,
                    arrayOf(modResources.assets, "assets://js/identity.js", true)
                )
                if (config.loadReactDevTools)
                    XposedBridge.invokeOriginalMethod(
                        loadScriptFromAssets,
                        param.thisObject,
                        arrayOf(modResources.assets, "assets://js/devtools.js", true)
                    )
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val context = AndroidAppHelper.currentApplication()
                    val themeString = try {
                        themeFile.readText()
                    } catch (_: Exception) {
                        "null"
                    }
                    themeJs.writeText("this.__vendetta_theme=$themeString")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val colors = mutableMapOf<String, List<String>>()
                        colors["neutral1"] = arrayOf(
                            android.R.color.system_neutral1_0,
                            android.R.color.system_neutral1_10,
                            android.R.color.system_neutral1_50,
                            android.R.color.system_neutral1_100,
                            android.R.color.system_neutral1_200,
                            android.R.color.system_neutral1_300,
                            android.R.color.system_neutral1_400,
                            android.R.color.system_neutral1_500,
                            android.R.color.system_neutral1_600,
                            android.R.color.system_neutral1_700,
                            android.R.color.system_neutral1_800,
                            android.R.color.system_neutral1_900,
                            android.R.color.system_neutral1_1000
                        ).map { sysColorToHexString(context, it) }
                        colors["neutral2"] = arrayOf(
                            android.R.color.system_neutral2_0,
                            android.R.color.system_neutral2_10,
                            android.R.color.system_neutral2_50,
                            android.R.color.system_neutral2_100,
                            android.R.color.system_neutral2_200,
                            android.R.color.system_neutral2_300,
                            android.R.color.system_neutral2_400,
                            android.R.color.system_neutral2_500,
                            android.R.color.system_neutral2_600,
                            android.R.color.system_neutral2_700,
                            android.R.color.system_neutral2_800,
                            android.R.color.system_neutral2_900,
                            android.R.color.system_neutral2_1000
                        ).map { sysColorToHexString(context, it) }
                        colors["accent1"] = arrayOf(
                            android.R.color.system_accent1_0,
                            android.R.color.system_accent1_10,
                            android.R.color.system_accent1_50,
                            android.R.color.system_accent1_100,
                            android.R.color.system_accent1_200,
                            android.R.color.system_accent1_300,
                            android.R.color.system_accent1_400,
                            android.R.color.system_accent1_500,
                            android.R.color.system_accent1_600,
                            android.R.color.system_accent1_700,
                            android.R.color.system_accent1_800,
                            android.R.color.system_accent1_900,
                            android.R.color.system_accent1_1000
                        ).map { sysColorToHexString(context, it) }
                        colors["accent2"] = arrayOf(
                            android.R.color.system_accent2_0,
                            android.R.color.system_accent2_10,
                            android.R.color.system_accent2_50,
                            android.R.color.system_accent2_100,
                            android.R.color.system_accent2_200,
                            android.R.color.system_accent2_300,
                            android.R.color.system_accent2_400,
                            android.R.color.system_accent2_500,
                            android.R.color.system_accent2_600,
                            android.R.color.system_accent2_700,
                            android.R.color.system_accent2_800,
                            android.R.color.system_accent2_900,
                            android.R.color.system_accent2_1000
                        ).map { sysColorToHexString(context, it) }
                        colors["accent3"] = arrayOf(
                            android.R.color.system_accent3_0,
                            android.R.color.system_accent3_10,
                            android.R.color.system_accent3_50,
                            android.R.color.system_accent3_100,
                            android.R.color.system_accent3_200,
                            android.R.color.system_accent3_300,
                            android.R.color.system_accent3_400,
                            android.R.color.system_accent3_500,
                            android.R.color.system_accent3_600,
                            android.R.color.system_accent3_700,
                            android.R.color.system_accent3_800,
                            android.R.color.system_accent3_900,
                            android.R.color.system_accent3_1000
                        ).map { sysColorToHexString(context, it) }

                        syscolorsJs.writeText("this.__vendetta_syscolors=${Json.encodeToString(colors)}")
                    } else {
                        syscolorsJs.writeText("this.__vendetta_syscolors=null")
                    }

                    XposedBridge.invokeOriginalMethod(
                        loadScriptFromFile,
                        param.thisObject,
                        arrayOf(themeJs.absolutePath, themeJs.absolutePath, param.args[2])
                    )
                    XposedBridge.invokeOriginalMethod(
                        loadScriptFromFile,
                        param.thisObject,
                        arrayOf(syscolorsJs.absolutePath, syscolorsJs.absolutePath, param.args[2])
                    )
                    XposedBridge.invokeOriginalMethod(
                        loadScriptFromFile,
                        param.thisObject,
                        arrayOf(vendetta.absolutePath, vendetta.absolutePath, param.args[2])
                    )
                } catch (_: Exception) {
                }
            }
        }

        XposedBridge.hookMethod(loadScriptFromAssets, patch)
        XposedBridge.hookMethod(loadScriptFromFile, patch)

        // Fighting the side effects of changing the package name
                if (param.packageName != "com.discord") {
            val getIdentifier = Resources::class.java.getDeclaredMethod(
                "getIdentifier",
                String::class.java,
                String::class.java,
                String::class.java
            )

            XposedBridge.hookMethod(getIdentifier, object : XC_MethodHook() {
                override fun beforeHookedMethod(mhparam: MethodHookParam) = with(mhparam) {
                    if (args[2] == param.packageName) args[2] = "com.discord"
                }
            })
        }
    }
}

