package com.geely.carplay.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.geely.carplay.ui.R

/**
 * 设置列表行：标题加说明，右侧按类型显示开关 / 当前值 / 箭头
 * 整行可点，车机上比小触点更好按
 */
class SettingRowView(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private val titleView: TextView
    private val descView: TextView
    private val valueView: TextView
    private val arrowView: TextView
    private val switchView: CarPlaySwitch

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.item_setting_row, this)
        setBackgroundResource(R.drawable.bg_row)
        titleView = findViewById(R.id.row_title)
        descView = findViewById(R.id.row_desc)
        valueView = findViewById(R.id.row_value)
        arrowView = findViewById(R.id.row_arrow)
        switchView = findViewById(R.id.row_switch)
    }

    fun bindToggle(
        titleResId: Int,
        descResId: Int,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit = {},
    ) {
        bindTexts(titleResId, descResId)
        switchView.visibility = View.VISIBLE
        switchView.updateChecked(checked)
        setOnClickListener {
            val next = !switchView.isChecked
            switchView.updateChecked(next)
            onCheckedChange(next)
        }
    }

    fun bindChoice(titleResId: Int, descResId: Int, value: String, onClick: () -> Unit) {
        bindTexts(titleResId, descResId)
        valueView.visibility = View.VISIBLE
        valueView.text = value
        arrowView.visibility = View.VISIBLE
        setOnClickListener { onClick() }
    }

    fun bindLink(titleResId: Int, descResId: Int, onClick: () -> Unit) {
        bindTexts(titleResId, descResId)
        arrowView.visibility = View.VISIBLE
        setOnClickListener { onClick() }
    }

    fun updateDescription(value: String) {
        descView.text = value
    }

    fun updateValue(value: String) {
        valueView.text = value
    }

    private fun bindTexts(titleResId: Int, descResId: Int) {
        titleView.setText(titleResId)
        descView.setText(descResId)
    }
}
