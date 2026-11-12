package com.villamediana.novelo.ui.plans

import android.app.Activity
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.villamediana.novelo.NoveloApplication
import com.villamediana.novelo.R
import com.villamediana.novelo.Store
import com.villamediana.novelo.ai.NoveloServer
import com.villamediana.novelo.billing.BillingCancelled
import com.villamediana.novelo.settings.Source
import com.villamediana.novelo.settings.t
import com.villamediana.novelo.settings.tn
import com.villamediana.novelo.ui.components.NoveloIcons
import com.villamediana.novelo.ui.components.StitchColor
import com.villamediana.novelo.ui.components.pressScale
import com.villamediana.novelo.ui.components.stitched
import com.villamediana.novelo.ui.sheets.NoveloSheet
import com.villamediana.novelo.ui.theme.NoveloColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/** The screen this context belongs to (a sheet or a dialog wraps it). */
private fun android.content.Context.activity(): Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.ROOT)

private fun date(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(SHORT_DATE)

/** Whole days left until [until] (at least 1 while there is any time left). */
internal fun daysLeft(until: Long, now: Long = System.currentTimeMillis()): Int =
    ceil((until - now).coerceAtLeast(0) / 86_400_000.0).toInt()

/** The plan in a few words, for Ajustes; true when it needs her (the trial is over). */
fun planSummary(st: NoveloServer.Status?, now: Long = System.currentTimeMillis()): Pair<String, Boolean>? = when {
    st == null || !st.showsPlans -> null
    st.plan == NoveloServer.PLUS -> t(R.string.plans_title) to false
    st.plan == NoveloServer.ENDED -> t(R.string.plans_trial_over) to true
    st.trialEnds == null -> t(R.string.plans_trial_fresh, st.trialDays) to false
    else -> tn(R.plurals.plans_trial_left, daysLeft(st.trialEnds, now)) to false
}

/**
 * In Ajustes, under "Quem pensa" when it is the Novelo's: the plan (trial, Novelo+, trial over), what
 * is left of the day and of the week in bars, and the way to Novelo+. Nothing for a tester or the
 * owner (no plans or payment for them).
 */
@Composable
fun PlanCard(onPlans: () -> Unit, modifier: Modifier = Modifier) {
    val novelo = NoveloApplication.graph.novelo
    val st by novelo.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { novelo.refresh() }
    val s = st ?: return
    if (!s.showsPlans) return
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NoveloColors.Surface)
            .stitched(shape, StitchColor)
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .animateContentSize(tween(300))
            .semantics { contentDescription = "plan-card" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (s.plan == NoveloServer.PLUS) t(R.string.plans_title) else t(R.string.plans_trial),
                    style = MaterialTheme.typography.titleLarge,
                    color = NoveloColors.Text,
                )
                Text(
                    when {
                        s.plan == NoveloServer.PLUS && s.subStore == "play" && s.subUntil != null -> t(R.string.plans_plus_renews, date(s.subUntil))
                        s.plan == NoveloServer.PLUS && s.subUntil != null -> t(R.string.plans_plus_until, date(s.subUntil))
                        s.plan == NoveloServer.ENDED -> t(R.string.plans_trial_over)
                        s.trialEnds == null -> t(R.string.plans_trial_fresh, s.trialDays)
                        else -> tn(R.plurals.plans_trial_left, daysLeft(s.trialEnds))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.plan == NoveloServer.ENDED) NoveloColors.Accent else NoveloColors.TextMuted,
                )
            }
            if (s.plan == NoveloServer.PLUS) Badge(t(R.string.plans_active))
        }
        if (s.plan != NoveloServer.ENDED) {
            Spacer(Modifier.height(16.dp))
            UsageBars(s)
        }
        if (s.plan != NoveloServer.PLUS) {
            Spacer(Modifier.height(18.dp))
            Cta(t(R.string.plans_subscribe_plus), onPlans, Modifier.fillMaxWidth())
        }
    }
}

/** What is left of the day and of the week (in the trial, of the whole trial): talks and photos, in bars. */
@Composable
fun UsageBars(s: NoveloServer.Status) {
    val trial = s.plan == NoveloServer.TRIAL
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        UsageGroup(t(R.string.usage_today), t(R.string.usage_resets_midnight), s.talk.day, s.photo.day)
        UsageGroup(
            if (trial) t(R.string.usage_trial) else t(R.string.usage_week),
            if (trial) s.trialEnds?.let { t(R.string.usage_until, date(it)) } else t(R.string.usage_resets_monday),
            s.talk.week,
            s.photo.week,
        )
    }
}

@Composable
private fun UsageGroup(title: String, resets: String?, talks: NoveloServer.Count, photos: NoveloServer.Count) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = NoveloColors.Text, modifier = Modifier.weight(1f))
            resets?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = NoveloColors.TextFaint) }
        }
        UsageBar(t(R.string.usage_talks), talks)
        UsageBar(t(R.string.usage_photos), photos)
    }
}

/** One count as a thin bar, the color warming up as it fills. */
@Composable
private fun UsageBar(name: String, c: NoveloServer.Count) {
    val shown by animateFloatAsState(c.fraction, tween(600), label = "usage")
    val full = c.limit != null && c.used >= c.limit
    Column(Modifier.semantics { contentDescription = "usage-$name" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextMuted, modifier = Modifier.weight(1f))
            Text(
                c.limit?.let { t(R.string.usage_of, c.used, it) } ?: t(R.string.usage_unlimited, c.used),
                style = MaterialTheme.typography.bodySmall,
                color = if (full) NoveloColors.Accent else NoveloColors.Text,
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(NoveloColors.Accent.copy(alpha = 0.12f))) {
            Box(Modifier.fillMaxWidth(shown).height(6.dp).clip(CircleShape).background(if (full) NoveloColors.Accent else NoveloColors.Accent.copy(alpha = 0.55f + 0.45f * shown)))
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = NoveloColors.Accent,
        modifier = Modifier.clip(CircleShape).background(NoveloColors.Accent.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** The one strong button: full, rounded, in the accent. */
@Composable
private fun Cta(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(min = 52.dp)
            .pressScale(interaction, 0.97f)
            .clip(CircleShape)
            .background(if (enabled) NoveloColors.Accent else NoveloColors.Accent.copy(alpha = 0.35f))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled && !busy, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(color = NoveloColors.OnAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        else Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = NoveloColors.OnAccent)
    }
}

@Composable
private fun Benefit(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Icon(NoveloIcons.Check, null, tint = NoveloColors.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = NoveloColors.Text)
    }
}

/**
 * The plans: Novelo+ monthly (the one card, its price from the store, what it gives, Assinar) and,
 * under it, her own intelligence — free, no Novelo limits. The GitHub build says nothing is charged.
 */
@Composable
fun PlansSheet(onDismiss: () -> Unit) {
    val graph = NoveloApplication.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by graph.novelo.status.collectAsStateWithLifecycle()
    val billing = Store.billing
    val price by produceState<String?>(null) { value = billing.price() }
    var buying by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val s = st
    val plus = s?.plan == NoveloServer.PLUS
    val offer = s?.offer?.limits

    NoveloSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 18.dp).navigationBarsPadding(),
        ) {
            Text(t(R.string.plans_title), style = MaterialTheme.typography.headlineMedium, color = NoveloColors.Text)
            planSummary(s)?.takeIf { !plus }?.let { (line, attention) ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = if (attention) NoveloColors.Accent else NoveloColors.TextMuted, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(20.dp))

            // Novelo+, monthly: the card that matters.
            val shape = RoundedCornerShape(24.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(NoveloColors.Accent.copy(alpha = 0.08f))
                    .stitched(shape, NoveloColors.Accent, width = 1.6.dp)
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .semantics { contentDescription = "plan-monthly" },
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(t(R.string.plans_monthly), style = MaterialTheme.typography.titleLarge, color = NoveloColors.Text, modifier = Modifier.weight(1f))
                    Text(price ?: "…", style = MaterialTheme.typography.headlineSmall, color = NoveloColors.Accent)
                    Text(t(R.string.plans_per_month), style = MaterialTheme.typography.bodyMedium, color = NoveloColors.TextMuted, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
                }
                Spacer(Modifier.height(14.dp))
                if (offer != null) {
                    Benefit(t(R.string.plans_daily, offer.talk.day ?: 0, offer.photo.day ?: 0))
                    Benefit(t(R.string.plans_weekly, offer.talk.week ?: 0, offer.photo.week ?: 0))
                }
                Benefit(t(R.string.plans_better_models))
                Benefit(t(R.string.plans_cancel))
                Spacer(Modifier.height(18.dp))
                Cta(
                    if (plus) t(R.string.plans_subscribed) else t(R.string.plans_subscribe),
                    onClick = {
                        // Inside the sheet the context is the sheet's window: the screen is behind it.
                        val activity = context.activity() ?: return@Cta
                        buying = true
                        note = null
                        scope.launch {
                            try {
                                billing.subscribe(activity)
                                note = t(R.string.purchase_done)
                                graph.mind.retryPending()
                            } catch (e: BillingCancelled) {
                                // She closed the store's sheet: nothing to say.
                            } catch (e: Exception) {
                                android.util.Log.w("Novelo", "purchase", e)
                                note = t(R.string.purchase_failed)
                            } finally {
                                buying = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !plus,
                    busy = buying,
                )
                if (billing.test) {
                    Text(t(R.string.plans_test_note), style = MaterialTheme.typography.labelSmall, color = NoveloColors.TextFaint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                }
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = NoveloColors.Accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            // Her own intelligence: free, always.
            val ownShape = RoundedCornerShape(22.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(ownShape)
                    .background(NoveloColors.Surface)
                    .stitched(ownShape, StitchColor)
                    .clickable {
                        scope.launch { graph.settings.setThink(Source.API) }
                        onDismiss()
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .semantics { contentDescription = "plan-own" },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t(R.string.plans_own), style = MaterialTheme.typography.titleMedium, color = NoveloColors.Text, modifier = Modifier.weight(1f))
                    Text(t(R.string.plans_free), style = MaterialTheme.typography.titleMedium, color = NoveloColors.Accent)
                }
                Text(t(R.string.plans_own_line), style = MaterialTheme.typography.bodySmall, color = NoveloColors.TextMuted, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
