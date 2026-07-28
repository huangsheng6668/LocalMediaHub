package com.juziss.localmediahub.ble

import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import com.juziss.localmediahub.R

/**
 * Task 5 §1.3: short-circuit Coil image requests to a local placeholder while
 * the media list is being served over the BLE fallback transport.
 *
 * When [BleDegradedState.isBleDegraded] is true, every Coil `AsyncImage`
 * (video thumbnails, image thumbnails, full-screen originals) currently points
 * at an HTTP URL the BLE path cannot serve — the BLE protocol only carries
 * JSON list data, not media bytes (spec §3.2). Letting those requests fire
 * would queue N failing OkHttp calls against a server the app already knows
 * is unreachable, each timing out at 30s and slowing the visible list. This
 * interceptor rewrites such requests in-flight to point at the local
 * placeholder drawable resource, so Coil's fetcher chain resolves them from
 * the resource fetcher (no HTTP) and the placeholder shows up immediately.
 *
 * Wire-format: Coil 3.x interceptors receive a [Interceptor.Chain] and return
 * an [ImageResult]. Calling [Interceptor.Chain.proceed] runs the rest of the
 * chain (fetcher → decoder → transform). To swap the data without losing the
 * request's memory/disk cache plumbing, we call [Interceptor.Chain.withRequest]
 * with a copy whose `data` is the placeholder resource ID — Coil recognises
 * `Int` drawable-res data and routes it through the built-in resource fetcher,
 * so the OkHttp network fetcher is never reached.
 *
 * Read-site freshness: the interceptor reads [BleDegradedState.isBleDegraded]
 * `.value` on EVERY request, so it always observes the most recent mirror of
 * `MediaRepository.isBleDegraded` (the repository flips the holder as soon as
 * a failover-capable fetch switches to BLE). No stale snapshot.
 */
class BleDegradedImageInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        // Fast path: not degraded — proceed with the original request untouched.
        if (!BleDegradedState.isBleDegraded.value) {
            return chain.proceed()
        }
        // Degraded: rewrite the request data to the placeholder drawable so
        // the OkHttp fetcher is bypassed. Only rewrite HTTP(S) URLs — a
        // request that is already pointing at a local resource (e.g. the
        // placeholder being requested recursively, or an offline asset) is
        // left alone to avoid an infinite swap.
        val data = chain.request.data
        if (data is String && (data.startsWith("http://") || data.startsWith("https://"))) {
            val placeholderRequest: ImageRequest = chain.request.newBuilder()
                .data(R.drawable.ic_ble_placeholder)
                .build()
            // withRequest returns a new chain bound to the rewritten request;
            // proceed() then runs the rest of the chain against that request.
            return chain.withRequest(placeholderRequest).proceed()
        }
        return chain.proceed()
    }
}
