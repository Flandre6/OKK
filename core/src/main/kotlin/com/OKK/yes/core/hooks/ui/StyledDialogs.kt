package com.OKK.yes.core.hooks.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 模块统一的精致弹窗：圆角卡片 + 深色/浅色自适应 + 绿色主按钮。
 * 用于替换分组等功能里的原生 AlertDialog（输入/确认/列表）。
 */
object StyledDialogs {

    private const val ACCENT = 0xFF07C160.toInt()
    private const val DANGER = 0xFFE64545.toInt()

    /** 夜间模式：uiMode + 主题背景亮度采样（微信深色独立于系统） */
    fun isNight(ctx: Context): Boolean {
        return runCatching {
            val mode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (mode == Configuration.UI_MODE_NIGHT_YES) return@runCatching true
            val tv = TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.windowBackground, tv, true)) {
                if (tv.type in 28..31) {
                    val c = tv.data
                    val lum = 0.2126 * Color.red(c) / 255.0 +
                        0.7152 * Color.green(c) / 255.0 +
                        0.0722 * Color.blue(c) / 255.0
                    if (lum < 0.35) return@runCatching true
                }
            }
            false
        }.getOrDefault(false)
    }

    private class Palette(ctx: Context) {
        val night = isNight(ctx)
        val cardBg = if (night) 0xFF1E1F24.toInt() else Color.WHITE
        val primaryTxt = if (night) 0xFFEAEAEA.toInt() else 0xFF1A1A1A.toInt()
        val subTxt = if (night) 0xFF9A9A9A.toInt() else 0xFF7A7A7A.toInt()
        val faintBg = if (night) 0x14FFFFFF else 0x0F000000
        val divider = if (night) 0x1FFFFFFF else 0x12000000
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun tv(ctx: Context, s: String, sizeSp: Float, color: Int, bold: Boolean): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /** 圆角卡片根布局 */
    private fun card(ctx: Context, p: Palette): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 22).toFloat()
            setColor(p.cardBg)
        }
        setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 14))
    }

    /** 幽灵按钮（灰底） */
    private fun ghostBtn(ctx: Context, p: Palette, label: String): TextView =
        tv(ctx, label, 14f, p.subTxt, false).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 17).toFloat()
                setColor(p.faintBg)
            }
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
        }

    /** 主按钮（彩色底） */
    private fun solidBtn(ctx: Context, label: String, color: Int = ACCENT): TextView =
        tv(ctx, label, 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 17).toFloat()
                setColor(color)
            }
            setPadding(dp(ctx, 24), dp(ctx, 8), dp(ctx, 24), dp(ctx, 8))
        }

    /** 显示并设置宽度 */
    private fun AlertDialog.showWide(ctx: Context) {
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        show()
        window?.let { w ->
            val dm = ctx.resources.displayMetrics
            val lp = w.attributes
            lp.width = (dm.widthPixels * 0.86f).toInt()
            w.attributes = lp
        }
    }

    /** 输入弹窗：标题 + 圆角输入框 + 取消/确定 */
    fun input(
        ctx: Context,
        title: String,
        initial: String = "",
        hint: String = "",
        okLabel: String = "确定",
        onOk: (String) -> Unit
    ) {
        val p = Palette(ctx)
        var dialog: AlertDialog? = null
        val root = card(ctx, p)
        root.addView(tv(ctx, title, 17f, p.primaryTxt, true))

        val input = EditText(ctx).apply {
            setText(initial)
            this.hint = hint
            textSize = 14.5f
            setSingleLine()
            setTextColor(p.primaryTxt)
            setHintTextColor(p.subTxt)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat()
                setColor(p.faintBg)
                setStroke(dp(ctx, 1), p.divider)
            }
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
            if (initial.isNotEmpty()) setSelection(initial.length)
        }
        root.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 14) })

        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 16), 0, 0)
            addView(ghostBtn(ctx, p, "取消").apply {
                setOnClickListener { dialog?.dismiss() }
            })
            addView(solidBtn(ctx, okLabel).apply {
                setOnClickListener {
                    val v = input.text.toString().trim()
                    if (v.isNotEmpty()) onOk(v)
                    dialog?.dismiss()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(ctx, 10) })
        })

        val d = AlertDialog.Builder(ctx).setView(root).create()
        dialog = d
        d.showWide(ctx)
    }

    /** 确认弹窗：标题 + 说明 + 取消/确定（可设危险红色） */
    fun confirm(
        ctx: Context,
        title: String,
        message: String,
        okLabel: String = "确定",
        danger: Boolean = false,
        onOk: () -> Unit
    ) {
        val p = Palette(ctx)
        var dialog: AlertDialog? = null
        val root = card(ctx, p)
        root.addView(tv(ctx, title, 17f, p.primaryTxt, true))
        root.addView(tv(ctx, message, 13.5f, p.subTxt, false).apply {
            setPadding(0, dp(ctx, 8), 0, 0)
        })
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 18), 0, 0)
            addView(ghostBtn(ctx, p, "取消").apply {
                setOnClickListener { dialog?.dismiss() }
            })
            addView(solidBtn(ctx, okLabel, if (danger) DANGER else ACCENT).apply {
                setOnClickListener {
                    onOk()
                    dialog?.dismiss()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(ctx, 10) })
        })

        val d = AlertDialog.Builder(ctx).setView(root).create()
        dialog = d
        d.showWide(ctx)
    }

    /** 列表弹窗：标题 + 操作行（用于分组长按操作等） */
    fun actions(
        ctx: Context,
        title: String,
        items: List<Pair<String, Boolean>>, // label to danger
        onPick: (Int) -> Unit
    ) {
        val p = Palette(ctx)
        var dialog: AlertDialog? = null
        val root = card(ctx, p)
        root.addView(tv(ctx, title, 17f, p.primaryTxt, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        items.forEachIndexed { idx, (label, danger) ->
            root.addView(tv(ctx, label, 15f, if (danger) DANGER else p.primaryTxt, false).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 14), dp(ctx, 13), dp(ctx, 14), dp(ctx, 13))
                background = GradientDrawable().apply {
                    cornerRadius = dp(ctx, 12).toFloat()
                    setColor(if (danger) 0x14E64545 else p.faintBg)
                }
                setOnClickListener {
                    onPick(idx)
                    dialog?.dismiss()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, if (idx == 0) 14 else 8) })
        }
        root.addView(ghostBtn(ctx, p, "取消").apply {
            gravity = Gravity.CENTER
            setOnClickListener { dialog?.dismiss() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 12) })

        val d = AlertDialog.Builder(ctx).setView(root).create()
        dialog = d
        d.showWide(ctx)
    }
}
