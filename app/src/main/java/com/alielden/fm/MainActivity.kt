package com.alielden.fm

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.github.junrar.Archive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.*
import java.util.concurrent.Executors
import java.util.zip.*

object Arc {
    private fun safe(d: File, name: String): File {
        val f = File(d, name.replace('\\', '/'))
        val c = f.canonicalPath
        val base = d.canonicalPath
        if (c != base && !c.startsWith(base + File.separator)) throw IOException("مسار غير آمن: $name")
        return f
    }

    private fun write(out: File, input: InputStream) {
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { input.copyTo(it) }
    }

    fun extract(src: File, dest: File) {
        val n = src.name.lowercase()
        dest.mkdirs()
        when {
            n.endsWith(".zip") || n.endsWith(".apk") || n.endsWith(".jar") ->
                ZipInputStream(BufferedInputStream(FileInputStream(src))).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val out = safe(dest, e.name)
                        if (e.isDirectory) out.mkdirs() else write(out, z)
                    }
                }
            n.endsWith(".7z") ->
                SevenZFile(src).use { sz ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val e = sz.nextEntry ?: break
                        val out = safe(dest, e.name)
                        if (e.isDirectory) { out.mkdirs(); continue }
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { o ->
                            while (true) {
                                val r = sz.read(buf)
                                if (r < 0) break
                                o.write(buf, 0, r)
                            }
                        }
                    }
                }
            n.endsWith(".tar") || n.endsWith(".tar.gz") || n.endsWith(".tgz") -> {
                var ins: InputStream = BufferedInputStream(FileInputStream(src))
                if (!n.endsWith(".tar")) ins = GZIPInputStream(ins)
                TarArchiveInputStream(ins).use { t ->
                    while (true) {
                        val e = t.nextEntry ?: break
                        val out = safe(dest, e.name)
                        if (e.isDirectory) out.mkdirs() else write(out, t)
                    }
                }
            }
            n.endsWith(".gz") ->
                GZIPInputStream(BufferedInputStream(FileInputStream(src))).use {
                    write(File(dest, src.name.dropLast(3)), it)
                }
            n.endsWith(".rar") ->
                Archive(src).use { a ->
                    while (true) {
                        val h = a.nextFileHeader() ?: break
                        val out = safe(dest, h.fileName)
                        if (h.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { o -> a.extractFile(h, o) }
                        }
                    }
                }
            else -> throw IOException("صيغة غير مدعومة")
        }
    }

    fun zip(files: List<File>, out: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { z ->
            fun add(f: File, p: String) {
                if (f.isDirectory) {
                    val kids = f.listFiles() ?: return
                    if (kids.isEmpty()) { z.putNextEntry(ZipEntry("$p/")); z.closeEntry() }
                    kids.forEach { add(it, "$p/${it.name}") }
                } else {
                    z.putNextEntry(ZipEntry(p))
                    FileInputStream(f).use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
            files.forEach { add(it, it.name) }
        }
    }

    fun tgz(files: List<File>, out: File) {
        TarArchiveOutputStream(GZIPOutputStream(BufferedOutputStream(FileOutputStream(out)))).use { t ->
            t.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            fun add(f: File, p: String) {
                t.putArchiveEntry(TarArchiveEntry(f, p))
                if (f.isDirectory) {
                    t.closeArchiveEntry()
                    f.listFiles()?.forEach { add(it, "$p/${it.name}") }
                } else {
                    FileInputStream(f).use { it.copyTo(t) }
                    t.closeArchiveEntry()
                }
            }
            files.forEach { add(it, it.name) }
        }
    }
}

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var panes: Array<Pane>
    private var active = 0
    private val root get() = Environment.getExternalStorageDirectory()

    inner class Pane(val idx: Int) {
        var dir: File = root
        val sel = LinkedHashSet<File>()
        var items: List<File?> = emptyList()
        val path = TextView(this@MainActivity)
        val list = ListView(this@MainActivity)
        val box = LinearLayout(this@MainActivity)

        private val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int): Any? = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                val tv = (v as? TextView) ?: TextView(this@MainActivity).apply {
                    setPadding(24, 20, 24, 20); textSize = 16f; setTextColor(Color.WHITE)
                }
                val f = items[p]
                tv.text = if (f == null) "⬆  .." else
                    (if (f.isDirectory) "📁  " else "📄  ") + f.name +
                        (if (f.isFile) "   (" + size(f.length()) + ")" else "")
                tv.setBackgroundColor(if (f != null && f in sel) Color.parseColor("#6A4CFF") else Color.TRANSPARENT)
                return tv
            }
        }

        init {
            box.orientation = LinearLayout.VERTICAL
            path.setTextColor(Color.WHITE); path.setPadding(16, 12, 16, 12); path.textSize = 13f
            box.addView(path)
            box.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
            list.adapter = adapter
            list.setOnItemClickListener { _, _, pos, _ ->
                active = idx; markActive()
                val f = items[pos]
                when {
                    f == null -> { dir.parentFile?.let { dir = it }; sel.clear(); refresh() }
                    f.isDirectory -> { dir = f; sel.clear(); refresh() }
                    else -> toggle(f)
                }
            }
            list.setOnItemLongClickListener { _, _, pos, _ ->
                active = idx; markActive()
                items[pos]?.let { toggle(it) }
                true
            }
        }

        private fun toggle(f: File) { if (!sel.remove(f)) sel.add(f); adapter.notifyDataSetChanged() }

        fun refresh() {
            val kids = (dir.listFiles() ?: emptyArray())
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            items = (if (dir.parentFile != null) listOf<File?>(null) else emptyList()) + kids
            path.text = dir.path
            adapter.notifyDataSetChanged()
        }
    }

    private fun size(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${b / 1024} KB"
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    private fun markActive() {
        panes.forEach { it.path.setBackgroundColor(if (it.idx == active) 0xFF6A4CFF.toInt() else 0xFF333333.toInt()) }
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun ask(title: String, def: String, ok: (String) -> Unit) {
        val et = EditText(this); et.setText(def)
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("موافق") { _, _ -> val t = et.text.toString().trim(); if (t.isNotEmpty()) ok(t) }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun selected(): List<File> {
        val s = panes[active].sel.toList()
        if (s.isEmpty()) toast("حدد عناصر أولاً (لمسة طويلة)")
        return s
    }

    private fun job(action: () -> Unit) {
        io.execute {
            try { action(); toast("تم ✓") } catch (e: Exception) { toast("خطأ: ${e.message}") }
            runOnUiThread { panes.forEach { it.sel.clear(); it.refresh() } }
        }
    }

    private fun baseName(n: String): String {
        val l = n.lowercase()
        for (e in listOf(".tar.gz", ".tgz", ".zip", ".apk", ".jar", ".7z", ".rar", ".tar", ".gz"))
            if (l.endsWith(e)) return n.dropLast(e.length)
        return n + "_out"
    }

    private fun btn(label: String, click: () -> Unit) = Button(this).apply {
        text = label; setOnClickListener { click() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        panes = arrayOf(Pane(0), Pane(1))

        val bar = LinearLayout(this)
        bar.addView(btn("نسخ") {
            val f = selected(); val dst = panes[1 - active].dir
            if (f.isNotEmpty()) job { f.forEach { it.copyRecursively(File(dst, it.name), true) } }
        })
        bar.addView(btn("نقل") {
            val f = selected(); val dst = panes[1 - active].dir
            if (f.isNotEmpty()) job {
                f.forEach {
                    if (dst.canonicalPath.startsWith(it.canonicalPath)) throw IOException("لا يمكن النقل داخل نفسه")
                    val t = File(dst, it.name)
                    if (!it.renameTo(t)) { it.copyRecursively(t, true); it.deleteRecursively() }
                }
            }
        })
        bar.addView(btn("حذف") {
            val f = selected()
            if (f.isNotEmpty()) AlertDialog.Builder(this).setTitle("حذف ${f.size} عنصر؟")
                .setPositiveButton("حذف") { _, _ -> job { f.forEach { it.deleteRecursively() } } }
                .setNegativeButton("إلغاء", null).show()
        })
        bar.addView(btn("تسمية") {
            val f = selected()
            if (f.size == 1) ask("اسم جديد", f[0].name) { n -> job { f[0].renameTo(File(f[0].parentFile, n)) } }
            else if (f.size > 1) toast("حدد عنصراً واحداً")
        })
        bar.addView(btn("مجلد") {
            ask("مجلد جديد", "") { n -> val d = panes[active].dir; job { File(d, n).mkdirs() } }
        })
        bar.addView(btn("ضغط") {
            val f = selected()
            if (f.isNotEmpty()) ask("اسم الأرشيف (.zip أو .tar.gz)", f[0].name + ".zip") { n ->
                val out = File(panes[active].dir, n)
                job { if (n.lowercase().endsWith(".tar.gz")) Arc.tgz(f, out) else Arc.zip(f, out) }
            }
        })
        bar.addView(btn("فك") {
            val f = selected()
            if (f.size == 1) {
                val dst = File(panes[1 - active].dir, baseName(f[0].name))
                job { Arc.extract(f[0], dst) }
            } else if (f.size > 1) toast("حدد أرشيفاً واحداً")
        })
        bar.addView(btn("الكل") {
            val p = panes[active]; p.sel.clear(); p.sel.addAll(p.items.filterNotNull()); p.refresh()
        })

        val scroll = HorizontalScrollView(this); scroll.addView(bar)
        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setBackgroundColor(0xFF121212.toInt())
        main.setPadding(0, 60, 0, 0)
        main.addView(scroll)
        panes.forEach { main.addView(it.box, LinearLayout.LayoutParams(-1, 0, 1f)) }
        setContentView(main)
        markActive()

        if (!Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    override fun onResume() {
        super.onResume()
        panes.forEach { it.refresh() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val p = panes[active]
        val up = p.dir.parentFile
        if (up != null && p.dir != root) { p.dir = up; p.sel.clear(); p.refresh() } else super.onBackPressed()
    }
}
