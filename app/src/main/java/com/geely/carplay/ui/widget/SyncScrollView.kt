package com.geely.carplay.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * 带滚动回调的 ScrollView : API18 没有 setOnScrollChangeListener
 */
class SyncScrollView(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {

    var onScrolled: ((scrollY: Int) -> Unit)? = null

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        onScrolled?.invoke(top)
    }
}
