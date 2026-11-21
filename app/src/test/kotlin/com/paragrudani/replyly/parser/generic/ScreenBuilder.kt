package com.paragrudani.replyly.parser.generic

import com.paragrudani.replyly.screen.Bounds
import com.paragrudani.replyly.screen.ScreenNode
import com.paragrudani.replyly.screen.ScreenSnapshot
import java.time.Instant

/*
 * Tiny builder for hand-made chat screens (1080 x 2340), for geometry cases too narrow to deserve a
 * fixture file. Items stack down the message list from y = 300.
 */

/** Something placed in the list at a given top; returns its nodes and height. */
class Element(
    val height: Int,
    val build: (top: Int) -> List<ScreenNode>,
)

/** One list child. */
sealed interface ListItem

/** A full-width row container holding its elements, stacked. */
class Row(
    val elements: List<Element>,
) : ListItem

/** An element placed straight in the list, with no row container (flat lists). */
class Flat(
    val element: Element,
) : ListItem

fun row(vararg elements: Element): ListItem = Row(elements.toList())

fun flat(element: Element): ListItem = Flat(element)

private fun textView(
    bounds: Bounds,
    text: String,
) = ScreenNode(className = "android.widget.TextView", text = text, bounds = bounds)

/** A bubble from [left] to [right]: one text view per line of [texts], the time bottom-right. */
fun bubble(
    left: Int,
    right: Int,
    vararg texts: String,
    time: String? = null,
    tick: Boolean = false,
): Element {
    val height = 30 + texts.size * 56 + if (time != null) 50 else 0
    return Element(height) { top ->
        val children = mutableListOf<ScreenNode>()
        texts.forEachIndexed { i, text ->
            val y = top + 15 + i * 56
            children += textView(Bounds(left + 30, y, right - 30, y + 52), text)
        }
        if (time != null) {
            val y = top + height - 50
            children += textView(Bounds(right - 200, y, right - 80, y + 40), time)
        }
        if (tick) {
            val y = top + height - 50
            children +=
                ScreenNode(
                    className = "android.widget.ImageView",
                    contentDescription = "Read",
                    bounds =
                        Bounds(right - 75, y, right - 30, y + 40),
                )
        }
        listOf(
            ScreenNode(
                className = "android.widget.LinearLayout",
                bounds = Bounds(left, top, right, top + height),
                children = children,
            ),
        )
    }
}

/** A bare text view from [left] to [right]. */
fun text(
    left: Int,
    right: Int,
    text: String,
): Element = Element(52) { top -> listOf(textView(Bounds(left, top, right, top + 52), text)) }

/** A bare text view centred in the list. */
fun centered(text: String): Element {
    val width = 40 + 24 * text.length
    val left = (1080 - width) / 2
    return text(left, left + width, text)
}

fun chatScreen(
    packageName: String,
    title: String,
    subtitle: String? = null,
    rows: List<ListItem>,
    /** Top of the message input. Lower it to about 1150 to model an open keyboard. */
    inputTop: Int = 2075,
): ScreenSnapshot {
    val listBottom = inputTop - 23
    var y = 300
    val listChildren =
        rows.map { item ->
            when (item) {
                is Flat -> {
                    item.element
                        .build(y)
                        .single()
                        .also { y += item.element.height + 10 }
                }

                is Row -> {
                    val top = y
                    val nodes =
                        item.elements.flatMap { element ->
                            element.build(y).also { y += element.height + 6 }
                        }
                    ScreenNode(className = "android.view.ViewGroup", bounds = Bounds(0, top, 1080, y), children = nodes)
                        .also { y += 10 }
                }
            }
        }
    check(y <= listBottom) { "test screen overflows the list" }
    val header =
        listOfNotNull(
            textView(Bounds(220, 128, 700, 190), title),
            subtitle?.let { textView(Bounds(220, 190, 700, 240), it) },
        )
    val list =
        ScreenNode(
            className = "androidx.recyclerview.widget.RecyclerView",
            bounds = Bounds(0, 257, 1080, listBottom),
            scrollable = true,
            children = listChildren,
        )
    val input =
        ScreenNode(
            className = "android.widget.EditText",
            hint = "Message",
            bounds = Bounds(140, inputTop, 760, inputTop + 115),
            editable = true,
            focused = true,
        )
    return ScreenSnapshot(
        packageName = packageName,
        capturedAt = Instant.parse("2026-11-21T10:15:30Z"),
        screenWidth = 1080,
        screenHeight = 2340,
        root = ScreenNode(bounds = Bounds(0, 0, 1080, 2340), children = header + list + input),
    )
}

/** An incoming-style bubble with a reply-quote block (name above text, in its own box) above the message. */
fun quoteBubble(
    left: Int,
    right: Int,
    quoteSender: String,
    quoteText: String,
    text: String,
    time: String,
): Element {
    val height = 30 + 150 + 56 + 50
    return Element(height) { top ->
        val quote =
            ScreenNode(
                className = "android.widget.LinearLayout",
                bounds = Bounds(left + 16, top + 15, right - 16, top + 155),
                children =
                    listOf(
                        textView(Bounds(left + 40, top + 27, right - 40, top + 77), quoteSender),
                        textView(Bounds(left + 40, top + 85, right - 40, top + 141), quoteText),
                    ),
            )
        val y = top + 165
        listOf(
            ScreenNode(
                className = "android.widget.LinearLayout",
                bounds = Bounds(left, top, right, top + height),
                children =
                    listOf(
                        quote,
                        textView(Bounds(left + 30, y, right - 30, y + 52), text),
                        textView(Bounds(right - 200, top + height - 50, right - 80, top + height - 10), time),
                    ),
            ),
        )
    }
}
