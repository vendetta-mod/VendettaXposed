package com.vendetta.xposed

import android.app.AndroidAppHelper
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.XModuleResources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Typeface.CustomFallbackBuilder
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import android.util.Log
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import de.robv.android.xposed.IXposedHookInitPackageResources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_InitPackageResources
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL


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
    val rawColors: Map<String, String>? = null,
    val fonts: Map<String, String>? = null
)
@Serializable
data class Theme(
    val id: String,
    val selected: Boolean,
    val data: ThemeData
)

@Serializable
data class SysColors(
    val neutral1: List<String>,
    val neutral2: List<String>,
    val accent1: List<String>,
    val accent2: List<String>,
    val accent3: List<String>
)

class Main : IXposedHookZygoteInit, IXposedHookLoadPackage, IXposedHookInitPackageResources {
    private val EXTENSIONS = arrayOf("", "_bold", "_italic", "_bold_italic")
    private val FILE_EXTENSIONS = arrayOf(".ttf", ".otf")
    private val FONTS_ASSET_PATH = "fonts/"

    private lateinit var modResources: XModuleResources
    private lateinit var cache: File
    private val rawColorMap = mutableMapOf<String, Int>()
    private val fontMap = mutableMapOf<String, String>()

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

    fun sysColorToHexString(context: Context, id: Int): String {
        val clr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.getColor(context, id)
        } else 0

        return java.lang.String.format("#%06X", 0xFFFFFF and clr)
    }

    override fun handleInitPackageResources(resparam: XC_InitPackageResources.InitPackageResourcesParam) {
        if (resparam.packageName.contains(".webview")) return

        // rawColorMap is initialized during handleLoadPackage
        rawColorMap.forEach { (key, value) -> 
            try {
                resparam.res.setReplacement("com.discord", "color", key, value)
            } catch (_: Exception) {
                Log.i("Vendetta", "No color resource with $key")
            }
        }
    }
    private fun createAssetTypefaceWithFallbacks(
        fontFamilyNames: Array<String>,
        style: Int,
        assetManager: AssetManager
    ): Typeface? {
        val fontFamilies: MutableList<FontFamily> = ArrayList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Iterate over the list of fontFamilyNames, constructing new FontFamily objects
            // for use in the CustomFallbackBuilder below.
            for (fontFamilyName in fontFamilyNames) {
                if(!fontMap[fontFamilyName].isNullOrEmpty()) {
                    val fileName = java.lang.StringBuilder()
                        .append(cache.absolutePath)
                        .append("/fonts/")
                        .append(fontMap[fontFamilyName])
                        .toString()

                    try {
                        val font = Font.Builder(File(fileName)).build()
                        val family = FontFamily.Builder(font).build()
                        fontFamilies.add(family)
                    } catch (e: java.lang.RuntimeException) {
                        // If the typeface asset does not exist, try another extension.
                    } catch (e: IOException) {
                        // If the font asset does not exist, try another extension.
                    }
                }
                for (fileExtension in FILE_EXTENSIONS) {
                    val fileName = java.lang.StringBuilder()
                        .append(FONTS_ASSET_PATH)
                        .append(fontFamilyName)
                        .append(fileExtension)
                        .toString()
                    try {
                        val font = Font.Builder(assetManager, fileName).build()
                        val family = FontFamily.Builder(font).build()
                        fontFamilies.add(family)
                    } catch (e: java.lang.RuntimeException) {
                        // If the typeface asset does not exist, try another extension.
                        continue
                    } catch (e: IOException) {
                        // If the font asset does not exist, try another extension.
                        continue
                    }
                }
            }

            // If there's some problem constructing fonts, fall back to the default behavior.
            if (fontFamilies.size == 0) {
                return createAssetTypeface(fontFamilyNames[0], style, assetManager)
            }
            val fallbackBuilder = CustomFallbackBuilder(fontFamilies[0])
            for (i in 1 until fontFamilies.size) {
                fallbackBuilder.addCustomFallback(fontFamilies[i])
            }
            return fallbackBuilder.build()
        }
        return null
    }
    private fun createAssetTypeface(
        fontFamilyName_: String, style: Int, assetManager: AssetManager
    ): Typeface? {
        // This logic attempts to safely check if the frontend code is attempting to use
        // fallback fonts, and if it is, to use the fallback typeface creation logic.
        var fontFamilyName: String = fontFamilyName_
        val fontFamilyNames =
            fontFamilyName.split(",".toRegex())
                .dropLastWhile { it.isEmpty() }
                .toTypedArray()
        for (i in fontFamilyNames.indices) {
            fontFamilyNames[i] = fontFamilyNames[i].trim()
        }

        // If there are multiple font family names:
        //   For newer versions of Android, construct a Typeface with fallbacks
        //   For older versions of Android, ignore all the fallbacks and just use the first font family
        if (fontFamilyNames.size > 1) {
            fontFamilyName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return createAssetTypefaceWithFallbacks(fontFamilyNames, style, assetManager)
            } else {
                fontFamilyNames[0]
            }
        }

        val extension = EXTENSIONS[style]
        if(!fontMap[fontFamilyName + extension].isNullOrEmpty()) {
            val fileName = java.lang.StringBuilder()
                .append(cache.absolutePath)
                .append("/fonts/")
                .append(fontMap[fontFamilyName + extension])
                .toString()
            return Typeface.createFromFile(fileName)
        }
        // Lastly, after all those checks above, this is the original RN logic for
        // getting the typeface.
        for (fileExtension in FILE_EXTENSIONS) {
            val fileName = java.lang.StringBuilder()
                .append(FONTS_ASSET_PATH)
                .append(fontFamilyName)
                .append(extension)
                .append(fileExtension)
                .toString()
            return try {
                Typeface.createFromAsset(assetManager, fileName)
            } catch (e: java.lang.RuntimeException) {
                // If the typeface asset does not exist, try another extension.
                continue
            }
        }
        return Typeface.create(fontFamilyName, style)
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName.contains(".webview")) return

        val catalystInstanceImpl = param.classLoader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl")
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

        cache = File(param.appInfo.dataDir, "cache").also { it.mkdirs() }
        val vendetta = File(cache, "vendetta.js")
        val etag = File(cache, "vendetta_etag.txt")
        val themeJs = File(cache, "vendetta_theme.js")
        val syscolorsJs = File(cache, "vendetta_syscolors.js")

        lateinit var config: LoaderConfig
        val files = File(param.appInfo.dataDir, "files").also { it.mkdirs() }
        val fontsDir = File(cache, "fonts").also { it.mkdirs() }
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
                try {
                    if (themeFile.exists()) {
                        val themeText = themeFile.readText()
                        if (themeText.isNotBlank() && themeText != "{}" && themeText != "null") {
                            val theme =
                                Json { ignoreUnknownKeys = true }.decodeFromString<Theme>(themeText)

                            theme.data.fonts?.forEach { (key, value) ->
                                val name = URLUtil.guessFileName(value, null, null)
                                val file = File(fontsDir.absolutePath, name)
                                fontMap[key] = name
                                if (file.exists())
                                    return@forEach

                                val conn = URL(value).openConnection() as HttpURLConnection
                                conn.connectTimeout = 3000
                                conn.readTimeout = 3000
                                if (conn.responseCode == 200) {
                                    conn.inputStream.use { input ->
                                        file.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }catch (e: Exception) {
                    Log.e("Vendetta", "Failed to setup fonts")
                }

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
                    val context = AndroidAppHelper.currentApplication()
                    val themeString = try { themeFile.readText() } catch (_: Exception) { "null" }
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

                    XposedBridge.invokeOriginalMethod(loadScriptFromFile, param.thisObject, arrayOf(themeJs.absolutePath, themeJs.absolutePath, param.args[2]))
                    XposedBridge.invokeOriginalMethod(loadScriptFromFile, param.thisObject, arrayOf(syscolorsJs.absolutePath, syscolorsJs.absolutePath, param.args[2]))
                    XposedBridge.invokeOriginalMethod(loadScriptFromFile, param.thisObject, arrayOf(vendetta.absolutePath, vendetta.absolutePath, param.args[2]))
                } catch (_: Exception) {}
            }
        }

        XposedBridge.hookMethod(loadScriptFromAssets, patch)
        XposedBridge.hookMethod(loadScriptFromFile, patch)

        XposedHelpers.findAndHookMethod("com.facebook.react.views.text.ReactFontManager", param.classLoader, "createAssetTypeface",
                String::class.java,
                Int::class.java,
                "android.content.res.AssetManager", object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Typeface? {
                        val fontFamilyName: String = param.args[0].toString();
                        val style: Int = param.args[1] as Int;
                        val assetManager: AssetManager = param.args[2] as AssetManager;
                        return createAssetTypeface(fontFamilyName, style, assetManager)
                    }
                });
        // Fighting the side effects of changing the package name
        if (param.packageName != "com.discord") {
            val getIdentifier = Resources::class.java.getDeclaredMethod(
                "getIdentifier", 
                String::class.java,
                String::class.java,
                String::class.java
            );

            XposedBridge.hookMethod(getIdentifier, object: XC_MethodHook() {
                override fun beforeHookedMethod(mhparam: MethodHookParam) = with(mhparam) {
                    if (args[2] == param.packageName) args[2] = "com.discord"
                }
            })
        }
    }
}
