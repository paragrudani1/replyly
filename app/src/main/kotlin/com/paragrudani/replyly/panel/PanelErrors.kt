package com.paragrudani.replyly.panel

import com.paragrudani.replyly.llm.SuggestionResult.Failure

/** What the error card's button does. */
enum class ErrorAction {
    /** Opens Replyly's settings screen (and closes the panel). */
    OpenSettings,

    /** Same as Regenerate: one new LLM call with the current instruction. */
    Retry,
}

/** Which group an error belongs to: picks the error state's icon. */
enum class ErrorKind {
    /** The API key is missing, unusable or rejected. */
    Key,

    /** Another setting is wrong (endpoint, model id, access). */
    Settings,

    /** A limit: the daily limit, credits, rate limiting. */
    Limit,

    /** No connection, too slow, or the provider's server failed (5xx). */
    Connection,

    /** Replyly's own encrypted storage could not be read. */
    Storage,

    /** The model or provider returned something unusable, or something unexpected failed. */
    Reply,
}

/**
 * One error card. Fixed wording only: never provider text, exception messages or chat text.
 * [detail] may repeat the generator's own fixed wording (see `ChatCompletionsClient.safeDetail`).
 */
data class PanelError(
    val title: String,
    val detail: String?,
    val action: ErrorAction,
    val kind: ErrorKind = ErrorKind.Reply,
)

internal fun Failure.toPanelError(): PanelError =
    when (this) {
        Failure.NoApiKey -> {
            PanelError("No API key yet", "Add your API key in Settings.", ErrorAction.OpenSettings, ErrorKind.Key)
        }

        Failure.InvalidApiKey -> {
            PanelError("API key not usable", Failure.InvalidApiKey.MESSAGE, ErrorAction.OpenSettings, ErrorKind.Key)
        }

        is Failure.LimitReached -> {
            PanelError(
                "Daily limit reached",
                "You have used all $dailyLimit requests for today. It resets tomorrow, or raise the limit in Settings.",
                ErrorAction.OpenSettings,
                ErrorKind.Limit,
            )
        }

        Failure.Timeout -> {
            PanelError(
                "The model took too long",
                "Check your connection and try again.",
                ErrorAction.Retry,
                ErrorKind.Connection,
            )
        }

        // The generator's Network messages are fixed wording per exception class (decision #4).
        is Failure.Network -> {
            PanelError("Could not reach the API", message, ErrorAction.Retry, ErrorKind.Connection)
        }

        is Failure.Http -> {
            httpError(code, detail)
        }

        Failure.InsecureEndpoint -> {
            PanelError(
                "Endpoint is not https",
                Failure.InsecureEndpoint.MESSAGE,
                ErrorAction.OpenSettings,
                ErrorKind.Settings,
            )
        }

        Failure.StorageLocked -> {
            PanelError("Secure storage is locked", Failure.StorageLocked.MESSAGE, ErrorAction.Retry, ErrorKind.Storage)
        }

        is Failure.BadOutput -> {
            if (cutOff) {
                PanelError(
                    "The reply was cut off",
                    "The model ran out of room before finishing. Try again.",
                    ErrorAction.Retry,
                )
            } else {
                PanelError(
                    "Could not read the model's reply",
                    "It was not in the expected format. Try again.",
                    ErrorAction.Retry,
                )
            }
        }
    }

private fun httpError(
    code: Int,
    detail: String?,
): PanelError =
    when (code) {
        401 -> {
            PanelError("API key rejected (401)", "Check the key in Settings.", ErrorAction.OpenSettings, ErrorKind.Key)
        }

        402 -> {
            PanelError(
                "Out of credits (402)",
                "Add credits to your API account, then retry.",
                ErrorAction.Retry,
                ErrorKind.Limit,
            )
        }

        // The request itself is wrong (model id, key access, size): retrying sends the same request.
        400, 403, 404, 413 -> {
            PanelError("The API returned an error ($code)", detail, ErrorAction.OpenSettings, ErrorKind.Settings)
        }

        429 -> {
            PanelError(
                "Too many requests (429)",
                "The provider is rate limiting. Wait a moment, then retry.",
                ErrorAction.Retry,
                ErrorKind.Limit,
            )
        }

        // The provider's side failed: like a connection problem, a retry may work.
        in 500..599 -> {
            PanelError("The API returned an error ($code)", detail, ErrorAction.Retry, ErrorKind.Connection)
        }

        else -> {
            PanelError("The API returned an error ($code)", detail, ErrorAction.Retry)
        }
    }

/** The assembler threw something it did not explain (an I/O error, a bug). */
internal val HISTORY_READ_ERROR =
    PanelError(
        "Could not read this chat's history",
        "Try again. If it keeps failing, close and reopen the panel.",
        ErrorAction.Retry,
        ErrorKind.Storage,
    )

/** The generator threw instead of returning a Failure (a bug in #4). */
internal val UNEXPECTED_ERROR =
    PanelError("Something went wrong", "Could not get suggestions. Try again.", ErrorAction.Retry)
