package com.impactledger.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF14532D)
private val GreenSoft = Color(0xFFDCEBE0)
private val Ground = Color(0xFFF5F6F3)
private val Ink = Color(0xFF1A1F1B)
private val Muted = Color(0xFF4B564E)
private val AmberSoft = Color(0xFFFFF1D6)
private val AmberInk = Color(0xFF7C2D12)

private val colors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    background = Ground,
    surface = Color.White,
    onSurface = Ink,
)

/** Shared app UI, used by both the Android app and the web app. */
@Composable
fun App() {
    MaterialTheme(colorScheme = colors) {
        val donations = remember {
            mutableStateMapOf<String, Double>().apply { putAll(DemoData.startingDonations) }
        }
        var openProjectId by remember { mutableStateOf<String?>(null) }

        Surface(modifier = Modifier.fillMaxSize(), color = Ground) {
            Box(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    val project = DemoData.projects.firstOrNull { it.id == openProjectId }
                    if (project == null) {
                        HomeScreen(
                            donations = donations,
                            onOpen = { openProjectId = it },
                        )
                    } else {
                        ProjectScreen(
                            project = project,
                            myDonation = donations[project.id] ?: 0.0,
                            onDonate = { amount ->
                                donations[project.id] = (donations[project.id] ?: 0.0) + amount
                            },
                            onBack = { openProjectId = null },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(donations: Map<String, Double>, onOpen: (String) -> Unit) {
    val total = donations.values.sum()
    val highlights = DemoData.projects.mapNotNull { project ->
        val gift = donations[project.id] ?: return@mapNotNull null
        val kpi = project.kpis.first()
        val share = ImpactMath.estimatedShare(kpi, gift, project.fundedChf)
        "≈ ${formatNumber(share)} ${kpi.unit}" to kpi.name.lowercase()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Impact Ledger", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Green)
                Text("See what your donations are doing", fontSize = 15.sp, color = Muted)
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Green),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("You donated", color = GreenSoft, fontSize = 14.sp)
                    Text(formatChf(total), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Your estimated impact so far", color = GreenSoft, fontSize = 14.sp)
                    highlights.forEach { (amount, what) ->
                        Text("$amount · $what", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item {
            Text("Education projects", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        items(DemoData.projects) { project ->
            ProjectCard(project = project, myDonation = donations[project.id] ?: 0.0, onClick = { onOpen(project.id) })
        }
        item {
            Text(
                "Demo data. Every impact figure is an estimate.",
                fontSize = 12.sp,
                color = Muted,
            )
        }
    }
}

@Composable
private fun ProjectCard(project: Project, myDonation: Double, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(project.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("${project.ngo} · ${project.country} · ${project.cause}", fontSize = 13.sp, color = Muted)
            val kpi = project.kpis.first()
            Text(
                "${formatNumber(kpi.total)} ${kpi.name.lowercase()} so far",
                fontSize = 14.sp,
                color = Ink,
            )
            if (myDonation > 0.0) {
                Text("You gave ${formatChf(myDonation)}", fontSize = 13.sp, color = Green, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ProjectScreen(
    project: Project,
    myDonation: Double,
    onDonate: (Double) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("← All projects", color = Green) }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(project.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("${project.ngo} · ${project.country}", fontSize = 14.sp, color = Muted)
                Text("Promise: ${project.promise}", fontSize = 14.sp, color = Ink)
                Text("Funded: ${formatChf(project.fundedChf)}", fontSize = 14.sp, color = Muted)
            }
        }
        item {
            Text("Results so far", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        items(project.kpis) { kpi ->
            KpiCard(kpi = kpi, project = project, myDonation = myDonation)
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Donate", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("You gave ${formatChf(myDonation)} to this project", fontSize = 14.sp, color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(50.0, 100.0, 500.0).forEach { amount ->
                            if (amount == 100.0) {
                                Button(onClick = { onDonate(amount) }) { Text(formatChf(amount)) }
                            } else {
                                OutlinedButton(onClick = { onDonate(amount) }) { Text(formatChf(amount), color = Green) }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("Latest updates", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
        items(project.updates) { update ->
            Text("• $update", fontSize = 14.sp, color = Ink)
        }
        item {
            Text("Demo data. Every impact figure is an estimate.", fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
private fun KpiCard(kpi: Kpi, project: Project, myDonation: Double) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(kpi.name, fontSize = 14.sp, color = Muted, modifier = Modifier.weight(1f))
                TrustBadge(kpi.trust)
            }
            Text(formatNumber(kpi.total), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(
                "≈ ${formatChf(ImpactMath.costPerResult(kpi, project.fundedChf))} per ${kpi.unit.removeSuffix("s")}",
                fontSize = 13.sp,
                color = Muted,
            )
            if (myDonation > 0.0) {
                val share = ImpactMath.estimatedShare(kpi, myDonation, project.fundedChf)
                Text(
                    "Your estimated share: ≈ ${formatNumber(share)} ${kpi.unit}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Green,
                )
            }
        }
    }
}

@Composable
private fun TrustBadge(trust: TrustLevel) {
    val (bg, fg) = when (trust) {
        TrustLevel.MEASURED -> GreenSoft to Green
        TrustLevel.REPORTED -> Color(0xFFE3EAF7) to Color(0xFF1E3A8A)
        TrustLevel.SELF_REPORTED -> AmberSoft to AmberInk
    }
    Text(
        trust.label,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
