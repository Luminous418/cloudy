package dev.ncatt.ota.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.LayoutInflaterCompat
import dev.ncatt.ota.R

/**
 * Applies the bundled One UI Sans family to every [TextView] as it is inflated.
 *
 * Why this exists: setting `android:fontFamily` in [R.style.Theme_Cloudy] only reaches views
 * whose style resolves the theme attribute. The oneui-design widgets (CardItemView, Separator,
 * ToolbarLayout's title/subtitle) build their TextViews in code with a hardcoded
 * `fontFamily="sec"`, so a theme-level font never touches them - the tabs and card rows would
 * keep rendering in the platform sans while only our own TextViews changed. Hooking the
 * LayoutInflater is the one interception point that covers both.
 *
 * Fragments inherit this: `Fragment.onGetLayoutInflater` clones the activity's inflater, and
 * `cloneInContext` carries the factory over. Dialogs built from a *different* context (the
 * AlertDialog themed wrapper) do not - [apply] is exposed so those can opt in manually.
 */
object OneUiFont {

    /**
     * Must be called BEFORE `super.onCreate()`. AppCompat installs its own Factory2 during
     * `super.onCreate()` and [LayoutInflaterCompat.setFactory2] throws if a factory is already
     * set on that inflater.
     */
    fun install(activity: AppCompatActivity) {
        val base = activity.delegate
        LayoutInflaterCompat.setFactory2(
            activity.layoutInflater,
            object : LayoutInflater.Factory2 {
                override fun onCreateView(
                    parent: View?,
                    name: String,
                    context: Context,
                    attrs: AttributeSet
                ): View? =
                    // Let AppCompat do the real construction (it maps TextView ->
                    // AppCompatTextView etc.), then restyle whatever comes back.
                    base.createView(parent, name, context, attrs)?.also { apply(it) }

                override fun onCreateView(
                    name: String,
                    context: Context,
                    attrs: AttributeSet
                ): View? = onCreateView(null, name, context, attrs)
            }
        )
    }

    /** Swaps in the bundled family while preserving the view's existing bold/italic style. */
    fun apply(view: View) {
        if (view !is TextView) return
        val family = font(view.context) ?: return
        // setTypeface(tf, style) keeps weight selection working: passing BOLD against a
        // family-backed Typeface picks the 700 face rather than synthesising a fake bold.
        view.setTypeface(family, view.typeface?.style ?: Typeface.NORMAL)
    }

    /** Walks a hierarchy that was inflated without the factory (e.g. AlertDialog custom views). */
    fun applyRecursively(view: View) {
        apply(view)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) applyRecursively(view.getChildAt(i))
        }
    }

    private var cached: Typeface? = null

    private fun font(context: Context): Typeface? {
        cached?.let { return it }
        // getFont hits the resource loader; cache it so we are not re-parsing the family
        // once per inflated row.
        return runCatching { ResourcesCompat.getFont(context, R.font.oneui_sans) }
            .getOrNull()
            ?.also { cached = it }
    }
}
