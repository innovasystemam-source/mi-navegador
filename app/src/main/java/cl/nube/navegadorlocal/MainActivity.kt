package cl.nube.navegadorlocal

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Navegador sencillo que corre 100% en el celular.
 * Usa el motor de Chrome que trae Android (WebView).
 * Incluye: pestañas, modo escritorio, favoritos, descargas y compartir enlace.
 */
class MainActivity : Activity() {

    private class Tab(val web: WebView, val defaultUa: String, var desktop: Boolean = false)

    private val tabs = mutableListOf<Tab>()
    private var current = -1

    private lateinit var container: FrameLayout
    private lateinit var urlBox: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tabsBtn: Button
    private lateinit var backBtn: Button
    private lateinit var fwdBtn: Button

    private val prefs by lazy { getSharedPreferences("navegador", Context.MODE_PRIVATE) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /* ------------------------------------------------------------------ */
    /* Ciclo de vida                                                       */
    /* ------------------------------------------------------------------ */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BAR
        setContentView(buildUi())
        newTab(safeUrl(intent?.data) ?: HOME)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        safeUrl(intent.data)?.let { newTab(it) }
    }

    override fun onPause() {
        tabs.getOrNull(current)?.web?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        tabs.getOrNull(current)?.web?.onResume()
    }

    override fun onDestroy() {
        tabs.forEach { it.web.destroy() }
        tabs.clear()
        super.onDestroy()
    }

    @Deprecated("Se mantiene por compatibilidad con Activity")
    override fun onBackPressed() {
        val web = tabs.getOrNull(current)?.web
        when {
            web != null && web.canGoBack() -> web.goBack()
            tabs.size > 1 -> closeTab(current)
            else -> finish()
        }
    }

    /* ------------------------------------------------------------------ */
    /* Interfaz (se arma por código, sin archivos de diseño)               */
    /* ------------------------------------------------------------------ */

    private fun barButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 17f
        b.setTextColor(Color.WHITE)
        b.setBackgroundColor(Color.TRANSPARENT)
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(0, 0, 0, 0)
        b.layoutParams = LinearLayout.LayoutParams(dp(40), dp(44))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(BAR)
        bar.setPadding(dp(4), dp(4), dp(4), dp(4))

        backBtn = barButton("◀") { tabs.getOrNull(current)?.web?.let { if (it.canGoBack()) it.goBack() } }
        fwdBtn = barButton("▶") { tabs.getOrNull(current)?.web?.let { if (it.canGoForward()) it.goForward() } }
        val reloadBtn = barButton("⟳") { tabs.getOrNull(current)?.web?.reload() }

        urlBox = EditText(this)
        urlBox.setSingleLine(true)
        urlBox.hint = "Buscar o escribir dirección"
        urlBox.textSize = 14f
        urlBox.setTextColor(Color.WHITE)
        urlBox.setHintTextColor(Color.GRAY)
        urlBox.setBackgroundColor(Color.parseColor("#17171A"))
        urlBox.setPadding(dp(12), 0, dp(12), 0)
        urlBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlBox.imeOptions = EditorInfo.IME_ACTION_GO
        urlBox.setSelectAllOnFocus(true)
        val urlParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        urlParams.setMargins(dp(4), 0, dp(4), 0)
        urlBox.layoutParams = urlParams
        urlBox.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                load(urlBox.text.toString())
                hideKeyboard(v)
                urlBox.clearFocus()
                true
            } else {
                false
            }
        }

        tabsBtn = barButton("1") { showTabs() }
        val menuBtn = barButton("⋮") { }
        menuBtn.setOnClickListener { showMenu(menuBtn) }

        bar.addView(backBtn)
        bar.addView(fwdBtn)
        bar.addView(reloadBtn)
        bar.addView(urlBox)
        bar.addView(tabsBtn)
        bar.addView(menuBtn)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.visibility = View.INVISIBLE
        progress.layoutParams = LinearLayout.LayoutParams(MATCH, dp(3))

        container = FrameLayout(this)
        container.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)

        root.addView(bar)
        root.addView(progress)
        root.addView(container)
        return root
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    /* ------------------------------------------------------------------ */
    /* Pestañas                                                            */
    /* ------------------------------------------------------------------ */

    private fun isCurrent(view: WebView?): Boolean = tabs.getOrNull(current)?.web === view

    private fun showUrl(url: String?) {
        if (!urlBox.hasFocus()) {
            urlBox.setText(if (url == null || url == "about:blank") "" else url)
        }
    }

    private fun updateNav() {
        val w = tabs.getOrNull(current)?.web
        backBtn.alpha = if (w != null && w.canGoBack()) 1f else 0.4f
        fwdBtn.alpha = if (w != null && w.canGoForward()) 1f else 0.4f
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String?) {
        val web = WebView(this)
        val tab = Tab(web, web.settings.userAgentString)

        web.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setSupportZoom(true)
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                applyDesktopViewport(view)
                if (isCurrent(view)) {
                    progress.visibility = View.VISIBLE
                    showUrl(url)
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                applyDesktopViewport(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                applyDesktopViewport(view)
                if (isCurrent(view)) {
                    progress.visibility = View.INVISIBLE
                    showUrl(url)
                    updateNav()
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (isCurrent(view)) {
                    showUrl(url)
                    updateNav()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return when (request.url.scheme) {
                    "http", "https", "about", "data", "blob" -> false
                    "mailto", "tel", "sms", "geo" -> {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        } catch (e: ActivityNotFoundException) {
                            toast("No hay una app para abrir este enlace")
                        }
                        true
                    }
                    else -> true // cualquier otro tipo de enlace se bloquea
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (isCurrent(view)) progress.progress = newProgress
            }
        }

        web.setDownloadListener { dlUrl, userAgent, contentDisposition, mime, _ ->
            startDownload(dlUrl, userAgent, contentDisposition, mime)
        }

        tabs.add(tab)
        switchTo(tabs.size - 1)
        if (url != null) web.loadUrl(url)
    }

    private fun switchTo(index: Int) {
        if (index < 0 || index >= tabs.size) return
        current = index
        val tab = tabs[index]
        container.removeAllViews()
        (tab.web.parent as? ViewGroup)?.removeView(tab.web)
        container.addView(tab.web)
        urlBox.clearFocus()
        showUrl(tab.web.url)
        tabsBtn.text = tabs.size.toString()
        progress.visibility = View.INVISIBLE
        updateNav()
    }

    private fun closeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        val tab = tabs.removeAt(index)
        container.removeView(tab.web)
        tab.web.destroy()
        if (tabs.isEmpty()) {
            current = -1
            newTab(HOME)
        } else {
            switchTo(minOf(index, tabs.size - 1))
        }
    }

    private fun showTabs() {
        val titles = tabs.mapIndexed { i, t ->
            val name = t.web.title?.takeIf { it.isNotBlank() } ?: t.web.url ?: "Pestaña nueva"
            (if (i == current) "● " else "") + name
        }
        AlertDialog.Builder(this)
            .setTitle("Pestañas (${tabs.size})")
            .setItems(titles.toTypedArray()) { _, i -> switchTo(i) }
            .setPositiveButton("Nueva") { _, _ -> newTab(HOME) }
            .setNeutralButton("Cerrar esta") { _, _ -> closeTab(current) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /* ------------------------------------------------------------------ */
    /* Navegación                                                          */
    /* ------------------------------------------------------------------ */

    private fun load(input: String) {
        tabs.getOrNull(current)?.web?.loadUrl(normalize(input))
    }

    private fun normalize(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return HOME
        if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) return s
        if (!s.contains(' ') && Regex("^[^\\s]+\\.[^\\s]{2,}(/.*)?$").matches(s)) return "https://$s"
        return "https://duckduckgo.com/?q=" + Uri.encode(s)
    }

    private fun safeUrl(uri: Uri?): String? {
        val scheme = uri?.scheme ?: return null
        return if (scheme == "http" || scheme == "https") uri.toString() else null
    }

    /* ------------------------------------------------------------------ */
    /* Menú, modo escritorio, favoritos, descargas, compartir              */
    /* ------------------------------------------------------------------ */

    private fun showMenu(anchor: View) {
        val tab = tabs.getOrNull(current) ?: return
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Nueva pestaña")
        menu.menu.add(0, 2, 1, if (tab.desktop) "✓ Modo escritorio" else "Modo escritorio")
        menu.menu.add(0, 3, 2, "Agregar a favoritos")
        menu.menu.add(0, 4, 3, "Favoritos")
        menu.menu.add(0, 5, 4, "Compartir enlace")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> newTab(HOME)
                2 -> toggleDesktop()
                3 -> addBookmark()
                4 -> showBookmarks()
                5 -> shareLink()
            }
            true
        }
        menu.show()
    }

    /**
     * En modo escritorio, Chrome ignora el ancho de pantalla real y le dice a la página
     * que mide más de 1000 px. Aquí hacemos lo mismo cambiando la etiqueta "viewport".
     */
    private fun applyDesktopViewport(view: WebView) {
        val tab = tabs.firstOrNull { it.web === view } ?: return
        if (!tab.desktop) return
        view.evaluateJavascript(DESKTOP_VIEWPORT_JS, null)
    }

    private fun toggleDesktop() {
        val tab = tabs.getOrNull(current) ?: return
        tab.desktop = !tab.desktop
        tab.web.settings.userAgentString = if (tab.desktop) DESKTOP_UA else tab.defaultUa
        tab.web.reload()
        toast(if (tab.desktop) "Modo escritorio activado" else "Modo móvil activado")
    }

    private fun loadBookmarks(): MutableList<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        try {
            val arr = JSONArray(prefs.getString("bookmarks", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Pair(o.getString("t"), o.getString("u")))
            }
        } catch (e: Exception) {
            // si el dato está dañado, se empieza con la lista vacía
        }
        return list
    }

    private fun saveBookmarks(list: List<Pair<String, String>>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.first).put("u", it.second)) }
        prefs.edit().putString("bookmarks", arr.toString()).apply()
    }

    private fun addBookmark() {
        val web = tabs.getOrNull(current)?.web ?: return
        val url = web.url
        if (url.isNullOrBlank() || url == "about:blank") {
            toast("No hay una página para guardar")
            return
        }
        val list = loadBookmarks()
        if (list.any { it.second == url }) {
            toast("Ya está en favoritos")
            return
        }
        val title = web.title?.takeIf { it.isNotBlank() } ?: url
        list.add(Pair(title, url))
        saveBookmarks(list)
        toast("Guardado en favoritos")
    }

    private fun showBookmarks() {
        val list = loadBookmarks()
        if (list.isEmpty()) {
            toast("Aún no tienes favoritos")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Favoritos")
            .setItems(list.map { it.first }.toTypedArray()) { _, i -> load(list[i].second) }
            .setNeutralButton("Borrar todos") { _, _ ->
                saveBookmarks(emptyList())
                toast("Favoritos borrados")
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun shareLink() {
        val url = tabs.getOrNull(current)?.web?.url ?: return
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(send, "Compartir enlace"))
    }

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mime: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("Este tipo de descarga no es compatible")
            return
        }
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
            if (!mime.isNullOrBlank()) req.setMimeType(mime)
            if (!userAgent.isNullOrBlank()) req.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            req.setTitle(name)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Descargando $name")
        } catch (e: Exception) {
            toast("No se pudo iniciar la descarga")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val HOME = "https://duckduckgo.com"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.0.0 Safari/537.36"
        val BAR: Int = Color.parseColor("#26262C")

        // Ancho (en píxeles) con el que se muestran las páginas en modo escritorio.
        // Más alto = se parece más a un computador, pero el texto se ve más pequeño.
        const val DESKTOP_WIDTH = 1280

        val DESKTOP_VIEWPORT_JS: String = """
            (function () {
              try {
                function fix() {
                  var m = document.querySelector('meta[name=viewport]');
                  if (!m) {
                    m = document.createElement('meta');
                    m.name = 'viewport';
                    (document.head || document.documentElement).appendChild(m);
                  }
                  if (m.getAttribute('content') !== 'width=$DESKTOP_WIDTH') {
                    m.setAttribute('content', 'width=$DESKTOP_WIDTH');
                  }
                }
                fix();
                if (window.__nubeObs) return;
                window.__nubeObs = new MutationObserver(fix);
                window.__nubeObs.observe(document.documentElement, { childList: true, subtree: true });
                setTimeout(function () {
                  if (window.__nubeObs) { window.__nubeObs.disconnect(); window.__nubeObs = null; }
                }, 10000);
              } catch (e) {}
            })();
        """.trimIndent()
    }
}
