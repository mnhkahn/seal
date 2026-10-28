package com.cyeam.seal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSpinner
import coil.ImageLoader
import coil.load
import com.cyeam.seal.ocr.Candidate

/** An anchored, image-rich dropdown matching the website's candidate selector. */
class CandidateDropdown(
    context: Context,
    candidates: List<Candidate>,
    selected: Int,
    imageLoader: ImageLoader,
    label: (Candidate) -> String,
    onSelect: (Int) -> Unit
) : AppCompatSpinner(context, Spinner.MODE_DROPDOWN) {
    init {
        minimumHeight = dp(56)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = RippleDrawable(ColorStateList.valueOf(0x146C757D), outline(), null)
        androidx.core.view.ViewCompat.setBackgroundTintList(this, null)
        setPopupBackgroundDrawable(outline())
        dropDownVerticalOffset = dp(6)
        addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            // Keep the list aligned to this field even in narrow/multilingual layouts.
            dropDownWidth = right - left
            dropDownHorizontalOffset = -paddingLeft
        }
        val choice = selected.coerceIn(candidates.indices)
        contentDescription = context.getString(R.string.candidates) + ": " + label(candidates[choice])
        adapter = object : BaseAdapter() {
            override fun getCount() = candidates.size
            override fun getItem(position: Int) = candidates[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                candidateRow(candidates[position], compact = true, chosen = false, imageLoader, label)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                candidateRow(candidates[position], compact = false, chosen = position == choice, imageLoader, label)
        }
        setSelection(choice, false)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Binding the current result must not emit a user-selection event.
                if (position != choice) onSelect(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun candidateRow(
        candidate: Candidate, compact: Boolean, chosen: Boolean,
        imageLoader: ImageLoader, label: (Candidate) -> String
    ): View {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (compact) 48 else 68)
            setPadding(dp(if (compact) 0 else 12), dp(8), dp(if (compact) 0 else 12), dp(8))
            if (chosen) setBackgroundColor(0xFFEEF3F8.toInt())
        }
        val size = dp(if (compact) 28 else 36)
        for (url in listOf(candidate.glyphImage, candidate.unicodeImage).filter { it.isNotBlank() }.distinct()) {
            line.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = GradientDrawable().apply {
                    setColor(Color.WHITE); cornerRadius = dp(4).toFloat(); setStroke(dp(1), 0xFFE0E3E6.toInt())
                }
                setPadding(dp(2), dp(2), dp(2), dp(2))
                contentDescription = context.getString(R.string.reference_glyph)
                load(url, imageLoader) { size(size, size) }
            }, LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) })
        }
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        words.addView(TextView(context).apply {
            text = label(candidate)
            textSize = if (compact) 16f else 17f
            setTextColor(0xFF343A40.toInt())
            if (chosen) setTypeface(typeface, Typeface.BOLD)
            // Allow wrapping so scores and rare-character hints survive font scaling.
        })
        if (!compact && candidate.work.isNotBlank()) {
            words.addView(TextView(context).apply {
                text = candidate.work; textSize = 12f; setTextColor(0xFF6C757D.toInt())
                setPadding(0, dp(3), 0, 0)
            })
        }
        line.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (compact) {
            line.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_expand_more)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(6) })
        }
        return line
    }

    private fun outline() = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(6).toFloat()
        setStroke(dp(1), 0xFFADB5BD.toInt())
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
