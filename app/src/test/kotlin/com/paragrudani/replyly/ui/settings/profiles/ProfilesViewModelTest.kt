package com.paragrudani.replyly.ui.settings.profiles

import com.google.common.truth.Truth.assertThat
import com.paragrudani.replyly.llm.ConnectionTestResult
import com.paragrudani.replyly.llm.ConnectionTester
import com.paragrudani.replyly.llm.SuggestionResult
import com.paragrudani.replyly.settings.ApiFormat
import com.paragrudani.replyly.settings.InMemoryProfileRepository
import com.paragrudani.replyly.settings.InMemoryProfileSecretStore
import com.paragrudani.replyly.settings.ProviderProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesViewModelTest {
    private val secrets = InMemoryProfileSecretStore()
    private val openRouter =
        ProviderProfile(
            "or",
            "OpenRouter",
            ApiFormat.OpenAi,
            "https://openrouter.ai/api/v1",
            "anthropic/claude-haiku-5.5",
        )
    private val repo = InMemoryProfileRepository(listOf(openRouter), secrets = secrets)
    private var nextTest: suspend (String) -> ConnectionTestResult = { ConnectionTestResult.Ok(840.milliseconds) }
    private val testedIds = mutableListOf<String>()
    private val tester =
        object : ConnectionTester {
            override suspend fun testConnection(profileId: String): ConnectionTestResult {
                testedIds += profileId
                return nextTest(profileId)
            }
        }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ProfilesViewModel(repo, secrets, tester, newId = { "new" })

    @Test
    fun `rows show the active profile and whether a key is saved`() =
        runTest {
            val vm = viewModel()
            assertThat(vm.state.value.rows).containsExactly(ProfileRow(openRouter, active = true, hasKey = false))
            secrets.setApiKey("or", "sk-or-key-0001")
            assertThat(
                vm.state.value.rows
                    .single()
                    .hasKey,
            ).isTrue()
        }

    @Test
    fun `adding an Anthropic profile from the preset saves key, headers and profile, then switching works`() =
        runTest {
            val vm = viewModel()
            vm.startNew()
            vm.applyPreset(ProfilePreset.Anthropic)
            vm.editDraft {
                it.copy(
                    apiKeyInput = "sk-ant-key-000001",
                    newHeaders = listOf(HeaderInput("X-Org", "org-9")),
                )
            }
            vm.save()

            assertThat(vm.state.value.editor).isNull()
            val saved =
                repo.state.value.profiles
                    .single { it.id == "new" }
            assertThat(saved.format).isEqualTo(ApiFormat.Anthropic)
            assertThat(saved.model).isEqualTo("claude-haiku-5-5")
            assertThat(saved.extraHeaderNames).containsExactly("X-Org")
            assertThat(secrets.apiKey("new")).isEqualTo("sk-ant-key-000001")
            assertThat(secrets.extraHeaders("new")).containsExactly("X-Org", "org-9")
            assertThat(repo.state.value.activeId).isEqualTo("or")

            vm.select("new")
            assertThat(repo.state.value.activeId).isEqualTo("new")
            assertThat(
                vm.state.value.rows
                    .single { it.active }
                    .profile.id,
            ).isEqualTo("new")
        }

    @Test
    fun `editing keeps the saved key and kept headers unless replaced`() =
        runTest {
            secrets.setApiKey("or", "sk-or-key-0001")
            secrets.setExtraHeaders("or", mapOf("X-A" to "a", "X-B" to "b"))
            repo.upsert(openRouter.copy(extraHeaderNames = listOf("X-A", "X-B")))
            val vm = viewModel()
            vm.edit("or")
            assertThat(
                vm.state.value.editor!!
                    .keySaved,
            ).isTrue()
            assertThat(
                vm.state.value.editor!!
                    .apiKeyInput,
            ).isEmpty()

            vm.editDraft { it.copy(model = "google/gemini-3.8-flash", savedHeaderNames = listOf("X-B")) }
            vm.save()

            assertThat(secrets.apiKey("or")).isEqualTo("sk-or-key-0001")
            assertThat(secrets.extraHeaders("or")).containsExactly("X-B", "b")
            assertThat(
                repo.state.value.profiles
                    .single()
                    .extraHeaderNames,
            ).containsExactly("X-B")
            assertThat(
                repo.state.value.profiles
                    .single()
                    .model,
            ).isEqualTo("google/gemini-3.8-flash")
        }

    @Test
    fun `invalid drafts show errors and save nothing`() =
        runTest {
            val vm = viewModel()
            vm.startNew()
            vm.editDraft { it.copy(baseUrl = "http://insecure.example.com/v1") }
            vm.save()
            assertThat(vm.state.value.errors).containsKey(ProfileField.BaseUrl)
            assertThat(repo.state.value.profiles).hasSize(1)
        }

    @Test
    fun `test connection shows OK with latency, or the panel's fixed wording`() =
        runTest {
            val vm = viewModel()
            vm.test("or")
            assertThat(vm.state.value.tests["or"]).isEqualTo(TestState.Ok(840))
            assertThat((vm.state.value.tests["or"] as TestState.Ok).text).isEqualTo("OK · 840 ms")

            nextTest =
                {
                    ConnectionTestResult.Failed(
                        SuggestionResult.Failure.Http(401, "API key rejected. Check the key in Settings."),
                    )
                }
            vm.test("or")
            val failed = vm.state.value.tests["or"] as TestState.Failed
            assertThat(failed.text).isEqualTo("API key rejected (401). Check the key in Settings.")
        }

    @Test
    fun `a running test ignores further taps`() =
        runTest {
            val gate = CompletableDeferred<ConnectionTestResult>()
            nextTest = { gate.await() }
            val vm = viewModel()
            vm.test("or")
            vm.test("or")
            assertThat(vm.state.value.tests["or"]).isEqualTo(TestState.Running)
            gate.complete(ConnectionTestResult.Ok(5.milliseconds))
            assertThat(testedIds).containsExactly("or")
            assertThat(vm.state.value.tests["or"]).isEqualTo(TestState.Ok(5))
        }

    @Test
    fun `deleting the active profile activates another one and drops its secrets`() =
        runTest {
            repo.upsert(ProviderProfile("b", "B", ApiFormat.OpenAi, "https://api.openai.com/v1", "m"))
            secrets.setApiKey("or", "sk-or-key-0001")
            val vm = viewModel()
            assertThat(vm.state.value.canDelete).isTrue()
            vm.delete("or")
            assertThat(
                repo.state.value.profiles
                    .map { it.id },
            ).containsExactly("b")
            assertThat(repo.state.value.activeId).isEqualTo("b")
            assertThat(secrets.apiKey("or")).isNull()
            assertThat(vm.state.value.canDelete).isFalse()
        }
}
