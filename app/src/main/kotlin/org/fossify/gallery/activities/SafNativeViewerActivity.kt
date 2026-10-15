package org.fossify.gallery.activities

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.bumptech.glide.Glide
import org.fossify.gallery.R
import org.fossify.gallery.helpers.SafNativeSession

/** Full-screen swipe viewer for content:// files, with Gallery's app theme. */
class SafNativeViewerActivity : SimpleActivity() {
    private lateinit var toolbar: Toolbar
    private lateinit var pager: ViewPager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SafNativeSession.items.isEmpty()) { finish(); return }
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = Toolbar(this).apply {
            setNavigationIcon(org.fossify.commons.R.drawable.ic_arrow_left_vector)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.saf_native_viewer)
            setOnMenuItemClickListener {
                if (it.itemId == R.id.saf_native_share) { shareCurrent(); true } else false
            }
        }
        holder.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            (56 * resources.displayMetrics.density).toInt()))
        pager = ViewPager(this).apply { adapter = PhotoPages() }
        holder.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(holder)
        setupEdgeToEdge(padTopSystem = listOf(toolbar), padBottomSystem = listOf(pager))
        val initial = intent.getIntExtra(EXTRA_POSITION, 0).coerceIn(0, SafNativeSession.items.lastIndex)
        pager.currentItem = initial
        toolbar.title = SafNativeSession.items[initial].name
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                toolbar.title = SafNativeSession.items.getOrNull(position)?.name ?: ""
            }
        })
    }

    private fun shareCurrent() {
        val entry = SafNativeSession.items.getOrNull(pager.currentItem) ?: return
        val uri = Uri.parse(entry.path)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, entry.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, getString(R.string.saf_share)))
    }

    private inner class PhotoPages : PagerAdapter() {
        override fun getCount() = SafNativeSession.items.size
        override fun isViewFromObject(view: View, obj: Any) = view === obj
        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val view = ImageView(this@SafNativeViewerActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = SafNativeSession.items[position].name
            }
            container.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
            Glide.with(this@SafNativeViewerActivity).load(Uri.parse(SafNativeSession.items[position].path)).into(view)
            return view
        }
        override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
            val view = obj as ImageView
            Glide.with(this@SafNativeViewerActivity).clear(view)
            container.removeView(view)
        }
    }

    companion object { const val EXTRA_POSITION = "org.fossify.gallery.saf.native.position" }
}
