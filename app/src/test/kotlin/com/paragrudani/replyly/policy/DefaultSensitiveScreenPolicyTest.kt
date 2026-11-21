package com.paragrudani.replyly.policy

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.parser.ChatKey
import com.paragrudani.replyly.screen.Bounds
import com.paragrudani.replyly.screen.ScreenNode
import com.paragrudani.replyly.screen.ScreenSnapshot
import com.paragrudani.replyly.settings.InMemorySettingsRepository
import com.paragrudani.replyly.settings.ReplylySettings
import com.paragrudani.replyly.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.time.Instant

class DefaultSensitiveScreenPolicyTest {
    private var settings: ReplylySettings? = ReplylySettings()
    private val policy = DefaultSensitiveScreenPolicy(settings = { settings })

    private fun snapshot(
        packageName: String = WHATSAPP,
        secureWindow: Boolean = false,
        vararg nodes: ScreenNode,
    ) = ScreenSnapshot(
        packageName = packageName,
        capturedAt = Instant.EPOCH,
        screenWidth = 1080,
        screenHeight = 2340,
        root = ScreenNode(bounds = Bounds(0, 0, 1080, 2340), children = nodes.toList()),
        secureWindow = secureWindow,
    )

    private fun blocked(reason: BlockReason) = SensitivityVerdict.Blocked(reason)

    @Test
    fun `an ordinary chat screen is allowed`() {
        val chat = snapshot(nodes = arrayOf(ScreenNode(text = "hi", bounds = Bounds(0, 0, 10, 10))))

        assertThat(policy.evaluate(ScreenContext(WHATSAPP, chat, ChatKey(WHATSAPP, "Asha")))).isEqualTo(
            SensitivityVerdict.Allowed,
        )
    }

    @Test
    fun `a package-only check is allowed for an ordinary app`() {
        assertThat(policy.evaluate(ScreenContext("com.slack"))).isEqualTo(SensitivityVerdict.Allowed)
    }

    @Test
    fun `blocklisted app is blocked`() {
        settings = ReplylySettings(blockedPackages = setOf("com.slack"))

        assertThat(policy.evaluate(ScreenContext("com.slack"))).isEqualTo(blocked(BlockReason.BLOCKLISTED_APP))
    }

    @Test
    fun `blocklisted chat is blocked once the chat key is known`() {
        settings = ReplylySettings(blockedChatKeys = setOf("$WHATSAPP:Doctor"))

        assertThat(policy.evaluate(ScreenContext(WHATSAPP, snapshot(), ChatKey(WHATSAPP, "Doctor"))))
            .isEqualTo(blocked(BlockReason.BLOCKLISTED_CHAT))
        assertThat(policy.evaluate(ScreenContext(WHATSAPP, snapshot(), ChatKey(WHATSAPP, "Asha"))))
            .isEqualTo(SensitivityVerdict.Allowed)
    }

    @Test
    fun `same chat title in another app is not blocked`() {
        settings = ReplylySettings(blockedChatKeys = setOf("$WHATSAPP:Doctor"))

        assertThat(policy.evaluate(ScreenContext(TELEGRAM, snapshot(TELEGRAM), ChatKey(TELEGRAM, "Doctor"))))
            .isEqualTo(SensitivityVerdict.Allowed)
    }

    @Test
    fun `any password field on screen blocks, even a hidden one deep in the tree`() {
        val deep =
            ScreenNode(
                bounds = Bounds(0, 0, 1, 1),
                children = listOf(ScreenNode(bounds = Bounds(0, 0, 1, 1), password = true, visible = false)),
            )

        assertThat(policy.evaluate(ScreenContext(WHATSAPP, snapshot(nodes = arrayOf(deep)))))
            .isEqualTo(blocked(BlockReason.PASSWORD_FIELD))
    }

    @Test
    fun `common Indian banking, UPI and wallet apps are blocked by package`() {
        val packages =
            listOf(
                "com.sbi.lotusintouch", // YONO SBI
                "com.snapwork.hdfc", // HDFC Bank
                "com.csam.icici.bank.imobile", // ICICI iMobile
                "com.axis.mobile", // Axis
                "com.msf.kbank.mobile", // Kotak
                "com.phonepe.app",
                "net.one97.paytm",
                "com.google.android.apps.nbu.paisa.user", // Google Pay
                "in.org.npci.upiapp", // BHIM
                "com.dreamplug.androidapp", // CRED
                "in.amazon.mShop.android.shopping", // Amazon Pay lives in the Amazon app
                "com.samsung.android.samsungpass", // Samsung Pass
                "com.x8bit.bitwarden", // Bitwarden
                "com.google.android.apps.authenticator2", // Google Authenticator
                "com.azure.authenticator", // Microsoft Authenticator
                "com.msf.angelmobile", // Angel One
                "com.fedmobile", // FedMobile
            )

        for (pkg in packages) {
            assertThat(policy.evaluate(ScreenContext(pkg))).isEqualTo(blocked(BlockReason.BANKING_APP))
        }
    }

    @Test
    fun `secure window blocks, from the context or from the snapshot`() {
        assertThat(policy.evaluate(ScreenContext(WHATSAPP, secureWindow = true)))
            .isEqualTo(blocked(BlockReason.SECURE_WINDOW))
        assertThat(policy.evaluate(ScreenContext(WHATSAPP, snapshot(secureWindow = true))))
            .isEqualTo(blocked(BlockReason.SECURE_WINDOW))
    }

    @Test
    fun `until settings have loaded every app counts as blocklisted`() {
        settings = null

        assertThat(policy.evaluate(ScreenContext(WHATSAPP))).isEqualTo(blocked(BlockReason.BLOCKLISTED_APP))
    }

    @Test
    fun `a blocklisted app wins over the other reasons`() {
        settings = ReplylySettings(blockedPackages = setOf(WHATSAPP))
        val withPassword = snapshot(nodes = arrayOf(ScreenNode(bounds = Bounds.EMPTY, password = true)))

        assertThat(policy.evaluate(ScreenContext(WHATSAPP, withPassword))).isEqualTo(
            blocked(BlockReason.BLOCKLISTED_APP),
        )
    }

    @Test
    fun `created policy follows blocklist edits in settings`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = InMemorySettingsRepository()
            val live = DefaultSensitiveScreenPolicy.create(repository, backgroundScope)

            assertThat(live.evaluate(ScreenContext("com.slack"))).isEqualTo(SensitivityVerdict.Allowed)

            repository.update { it.copy(blockedPackages = setOf("com.slack")) }

            assertThat(live.evaluate(ScreenContext("com.slack"))).isEqualTo(blocked(BlockReason.BLOCKLISTED_APP))
        }

    @Test
    fun `settings that fail to load keep every app blocked and report it once`() =
        runTest(UnconfinedTestDispatcher()) {
            val broken =
                object : SettingsRepository {
                    override val settings: Flow<ReplylySettings> = flow { throw IOException("disk") }

                    override suspend fun update(transform: (ReplylySettings) -> ReplylySettings) = Unit
                }
            var reports = 0
            val live = DefaultSensitiveScreenPolicy.create(broken, backgroundScope, log = {}) { reports++ }

            assertThat(live.evaluate(ScreenContext("com.slack"))).isEqualTo(blocked(BlockReason.BLOCKLISTED_APP))
            assertThat(reports).isEqualTo(1)
        }

    private companion object {
        const val WHATSAPP = "com.whatsapp"
        const val TELEGRAM = "org.telegram.messenger"
    }
}
