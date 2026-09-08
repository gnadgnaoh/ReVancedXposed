package io.github.nexalloy.morphe.twitter.link.unshorten

import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch

val NoShortenedUrl = patch(
    name = "No shortened URL",
    description = "Gets rid of t.co short urls by showing the expanded URL instead.",
) {
    UrlEntityConstructorFingerprint.hookMethod {
        before { param ->
            unshortenArgs(param, displayIdx = 1, expandedIdx = 3, urlIdx = 4)
        }
    }

    UrlEntitySerialConstructorFingerprint.hookMethod {
        before { param ->
            unshortenArgs(param, displayIdx = 3, expandedIdx = 4, urlIdx = 5)
        }
    }

    OpenExternalUrlFingerprint.hookMethod {
        before { param -> unshortenArgAt(param, 0) }
    }

    for (fingerprint in listOf(
        LinkWithPostDetailArgsToStringFingerprint,
        WebViewArgsToStringFingerprint,
    )) {
        for (constructor in fingerprint.declaredClass.declaredConstructors) {
            val urlIndex = constructor.parameterTypes.indexOfFirst { it == String::class.java }
            if (urlIndex < 0) continue

            constructor.hookMethod {
                before { param -> unshortenArgAt(param, urlIndex) }
            }
        }
    }
}
