package com.paragrudani.replyly.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paragrudani.replyly.R
import com.paragrudani.replyly.onboarding.OnboardingState
import com.paragrudani.replyly.onboarding.OnboardingStepId
import com.paragrudani.replyly.onboarding.WelcomeFlow
import com.paragrudani.replyly.onboarding.WelcomePage
import com.paragrudani.replyly.ui.theme.MotionDurations
import com.paragrudani.replyly.ui.theme.MotionEasing
import com.paragrudani.replyly.ui.theme.ReplylyMotion
import com.paragrudani.replyly.ui.theme.ReplylyTheme
import com.paragrudani.replyly.ui.theme.ReplylyWordmark
import com.paragrudani.replyly.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * The welcome flow: what Replyly does, the privacy promise, then the setup checklist. Stateless
 * apart from the current page, so screenshot tests render it with a fixed [state] and [startPage].
 */
@Composable
internal fun OnboardingContent(
    state: OnboardingState?,
    onOpen: (OnboardingStepId) -> Unit,
    onConfirmSleepingApps: (Boolean) -> Unit,
    onFinished: () -> Unit,
    startPage: WelcomePage = WelcomePage.WhatItDoes,
) {
    val pages = WelcomePage.entries
    val pager = rememberPagerState(initialPage = startPage.ordinal) { pages.size }
    val motion = ReplylyTheme.motion
    val scope = rememberCoroutineScope()
    // The page the user is on or is settling onto: drives the controls while a swipe is in flight.
    val current = pages[pager.targetPage]
    val goTo: (WelcomePage) -> Unit = { page ->
        scope.launch {
            pager.animateScrollToPage(
                page.ordinal,
                animationSpec = motion.tween(MotionDurations.EXTRA_LONG, MotionEasing.Standard),
            )
        }
    }

    // System Back retraces the pages after the start page, then leaves the screen as before.
    val backTarget = WelcomeFlow.systemBackTarget(current, startPage)
    BackHandler(enabled = backTarget != null) { backTarget?.let(goTo) }

    Scaffold(
        topBar = { TopBar(canSkip = WelcomeFlow.canSkip(current), onSkip = { goTo(WelcomeFlow.SKIP_TARGET) }) },
        bottomBar = {
            BottomBar(
                current = current,
                pageCount = pages.size,
                allDone = state?.allDone == true,
                onBack = { WelcomeFlow.previous(current)?.let(goTo) },
                onNext = { WelcomeFlow.next(current)?.let(goTo) },
                onFinished = onFinished,
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().padding(padding),
            flingBehavior = PagerDefaults.flingBehavior(state = pager, snapAnimationSpec = motion.spring()),
            key = { pages[it] },
        ) { index ->
            val modifier = Modifier.fillMaxSize().pageTransition(pager, index, motion)
            when (pages[index]) {
                WelcomePage.WhatItDoes -> WhatItDoesPage(modifier)
                WelcomePage.Privacy -> PrivacyPage(modifier)
                WelcomePage.Setup -> SetupChecklistPage(state, onOpen, onConfirmSleepingApps, modifier)
            }
        }
    }
}

/** Neighbouring pages fade a little and drift slower than the finger: depth without decoration. */
private fun Modifier.pageTransition(
    pager: PagerState,
    index: Int,
    motion: ReplylyMotion,
): Modifier =
    if (motion.reduced) {
        this
    } else {
        graphicsLayer {
            val offset = ((pager.currentPage - index) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
            alpha = 1f - offset.absoluteValue * 0.6f
            translationX = offset * size.width * 0.25f
        }
    }

@Composable
private fun TopBar(
    canSkip: Boolean,
    onSkip: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = Spacing.XL, end = Spacing.S, top = Spacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReplylyWordmark(style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (canSkip) {
            TextButton(onClick = onSkip) { Text("Skip") }
        }
    }
}

@Composable
private fun BottomBar(
    current: WelcomePage,
    pageCount: Int,
    allDone: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinished: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.L, vertical = Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Side slots take their content's width so "Skip for now" stays on one line at large font
        // sizes; the dots sit centred in the space between them.
        Box(contentAlignment = Alignment.CenterStart) {
            if (WelcomeFlow.previous(current) != null) TextButton(onClick = onBack) { Text("Back", maxLines = 1) }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            PageIndicator(current = current.ordinal, count = pageCount)
        }
        Box(contentAlignment = Alignment.CenterEnd) {
            when {
                WelcomeFlow.next(current) != null -> Button(onClick = onNext) { Text("Next", maxLines = 1) }
                allDone -> Button(onClick = onFinished) { Text("Done", maxLines = 1) }
                else -> TextButton(onClick = onFinished) { Text("Skip for now", maxLines = 1) }
            }
        }
    }
}

/** Dots, the current one stretched into a pill. One TalkBack stop: "Page 2 of 3". */
@Composable
private fun PageIndicator(
    current: Int,
    count: Int,
) {
    val motion = ReplylyTheme.motion
    Row(
        modifier =
            Modifier
                .padding(horizontal = Spacing.S)
                .clearAndSetSemantics { contentDescription = "Page ${current + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(if (selected) 24.dp else Spacing.S, motion.tween(MotionDurations.LONG))
            val color by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                motion.tween(MotionDurations.LONG),
            )
            Box(
                Modifier
                    .height(Spacing.S)
                    .width(width)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** A page's scrollable column (nothing clips at large font sizes), announced by [title] when it appears. */
@Composable
internal fun PageColumn(
    title: String,
    modifier: Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            modifier
                .semantics { paneTitle = title }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.XL, vertical = Spacing.L),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(Spacing.L),
    ) { content() }
}

/**
 * The page illustration: the page's mark on a soft brand disc, with the marigold sparkle as the
 * one warm accent. Decorative: TalkBack skips it.
 */
@Composable
internal fun PageIllustration(
    @DrawableRes icon: Int,
    size: Dp = 112.dp,
) {
    Box(Modifier.size(size)) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(size * 0.46f),
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_sparkle),
            contentDescription = null,
            tint = ReplylyTheme.colors.accent,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = size * 0.02f).size(size * 0.24f),
        )
    }
}

@Composable
internal fun PageTitle(
    text: String,
    textAlign: TextAlign = TextAlign.Center,
) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )
}

@Composable
private fun WhatItDoesPage(modifier: Modifier) {
    PageColumn(title = "What Replyly does", modifier = modifier) {
        PageIllustration(R.drawable.ic_onboarding_replies)
        PageTitle("Replies in your style")
        Text(
            "Replyly helps you answer chats faster. It suggests; you decide.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FeatureRow(
            marker = "1",
            "In WhatsApp, Telegram or another chat app, a small button floats above the keyboard.",
        )
        FeatureRow(marker = "2", "Tap it for 3 replies written the way you write.")
        FeatureRow(marker = "3", "Tap a reply to put it in the text box. You edit it and send it yourself.")
    }
}

@Composable
private fun PrivacyPage(modifier: Modifier) {
    PageColumn(title = "Your privacy", modifier = modifier) {
        PageIllustration(R.drawable.ic_onboarding_privacy)
        PageTitle("Private by design")
        PRIVACY_PROMISES.forEach { FeatureRow(marker = null, it) }
    }
}

/** Spec sections 4, 7 and 8, in the user's words. */
internal val PRIVACY_PROMISES =
    listOf(
        "No backend. Your phone talks directly to the AI service you choose, with your own API key.",
        "Chat data stays on your phone, except the prompt sent with each request.",
        "Your API key is stored encrypted on this phone.",
        "Chats you open in supported apps are saved on this phone, encrypted, for 60 days by default. You can delete them.",
        "Secure screens, password fields and known banking and payment apps are never read.",
        "Replyly never sends a message. It only fills in the text box; you press send.",
    )

/** A numbered (or bulleted, when [marker] is null) line of body text. Read as one TalkBack stop. */
@Composable
private fun FeatureRow(
    marker: String?,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            if (marker != null) {
                Text(
                    marker,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(Spacing.L),
                )
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(top = Spacing.XXS),
        )
    }
}
