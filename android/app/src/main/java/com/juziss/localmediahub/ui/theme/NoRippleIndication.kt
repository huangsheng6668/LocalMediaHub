package com.juziss.localmediahub.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode

/**
 * Workaround for a Compose version skew between foundation 1.11.x and material3 1.3.1.
 *
 * Background — material3 1.3.1 ships `androidx.compose.material.ripple.PlatformRipple`
 * which implements only the legacy `Indication` interface, NOT the new
 * `IndicationNodeFactory` required by foundation 1.11.x's `Modifier.clickable`
 * (clickable now validates at runtime that the `LocalIndication` value implements
 * `IndicationNodeFactory`, throwing `IllegalArgumentException` otherwise).
 *
 * Root cause of the skew: Round 24 Task 8 upgraded Coil 2 → 3, which transitively
 * pulled Compose foundation to 1.11.x, but the Compose BOM (2024.06.00) still
 * pins material3 at 1.3.1. Debug builds tolerate the mismatch; release R8
 * builds crash at startup on the first `Modifier.clickable` evaluation.
 *
 * Fix: replace the default `LocalIndication` with a no-op `IndicationNodeFactory`
 * so `clickable` (and all Material components using it) get a compliant instance.
 * Visual cost: ripple effect on taps is suppressed. Acceptable for a media-gallery
 * app where primary affordances are images and videos.
 *
 * When material3 is upgraded to 1.4.x+ (whose Ripple implements
 * `IndicationNodeFactory` natively), this file and [ProvideNoRippleIndication]
 * should be deleted.
 */
private object NoRippleIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = NoOpNode()
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Minimal `Modifier.Node` (which implements `DelegatableNode`) that does no draw,
 * measure, or update work. Used as the per-InteractionSource indication node so
 * `clickable` has a compliant node to attach without producing any visual ripple.
 */
private class NoOpNode : Modifier.Node()

/**
 * CompositionLocal override that injects [NoRippleIndication] as the app-wide
 * `LocalIndication`, satisfying foundation 1.11.x's `IndicationNodeFactory`
 * requirement without touching Material 1.3.1's legacy ripple.
 */
@Composable
fun ProvideNoRippleIndication(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalIndication provides NoRippleIndication,
        content = content,
    )
}
